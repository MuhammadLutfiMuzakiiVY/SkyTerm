package com.termux.app;

import android.app.ActivityManager;
import android.app.NotificationManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.PowerManager;
import android.os.StatFs;

import androidx.annotation.NonNull;

import com.termux.BuildConfig;
import com.termux.shared.termux.TermuxConstants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Read-only checks for common Android and Termux stability problems. */
public final class StabilityDiagnostics {

    private static final long LOW_STORAGE_BYTES = 256L * 1024L * 1024L;
    private static final long LOW_MEMORY_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_CRASH_LOG_BYTES = 64 * 1024;

    private StabilityDiagnostics() {}

    public enum Level {
        OK,
        INFO,
        WARNING,
        ERROR
    }

    public static final class Check {
        public final String name;
        public final Level level;
        public final String detail;

        Check(String name, Level level, String detail) {
            this.name = name;
            this.level = level;
            this.detail = detail;
        }
    }

    @NonNull
    public static List<Check> collect(@NonNull Context context) {
        List<Check> checks = new ArrayList<>();
        checks.add(safeCheck("Versi Android", StabilityDiagnostics::checkRuntime));
        checks.add(safeCheck("Library native / ABI", () -> checkNativeCore(context)));
        checks.add(safeCheck("Filesystem Termux", StabilityDiagnostics::checkTermuxFilesystem));
        checks.add(safeCheck("Ubuntu / PRoot", StabilityDiagnostics::checkUbuntu));
        checks.add(safeCheck("Resource", () -> checkResources(context)));
        checks.add(safeCheck("Pembatasan Android", () -> checkAndroidRestrictions(context)));
        checks.add(safeCheck("Jaringan", () -> checkNetwork(context)));
        checks.add(safeCheck("Log crash", StabilityDiagnostics::checkCrashLog));
        return checks;
    }

    private interface CheckProducer {
        Check run();
    }

    private static Check safeCheck(String name, CheckProducer producer) {
        try {
            return producer.run();
        } catch (Exception | LinkageError e) {
            return new Check(name, Level.WARNING,
                "Pemeriksaan tidak dapat diselesaikan (" + e.getClass().getSimpleName() + ").");
        }
    }

    @NonNull
    public static String createReport(@NonNull Context context, @NonNull List<Check> checks) {
        StringBuilder report = new StringBuilder();
        SimpleDateFormat timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT);
        timestamp.setTimeZone(TimeZone.getTimeZone("UTC"));

        report.append("SkyTerm stability diagnostics\n");
        report.append("Generated: ").append(timestamp.format(new Date())).append('\n');
        report.append("App: ").append(BuildConfig.VERSION_NAME).append(" (code ")
            .append(BuildConfig.VERSION_CODE).append(")\n");
        report.append("Android: API ").append(Build.VERSION.SDK_INT)
            .append(" (release ").append(Build.VERSION.RELEASE).append(")\n");
        report.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        report.append("Supported ABIs: ").append(join(Build.SUPPORTED_ABIS)).append("\n\n");

