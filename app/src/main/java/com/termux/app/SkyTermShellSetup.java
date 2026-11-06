package com.termux.app;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;

import com.termux.BuildConfig;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Installs the app-owned default shell launcher into the Termux prefix. */
final class SkyTermShellSetup {

    private static final String LOG_TAG = "SkyTermShellSetup";
    private static final String ASSET_NAME = "skyterm-shell";
    private static final String LAUNCHER_NAME = "skyterm-shell";
    private static final String COMMAND_ASSET_NAME = "skyterm-command";
    private static final String COMMAND_NAME = "skyterm";
    private static final String COMMAND_MARKER = "# SKYTERM_MANAGED_COMMAND";
    private static final String VERSION_LINE_PREFIX = "SKYTERM_VERSION=\"";
    private static volatile boolean sLauncherVerified = false;

    private SkyTermShellSetup() {}

    static boolean installLauncher(Context context) {
        File binDirectory = new File(TermuxConstants.TERMUX_PREFIX_DIR, "bin");
        File destination = new File(binDirectory, LAUNCHER_NAME);
        File commandDestination = new File(binDirectory, COMMAND_NAME);
        if (sLauncherVerified && destination.canExecute() && commandDestination.canExecute()) {
            return true;
        }

        if (!binDirectory.isDirectory() && !binDirectory.mkdirs()) {
            Logger.logError(LOG_TAG, "Unable to create Termux prefix bin directory.");
            return false;
        }

        if (!isLauncherUpToDate(context, destination)) {
            File temporary = new File(binDirectory, LAUNCHER_NAME + ".tmp");
            try (InputStream input = context.getAssets().open(ASSET_NAME);
                 FileOutputStream output = new FileOutputStream(temporary, false)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.getFD().sync();
            } catch (IOException e) {
                temporary.delete();
                Logger.logErrorExtended(LOG_TAG, "Unable to install the SkyTerm shell launcher: " + e);
                return false;
            }

            if (!temporary.setExecutable(true, false) || !temporary.renameTo(destination)) {
                temporary.delete();
                Logger.logError(LOG_TAG, "Unable to activate the SkyTerm shell launcher.");
                return false;
            }
        }

        installSkyTermCommand(context, commandDestination);
        boolean ready = destination.canExecute();
        if (ready) {
            sLauncherVerified = true;
        }
        return ready;
    }

    private static boolean isLauncherUpToDate(Context context, File destination) {
        if (!destination.isFile() || !destination.canExecute()) return false;
        try (InputStream assetInput = context.getAssets().open(ASSET_NAME);
             FileInputStream fileInput = new FileInputStream(destination)) {
            byte[] assetBuf = new byte[4096];
            byte[] fileBuf = new byte[4096];
            while (true) {
                int aRead = assetInput.read(assetBuf);
                int fRead = fileInput.read(fileBuf);
                if (aRead != fRead) return false;
                if (aRead == -1) return true;
                for (int i = 0; i < aRead; i++) {
                    if (assetBuf[i] != fileBuf[i]) return false;
                }
            }
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean installSkyTermCommand(Context context, File destination) {
        if ((destination.exists() || isSymbolicLink(destination)) &&
            (isSymbolicLink(destination) || !isSkyTermManagedCommand(destination))) {
            Logger.logWarn(LOG_TAG, "Preserving an existing user command at " + destination.getName());
            return false;
        }

        if (destination.isFile() && destination.canExecute() && isSkyTermCommandCurrentVersion(destination)) {
            return true;
        }

        File temporary = new File(destination.getParentFile(), destination.getName() + ".tmp");
        try (InputStream input = context.getAssets().open(COMMAND_ASSET_NAME);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            String script = new String(bytes.toByteArray(), StandardCharsets.UTF_8)
                .replace("@SKYTERM_VERSION@", BuildConfig.VERSION_NAME);

            try (FileOutputStream output = new FileOutputStream(temporary, false)) {
                output.write(script.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
        } catch (IOException e) {
            temporary.delete();
            Logger.logErrorExtended(LOG_TAG, "Unable to install the SkyTerm command: " + e);
            return false;
        }

        if (!temporary.setExecutable(true, false) || !temporary.renameTo(destination)) {
            temporary.delete();
            Logger.logError(LOG_TAG, "Unable to activate the SkyTerm command.");
            return false;
        }
        return destination.canExecute();
    }

    private static boolean isSkyTermManagedCommand(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            reader.readLine(); // Shebang
            return COMMAND_MARKER.equals(reader.readLine());
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isSkyTermCommandCurrentVersion(File file) {
        String expected = VERSION_LINE_PREFIX + BuildConfig.VERSION_NAME + "\"";
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            for (int i = 0; i < 8 && (line = reader.readLine()) != null; i++) {
                if (expected.equals(line)) return true;
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isSymbolicLink(File file) {
        try {
            Os.readlink(file.getAbsolutePath());
            return true;
        } catch (ErrnoException e) {
            return false;
        }
    }
}
