package com.termux.app;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.StatFs;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.termux.R;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Read-only, lifecycle-bound overview of device resources and SkyTerm sessions. */
public final class SkyTermDashboardActivity extends AppCompatActivity {

    private static final int COLOR_BACKGROUND = 0xff0b1117;
    private static final int COLOR_SURFACE = 0xff141e28;
    private static final int COLOR_ACCENT = 0xff77d9c6;
    private static final long REFRESH_INTERVAL_MS = 2000L;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mReader = Executors.newSingleThreadExecutor();
    private final List<Integer> mRoots = new ArrayList<>();
    private boolean mBound;
    private boolean mReadInFlight;
    private boolean mCpuSeeded;
    private boolean mNetworkSeeded;
    private long mLastCpuTotal;
    private long mLastCpuIdle;
    private long mLastRxBytes;
    private long mLastTxBytes;
    private long mLastNetworkTime;
    private TermuxService mService;

    private MetricCard mCpuCard;
    private MetricCard mMemoryCard;
    private MetricCard mStorageCard;
    private MetricCard mNetworkCard;
    private MetricCard mSessionsCard;
    private MetricCard mProcessesCard;
    private MetricCard mBatteryCard;
    private TextView mUpdatedAt;
    private TextView mServiceStatus;

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mService = ((TermuxService.LocalBinder) binder).service;
            mServiceStatus.setText(R.string.dashboard_live_status);
            refreshDashboard();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
            mServiceStatus.setText(R.string.dashboard_service_disconnected);
            refreshDashboard();
        }
    };

    private final Runnable mRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshDashboard();
            mHandler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createLayout();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent serviceIntent = new Intent(this, TermuxService.class);
        try {
            startService(serviceIntent);
            mBound = bindService(serviceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
        } catch (Exception ignored) {
            mServiceStatus.setText(R.string.dashboard_service_disconnected);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.removeCallbacks(mRefreshRunnable);
        mHandler.post(mRefreshRunnable);
    }

    @Override
    protected void onPause() {
        mHandler.removeCallbacks(mRefreshRunnable);
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (mBound) {
            unbindService(mServiceConnection);
            mBound = false;
        }
        mService = null;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mReader.shutdownNow();
        super.onDestroy();
    }

    private void createLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BACKGROUND);

        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle(R.string.dashboard_title);
        toolbar.setTitleTextColor(0xfff3f7fa);
        toolbar.setBackgroundColor(COLOR_BACKGROUND);
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert);
        toolbar.setNavigationContentDescription(android.R.string.cancel);
        toolbar.setNavigationOnClickListener(view -> finish());
        root.addView(toolbar, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(24));
        scrollView.addView(content);

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout titleStack = new LinearLayout(this);
        titleStack.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(getString(R.string.dashboard_heading), 22, true, 0xfff3f7fa);
        TextView subtitle = text(getString(R.string.dashboard_subtitle), 13, false, 0xff9aabb9);
        titleStack.addView(title);
        titleStack.addView(subtitle);
        heading.addView(titleStack, new LinearLayout.LayoutParams(0, -2, 1f));

        Button refresh = new Button(this);
        refresh.setText(R.string.dashboard_refresh);
        refresh.setTextColor(COLOR_ACCENT);
        refresh.setOnClickListener(view -> refreshDashboard());
        heading.addView(refresh, new LinearLayout.LayoutParams(-2, dp(46)));
        content.addView(heading, spaced(-1, -2, 0, 0, 0, dp(12)));

        LinearLayout status = new LinearLayout(this);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dp(14), dp(10), dp(14), dp(10));
        status.setBackground(roundRect(COLOR_SURFACE, dp(14)));
        TextView liveDot = text("●", 12, true, COLOR_ACCENT);
        status.addView(liveDot, spaced(-2, -2, 0, 0, dp(9), 0));
        mServiceStatus = text(getString(R.string.dashboard_connecting), 13, false, 0xffdce5eb);
        status.addView(mServiceStatus, new LinearLayout.LayoutParams(0, -2, 1f));
        mUpdatedAt = text(getString(R.string.dashboard_waiting), 11, false, 0xff9aabb9);
        status.addView(mUpdatedAt);
        content.addView(status, spaced(-1, -2, 0, 0, 0, dp(14)));

        mCpuCard = createCard(R.string.dashboard_cpu, "◉", true);
        mMemoryCard = createCard(R.string.dashboard_memory, "▤", true);
        mStorageCard = createCard(R.string.dashboard_storage, "▣", true);
        mNetworkCard = createCard(R.string.dashboard_network, "↕", false);
        mSessionsCard = createCard(R.string.dashboard_sessions, "▤", false);
        mProcessesCard = createCard(R.string.dashboard_processes, "⌘", false);
        mBatteryCard = createCard(R.string.dashboard_battery, "⚡", true);
        addCardRow(content, mCpuCard, mMemoryCard);
        addCardRow(content, mStorageCard, mNetworkCard);
        addCardRow(content, mSessionsCard, mProcessesCard);
        content.addView(mBatteryCard.container, spaced(-1, -2, 0, 0, 0, dp(10)));

        TextView note = text(getString(R.string.dashboard_scope_note), 12, false, 0xff8193a1);
        note.setPadding(dp(2), dp(12), dp(2), 0);
        content.addView(note);

        root.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        getWindow().setStatusBarColor(COLOR_BACKGROUND);
        getWindow().setNavigationBarColor(COLOR_BACKGROUND);
    }

    private MetricCard createCard(int label, String icon, boolean hasProgress) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(13));
        card.setBackground(roundRect(COLOR_SURFACE, dp(16)));

        LinearLayout labelRow = new LinearLayout(this);
        labelRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView iconView = text(icon, 16, true, COLOR_ACCENT);
        iconView.setGravity(Gravity.CENTER);
        iconView.setBackground(roundRect(0xff20353a, dp(10)));
        labelRow.addView(iconView, spaced(dp(32), dp(32), 0, 0, 0, 0));
        TextView labelView = text(getString(label), 13, true, 0xffb8c6d0);
        labelView.setPadding(dp(8), 0, 0, 0);
        labelRow.addView(labelView);
        card.addView(labelRow);

        TextView value = text("—", 25, true, 0xfff3f7fa);
        value.setSingleLine(true);
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(value, spaced(-1, -2, 0, dp(12), 0, dp(4)));

        TextView detail = text(getString(R.string.dashboard_waiting), 12, false, 0xff9aabb9);
        detail.setMinLines(2);
        detail.setMaxLines(2);
        card.addView(detail);

        ProgressBar progress = null;
        if (hasProgress) {
            progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(100);
            progress.setProgressTintList(android.content.res.ColorStateList.valueOf(COLOR_ACCENT));
            progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff29343d));
            card.addView(progress, spaced(-1, dp(4), 0, dp(12), 0, 0));
        }
        return new MetricCard(card, value, detail, progress);
    }

    private void addCardRow(LinearLayout parent, MetricCard left, MetricCard right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(left.container, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, -2, 1f);
        rightParams.setMargins(dp(10), 0, 0, 0);
        row.addView(right.container, rightParams);
        parent.addView(row, spaced(-1, -2, 0, 0, 0, dp(10)));
    }

    private void refreshDashboard() {
        if (mReadInFlight || isFinishing() || isDestroyed()) return;
        mReadInFlight = true;
        final boolean serviceReady = mService != null;
        final List<SessionRoot> roots = getSessionRoots();
        mReader.execute(() -> {
            DashboardSnapshot snapshot = readSnapshot(roots, serviceReady);
            mHandler.post(() -> {
                mReadInFlight = false;
                if (!isFinishing() && !isDestroyed()) renderSnapshot(snapshot);
            });
        });
    }

    private List<SessionRoot> getSessionRoots() {
        List<SessionRoot> roots = new ArrayList<>();
        if (mService == null) return roots;
        List<TermuxSession> sessions = new ArrayList<>(mService.getTermuxSessions());
        for (TermuxSession session : sessions) {
            TerminalSession terminal = session.getTerminalSession();
            if (terminal == null) continue;
            String label = terminal.mSessionName;
            if (label == null || label.trim().isEmpty()) label = getString(R.string.application_name);
            roots.add(new SessionRoot(terminal.getPid(), label, terminal.isRunning()));
        }
        return roots;
    }

    private DashboardSnapshot readSnapshot(List<SessionRoot> roots, boolean serviceReady) {
        double cpuPercent = readCpuPercent();
        long totalMemory = 0;
        long availableMemory = 0;
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (activityManager != null) {
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(memory);
            totalMemory = memory.totalMem;
            availableMemory = memory.availMem;
        }

        long totalStorage = 0;
        long availableStorage = 0;
        try {
            StatFs storage = new StatFs(getFilesDir().getAbsolutePath());
            totalStorage = storage.getBlockCountLong() * storage.getBlockSizeLong();
            availableStorage = storage.getAvailableBlocksLong() * storage.getBlockSizeLong();
        } catch (IllegalArgumentException ignored) {
            // The app data volume can be unavailable briefly during storage changes.
        }

        NetworkSnapshot network = readNetworkSnapshot();
        BatterySnapshot battery = readBatterySnapshot();
        List<SessionRoot> activeRoots = new ArrayList<>();
        for (SessionRoot root : roots) if (root.running && root.pid > 0) activeRoots.add(root);
        int processCount = serviceReady ? countSessionProcesses(activeRoots) : -1;
        return new DashboardSnapshot(cpuPercent, totalMemory, availableMemory,
            totalStorage, availableStorage, network, battery, roots, activeRoots.size(), processCount, serviceReady);
    }

    private BatterySnapshot readBatterySnapshot() {
        Intent batteryIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int percent = -1;
        String statusText = getString(R.string.dashboard_waiting);
        double temperatureCelsius = -1;
        if (batteryIntent != null) {
            int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) {
                percent = Math.max(0, Math.min(100, Math.round(level * 100f / scale)));
            }
            int status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            if (status == BatteryManager.BATTERY_STATUS_CHARGING) {
                if (plugged == BatteryManager.BATTERY_PLUGGED_AC) statusText = getString(R.string.dashboard_battery_charging_ac);
                else if (plugged == BatteryManager.BATTERY_PLUGGED_USB) statusText = getString(R.string.dashboard_battery_charging_usb);
                else if (plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) statusText = getString(R.string.dashboard_battery_charging_wireless);
                else statusText = getString(R.string.dashboard_battery_charging);
            } else if (status == BatteryManager.BATTERY_STATUS_FULL) {
                statusText = getString(R.string.dashboard_battery_full);
            } else {
                statusText = getString(R.string.dashboard_battery_discharging);
            }
            int tempTenths = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            if (tempTenths > 0) temperatureCelsius = tempTenths / 10.0;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean unrestricted = pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            && pm.isIgnoringBatteryOptimizations(getPackageName());
        return new BatterySnapshot(percent, statusText, temperatureCelsius, unrestricted);
    }

    private double readCpuPercent() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"))) {
            String line = reader.readLine();
            if (line == null || !line.startsWith("cpu ")) return -1;
            String[] fields = line.trim().split("\\s+");
            long total = 0;
            long idle = 0;
            for (int i = 1; i < fields.length; i++) {
                long value = Long.parseLong(fields[i]);
                total += value;
                if (i == 4 || i == 5) idle += value;
            }
            if (!mCpuSeeded) {
                mCpuSeeded = true;
                mLastCpuTotal = total;
                mLastCpuIdle = idle;
                return -1;
            }
            long totalDelta = total - mLastCpuTotal;
            long idleDelta = Math.max(0, idle - mLastCpuIdle);
            mLastCpuTotal = total;
            mLastCpuIdle = idle;
            if (totalDelta <= 0) return -1;
            return Math.max(0, Math.min(100, (totalDelta - idleDelta) * 100.0 / totalDelta));
        } catch (IOException | NumberFormatException ignored) {
            return -1;
        }
    }

    @SuppressWarnings("deprecation")
    private NetworkSnapshot readNetworkSnapshot() {
        String type = getString(R.string.dashboard_network_offline);
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (manager != null) {
            Network network = manager.getActiveNetwork();
            NetworkCapabilities capabilities = network == null ? null : manager.getNetworkCapabilities(network);
            if (capabilities != null) {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) type = getString(R.string.dashboard_network_wifi);
                else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) type = getString(R.string.dashboard_network_cellular);
                else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) type = getString(R.string.dashboard_network_ethernet);
                else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) type = getString(R.string.dashboard_network_vpn);
                else type = getString(R.string.dashboard_network_connected);
            }
        }

        long rx = TrafficStats.getUidRxBytes(Process.myUid());
        long tx = TrafficStats.getUidTxBytes(Process.myUid());
        long now = android.os.SystemClock.elapsedRealtime();
        long rxRate = -1;
        long txRate = -1;
        if (mNetworkSeeded && now > mLastNetworkTime && rx >= mLastRxBytes && tx >= mLastTxBytes
            && rx != TrafficStats.UNSUPPORTED && tx != TrafficStats.UNSUPPORTED) {
            long elapsed = now - mLastNetworkTime;
            rxRate = (rx - mLastRxBytes) * 1000L / elapsed;
            txRate = (tx - mLastTxBytes) * 1000L / elapsed;
        }
        mNetworkSeeded = rx != TrafficStats.UNSUPPORTED && tx != TrafficStats.UNSUPPORTED;
        mLastRxBytes = rx;
        mLastTxBytes = tx;
        mLastNetworkTime = now;
        return new NetworkSnapshot(type, rxRate, txRate);
    }

    private int countSessionProcesses(List<SessionRoot> roots) {
        Set<Integer> visited = new HashSet<>();
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        for (SessionRoot root : roots) pending.add(root.pid);
        while (!pending.isEmpty() && visited.size() < 10000) {
            int pid = pending.removeFirst();
            if (!visited.add(pid)) continue;
            File children = new File("/proc/" + pid + "/task/" + pid + "/children");
            try (BufferedReader reader = new BufferedReader(new FileReader(children))) {
                String line = reader.readLine();
                if (line == null || line.trim().isEmpty()) continue;
                for (String child : line.trim().split("\\s+")) {
                    try {
                        int childPid = Integer.parseInt(child);
                        if (childPid > 0 && !visited.contains(childPid)) pending.addLast(childPid);
                    } catch (NumberFormatException ignored) {
                        // Ignore malformed kernel entries.
                    }
                }
            } catch (IOException ignored) {
                // Some Android kernels restrict child-list reads; count visible processes only.
            }
        }
        return visited.size();
    }

    private void renderSnapshot(DashboardSnapshot snapshot) {
        if (snapshot.cpuPercent >= 0) {
            mCpuCard.set(String.format(Locale.getDefault(), "%.0f%%", snapshot.cpuPercent),
                getString(R.string.dashboard_cpu_detail), (int) Math.round(snapshot.cpuPercent));
        } else {
            mCpuCard.set(getString(R.string.dashboard_measuring), getString(R.string.dashboard_cpu_detail), 0);
        }

        long usedMemory = Math.max(0, snapshot.totalMemory - snapshot.availableMemory);
        int memoryPercent = percent(usedMemory, snapshot.totalMemory);
        mMemoryCard.set(memoryPercent < 0 ? "—" : memoryPercent + "%",
            getString(R.string.dashboard_memory_detail, formatBytes(usedMemory), formatBytes(snapshot.totalMemory),
                formatBytes(snapshot.availableMemory)), Math.max(0, memoryPercent));

        long usedStorage = Math.max(0, snapshot.totalStorage - snapshot.availableStorage);
        int storagePercent = percent(usedStorage, snapshot.totalStorage);
        mStorageCard.set(storagePercent < 0 ? "—" : storagePercent + "%",
            getString(R.string.dashboard_storage_detail, formatBytes(usedStorage), formatBytes(snapshot.totalStorage),
                formatBytes(snapshot.availableStorage)), Math.max(0, storagePercent));

        String networkDetail = getString(R.string.dashboard_network_detail,
            formatRate(snapshot.network.rxRate), formatRate(snapshot.network.txRate));
        mNetworkCard.set(snapshot.network.type, networkDetail, -1);

        String tempString = snapshot.battery.temperatureCelsius > 0
            ? String.format(Locale.getDefault(), "%.1f°C", snapshot.battery.temperatureCelsius)
            : "—";
        String optString = getString(snapshot.battery.unrestricted
            ? R.string.dashboard_battery_opt_unrestricted
            : R.string.dashboard_battery_opt_restricted);
        mBatteryCard.set(
            snapshot.battery.percent < 0 ? "—" : snapshot.battery.percent + "%",
            getString(R.string.dashboard_battery_detail, snapshot.battery.statusText, tempString, optString),
            Math.max(0, snapshot.battery.percent));

        if (snapshot.serviceReady) {
            String sessionSummary = formatSessionNames(snapshot.roots);
            mSessionsCard.set(String.valueOf(snapshot.activeSessions), sessionSummary, -1);
            mProcessesCard.set(snapshot.processCount < 0 ? "—" : String.valueOf(snapshot.processCount),
                getString(R.string.dashboard_process_detail), -1);
            mServiceStatus.setText(R.string.dashboard_live_status);
        } else {
            mSessionsCard.set("—", getString(R.string.dashboard_connecting), -1);
            mProcessesCard.set("—", getString(R.string.dashboard_connecting), -1);
            mServiceStatus.setText(R.string.dashboard_service_disconnected);
        }
        mUpdatedAt.setText(getString(R.string.dashboard_updated, android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())));
    }

    private String formatSessionNames(List<SessionRoot> roots) {
        StringBuilder names = new StringBuilder();
        int shown = 0;
        for (SessionRoot root : roots) {
            if (!root.running) continue;
            if (shown > 0) names.append(" • ");
            names.append(root.label);
            shown++;
            if (shown == 3) break;
        }
        if (shown == 0) return getString(R.string.dashboard_no_sessions);
        int remaining = 0;
        for (SessionRoot root : roots) if (root.running) remaining++;
        if (remaining > shown) names.append(" +").append(remaining - shown);
        return names.toString();
    }

    private int percent(long value, long total) {
        if (total <= 0) return -1;
        return (int) Math.max(0, Math.min(100, Math.round(value * 100.0 / total)));
    }

    private String formatBytes(long bytes) {
        if (bytes <= 0) return "—";
        double gib = bytes / (1024.0 * 1024.0 * 1024.0);
        if (gib >= 1.0) return String.format(Locale.getDefault(), "%.1f GiB", gib);
        return String.format(Locale.getDefault(), "%.0f MiB", bytes / (1024.0 * 1024.0));
    }

    private String formatRate(long bytesPerSecond) {
        if (bytesPerSecond < 0) return "—";
        if (bytesPerSecond >= 1024L * 1024L)
            return String.format(Locale.getDefault(), "%.1f MiB/s", bytesPerSecond / (1024.0 * 1024.0));
        if (bytesPerSecond >= 1024L)
            return String.format(Locale.getDefault(), "%.0f KiB/s", bytesPerSecond / 1024.0);
        return bytesPerSecond + " B/s";
    }

    private TextView text(String value, int sizeSp, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout.LayoutParams spaced(int width, int height, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class MetricCard {
        final LinearLayout container;
        final TextView value;
        final TextView detail;
        final ProgressBar progress;

        MetricCard(LinearLayout container, TextView value, TextView detail, ProgressBar progress) {
            this.container = container;
            this.value = value;
            this.detail = detail;
            this.progress = progress;
        }

        void set(String newValue, String newDetail, int progressValue) {
            value.setText(newValue);
            detail.setText(newDetail);
            if (progress != null) progress.setProgress(Math.max(0, Math.min(100, progressValue)));
        }
    }

    private static final class SessionRoot {
        final int pid;
        final String label;
        final boolean running;

        SessionRoot(int pid, String label, boolean running) {
            this.pid = pid;
            this.label = label;
            this.running = running;
        }
    }

    private static final class NetworkSnapshot {
        final String type;
        final long rxRate;
        final long txRate;

        NetworkSnapshot(String type, long rxRate, long txRate) {
            this.type = type;
            this.rxRate = rxRate;
            this.txRate = txRate;
        }
    }

    private static final class BatterySnapshot {
        final int percent;
        final String statusText;
        final double temperatureCelsius;
        final boolean unrestricted;

        BatterySnapshot(int percent, String statusText, double temperatureCelsius, boolean unrestricted) {
            this.percent = percent;
            this.statusText = statusText;
            this.temperatureCelsius = temperatureCelsius;
            this.unrestricted = unrestricted;
        }
    }

    private static final class DashboardSnapshot {
        final double cpuPercent;
        final long totalMemory;
        final long availableMemory;
        final long totalStorage;
        final long availableStorage;
        final NetworkSnapshot network;
        final BatterySnapshot battery;
        final List<SessionRoot> roots;
        final int activeSessions;
        final int processCount;
        final boolean serviceReady;

        DashboardSnapshot(double cpuPercent, long totalMemory, long availableMemory,
                          long totalStorage, long availableStorage, NetworkSnapshot network,
                          BatterySnapshot battery, List<SessionRoot> roots, int activeSessions,
                          int processCount, boolean serviceReady) {
            this.cpuPercent = cpuPercent;
            this.totalMemory = totalMemory;
            this.availableMemory = availableMemory;
            this.totalStorage = totalStorage;
            this.availableStorage = availableStorage;
            this.network = network;
            this.battery = battery;
            this.roots = roots;
            this.activeSessions = activeSessions;
            this.processCount = processCount;
            this.serviceReady = serviceReady;
        }
    }
}