        for (Check check : checks) {
            report.append('[').append(check.level.name()).append("] ")
                .append(check.name).append("\n  ").append(check.detail).append('\n');
        }
        report.append("\nThis report is generated locally. It does not repair files or send data.");
        return report.toString();
    }

    private static Check checkRuntime() {
        if (Build.VERSION.SDK_INT < BuildConfig.TERMUX_APP__BOOTSTRAP_MIN_SDK) {
            return new Check("Versi Android", Level.ERROR,
                "API perangkat berada di bawah batas bootstrap yang didukung.");
        }
        return new Check("Versi Android", Level.OK,
            "API " + Build.VERSION.SDK_INT + " memenuhi batas minimum aplikasi.");
    }

    private static Check checkNativeCore(Context context) {
        int version = RustCoreBridge.apiVersionOrZero();
        File nativeDirectory = new File(context.getApplicationInfo().nativeLibraryDir);
        File terminalLibrary = new File(nativeDirectory, "libtermux.so");
        File bootstrapLibrary = new File(nativeDirectory, "libtermux-bootstrap.so");
        File rustLibrary = new File(nativeDirectory, "libtermux_core.so");
        List<String> missingCriticalLibraries = new ArrayList<>();
        if (!terminalLibrary.isFile()) missingCriticalLibraries.add("libtermux.so");
        if (!bootstrapLibrary.isFile()) missingCriticalLibraries.add("libtermux-bootstrap.so");
        if (!missingCriticalLibraries.isEmpty()) {
            return new Check("Library native / ABI", Level.ERROR,
                "Library native penting tidak ditemukan: " + join(missingCriticalLibraries) +
                    ". Periksa ABI APK dan instalasi aplikasi.");
        }

        if (version > 0) {
            return new Check("Library native / ABI", Level.OK,
                "Library terminal dan bootstrap ditemukan; Rust core termuat dengan API JNI " + version +
                    ". ABI perangkat: " + join(Build.SUPPORTED_ABIS) + ".");
        }

        String reason = RustCoreBridge.getFailureMessage();
        if (reason == null || reason.trim().isEmpty()) reason = "Library atau API JNI tidak tersedia.";
        return new Check("Library native / ABI", Level.WARNING,
            "Library terminal dan bootstrap ditemukan, tetapi Rust core tidak aktif (file: " +
                (rustLibrary.isFile() ? "ada" : "tidak ada") + "). Runtime Termux tetap memakai jalur yang ada. " + reason);
    }

    private static Check checkTermuxFilesystem() {
        File prefix = TermuxConstants.TERMUX_PREFIX_DIR;
        File filesDirectory = TermuxConstants.TERMUX_FILES_DIR;
        if (!filesDirectory.isDirectory() || !filesDirectory.canRead() || !filesDirectory.canWrite()) {
            return new Check("Filesystem Termux", Level.ERROR,
                "Direktori data aplikasi tidak tersedia atau tidak dapat dibaca dan ditulis.");
        }
        if (!prefix.isDirectory()) {
            return new Check("Filesystem Termux", Level.ERROR,
                "Direktori $PREFIX belum tersedia. Bootstrap mungkin belum selesai.");
        }

        StringBuilder missing = new StringBuilder();
        appendMissing(missing, prefix, "bin/bash", true);
        appendMissing(missing, prefix, "bin/apt", true);
        appendMissing(missing, prefix, "bin/dpkg", true);
        appendMissing(missing, prefix, "var/lib/dpkg/status", false);
        if (missing.length() > 0) {
            return new Check("Filesystem Termux", Level.WARNING,
                "Struktur $PREFIX ada, tetapi komponen berikut tidak ditemukan atau tidak dapat dieksekusi: " + missing);
        }
        return new Check("Filesystem Termux", Level.OK,
            "$PREFIX dapat dibaca; Bash, APT, dpkg, dan status paket ditemukan.");
    }

    private static Check checkUbuntu() {
        File prefix = TermuxConstants.TERMUX_PREFIX_DIR;
        File prootDistro = new File(prefix, "bin/proot-distro");
        File currentRootfsShell = new File(prefix,
            "var/lib/proot-distro/containers/ubuntu/rootfs/bin/bash");
        File legacyRootfsShell = new File(prefix,
            "var/lib/proot-distro/installed-rootfs/ubuntu/bin/bash");

        if (!prootDistro.isFile() || !prootDistro.canExecute()) {
            return new Check("Ubuntu / PRoot", Level.INFO,
                "proot-distro belum tersedia. Launcher SkyTerm akan mencoba memasangnya saat sesi pertama; koneksi internet diperlukan.");
        }
        if (currentRootfsShell.canExecute() || legacyRootfsShell.canExecute()) {
            return new Check("Ubuntu / PRoot", Level.OK,
                "proot-distro dan shell rootfs Ubuntu ditemukan.");
        }
        return new Check("Ubuntu / PRoot", Level.WARNING,
            "proot-distro ada tetapi rootfs Ubuntu belum ditemukan. Pemasangan awal perlu jaringan dan ruang penyimpanan.");
    }

    private static Check checkResources(Context context) {
        long availableStorage = -1;
        long availableMemory = -1;
        boolean lowMemory = false;
        try {
            StatFs statFs = new StatFs(context.getFilesDir().getAbsolutePath());
            availableStorage = statFs.getAvailableBytes();
        } catch (Exception ignored) {
            // Keep the report useful even when storage statistics cannot be read.
        }

        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager != null) {
            ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(memoryInfo);
            availableMemory = memoryInfo.availMem;
            lowMemory = memoryInfo.lowMemory;
        }

        List<String> warnings = new ArrayList<>();
        if (availableStorage >= 0 && availableStorage < LOW_STORAGE_BYTES)
            warnings.add("ruang data aplikasi di bawah 256 MiB");
        if (lowMemory || (availableMemory >= 0 && availableMemory < LOW_MEMORY_BYTES))
            warnings.add("RAM perangkat rendah");

        String detail = "Ruang data tersedia: " + formatBytes(availableStorage) +
            "; RAM tersedia: " + formatBytes(availableMemory) + ".";
        if (!warnings.isEmpty()) return new Check("Resource", Level.WARNING, detail + " Indikasi: " + join(warnings) + ".");
        if (availableStorage < 0 && availableMemory < 0)
            return new Check("Resource", Level.INFO, "Statistik ruang penyimpanan dan RAM tidak tersedia.");
        return new Check("Resource", Level.OK, detail);
    }

    private static Check checkAndroidRestrictions(Context context) {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        List<String> restrictions = new ArrayList<>();
        if (powerManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !powerManager.isIgnoringBatteryOptimizations(context.getPackageName())) {
            restrictions.add("optimisasi baterai dapat membatasi proses terminal di latar belakang");
        }
        if (powerManager != null && powerManager.isPowerSaveMode()) {
            restrictions.add("mode hemat baterai aktif");
        }

        NotificationManager notificationManager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null && !notificationManager.areNotificationsEnabled()) {
            restrictions.add("notifikasi SkyTerm dinonaktifkan");
        }

        if (!restrictions.isEmpty()) {
            return new Check("Pembatasan Android", Level.WARNING,
                join(restrictions) + ". Pemeriksaan ini tidak mengubah pengaturan perangkat.");
        }
        if (powerManager == null && notificationManager == null) {
            return new Check("Pembatasan Android", Level.INFO,
                "Status baterai dan notifikasi tidak dapat diperiksa pada perangkat ini.");
        }
        return new Check("Pembatasan Android", Level.OK,
            "Optimisasi baterai dan notifikasi aplikasi tidak menunjukkan pembatasan.");
    }

    private static Check checkNetwork(Context context) {
        ConnectivityManager connectivityManager =
            (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            return new Check("Jaringan", Level.INFO, "Status jaringan tidak tersedia.");
        }

        try {
            Network network = connectivityManager.getActiveNetwork();
            NetworkCapabilities capabilities = network == null ? null : connectivityManager.getNetworkCapabilities(network);
            if (capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return new Check("Jaringan", Level.WARNING,
                    "Tidak ada koneksi dengan kemampuan internet yang terdeteksi; diagnosis ini tidak menguji DNS atau server repository.");
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                return new Check("Jaringan", Level.WARNING,
                    "Jaringan tersedia tetapi Android belum memvalidasi akses internet.");
            }
            return new Check("Jaringan", Level.OK,
                "Android melaporkan koneksi internet aktif; DNS, TLS, dan mirror paket belum diuji.");
        } catch (SecurityException e) {
            return new Check("Jaringan", Level.INFO,
                "Akses status jaringan ditolak oleh Android.");
        }
    }

    private static Check checkCrashLog() {
        File current = new File(TermuxConstants.TERMUX_CRASH_LOG_FILE_PATH);
        File backup = new File(TermuxConstants.TERMUX_CRASH_LOG_BACKUP_FILE_PATH);
        File latest = current.isFile() && (!backup.isFile() || current.lastModified() >= backup.lastModified())
            ? current : backup;
        if (!latest.isFile()) {
            return new Check("Log crash", Level.OK,
                "Tidak ada log crash tersimpan yang menunggu pemeriksaan.");
        }

        String crashText = readLimited(latest);
        String category = classifyCrash(crashText);
        return new Check("Log crash", Level.WARNING,
            "Log tersimpan (" + latest.getName() + "); klasifikasi awal: " + category +
                ". Baca stack trace untuk memastikan penyebab; log tidak disalin ke laporan otomatis.");
    }

    private static String classifyCrash(String crashText) {
        String text = crashText == null ? "" : crashText.toLowerCase(Locale.ROOT);
        if (text.contains("outofmemoryerror") || text.contains("failed to allocate"))
            return "tekanan memori";
        if (text.contains("unsatisfiedlinkerror") || text.contains("dlopen failed") || text.contains("wrong elf class"))
            return "library native atau ABI";
        if (text.contains("sigsegv") || text.contains("sigbus") || text.contains("fatal signal"))
            return "crash native";
        if (text.contains("no space left on device") || text.contains("enospc"))
            return "ruang penyimpanan habis";
        if (text.contains("permission denied") || text.contains("eacces"))
            return "permission atau akses filesystem";
        if (text.contains("unknownhostexception") || text.contains("sslhandshakeexception") || text.contains("network is unreachable"))
            return "koneksi jaringan atau TLS";
        if (text.contains("sqliteexception") || text.contains("database is locked"))
            return "database";
        if (!text.isEmpty()) return "exception aplikasi; periksa stack trace";
        return "log kosong atau tidak dapat dibaca";
    }

    private static void appendMissing(StringBuilder missing, File prefix, String relativePath,
                                      boolean executable) {
        File file = new File(prefix, relativePath);
        if (!file.exists() || (executable && !file.canExecute())) {
            if (missing.length() > 0) missing.append(", ");
            missing.append(relativePath);
        }
    }

    private static String readLimited(File file) {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1 && output.size() < MAX_CRASH_LOG_BYTES) {
                output.write(buffer, 0, Math.min(read, MAX_CRASH_LOG_BYTES - output.size()));
            }
            return new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return "";
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) return "tidak tersedia";
        double mebibytes = bytes / (1024.0 * 1024.0);
        if (mebibytes >= 1024.0) return String.format(Locale.ROOT, "%.1f GiB", mebibytes / 1024.0);
        return String.format(Locale.ROOT, "%.0f MiB", mebibytes);
    }

    private static String join(String[] values) {
        if (values == null || values.length == 0) return "tidak tersedia";
        StringBuilder joined = new StringBuilder();
        for (String value : values) {
            if (joined.length() > 0) joined.append(", ");
            joined.append(value);
        }
        return joined.toString();
    }

    private static String join(List<String> values) {
        StringBuilder joined = new StringBuilder();
        for (String value : values) {
            if (joined.length() > 0) joined.append(", ");
            joined.append(value);
        }
        return joined.toString();
    }
}
