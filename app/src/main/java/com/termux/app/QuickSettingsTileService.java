package com.termux.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.termux.R;
import com.termux.shared.logger.Logger;
import com.termux.shared.net.uri.UriUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;

import java.io.File;

@RequiresApi(api = Build.VERSION_CODES.N)
public final class QuickSettingsTileService extends TileService {

    public static final String PREFERENCES_NAME = "skyterm_system_features";
    public static final String KEY_QUICK_TILE_COMMAND = "quick_tile_command";
    public static final String KEY_BOOT_TASKS_ENABLED = "boot_tasks_enabled";

    private static final String LOG_TAG = "QuickSettingsTileService";

    @NonNull
    public static String getQuickTileCommand(@NonNull Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        String command = prefs.getString(KEY_QUICK_TILE_COMMAND, "");
        return command == null ? "" : command.trim();
    }

    public static void setQuickTileCommand(@NonNull Context context, @NonNull String command) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_QUICK_TILE_COMMAND, command.trim())
            .apply();
    }

    public static boolean isBootTasksEnabled(@NonNull Context context) {
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BOOT_TASKS_ENABLED, true);
    }

    public static void setBootTasksEnabled(@NonNull Context context, boolean enabled) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BOOT_TASKS_ENABLED, enabled)
            .apply();
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTileState();
    }

    @Override
    public void onClick() {
        super.onClick();
        Runnable action = this::executeConfiguredCommand;
        if (isLocked()) {
            unlockAndRun(action);
        } else {
            action.run();
        }
    }

    private void executeConfiguredCommand() {
        String command = getQuickTileCommand(this);
        File shell = resolveShellExecutable();

        if (!command.isEmpty() && shell != null) {
            Uri scriptUri = UriUtils.getFileUri(shell.getAbsolutePath());
            Intent executeIntent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, scriptUri);
            executeIntent.setClass(this, TermuxService.class);
            executeIntent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, new String[]{"-lc", command});
            executeIntent.putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, TermuxConstants.TERMUX_HOME_DIR_PATH);
            executeIntent.putExtra(TERMUX_SERVICE.EXTRA_COMMAND_LABEL, getString(R.string.quick_settings_tile_label));
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(executeIntent);
                } else {
                    startService(executeIntent);
                }
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to start Quick Settings command", e);
            }
        }

        try {
            startActivityAndCollapse(TermuxActivity.newInstance(this));
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to open TermuxActivity from tile", e);
        }
    }

    private File resolveShellExecutable() {
        File bash = new File(TermuxConstants.TERMUX_PREFIX_DIR, "bin/bash");
        if (bash.isFile() && bash.canExecute()) return bash;
        File sh = new File(TermuxConstants.TERMUX_PREFIX_DIR, "bin/sh");
        if (sh.isFile() && sh.canExecute()) return sh;
        return null;
    }

    private void updateTileState() {
        Tile tile = getQsTile();
        if (tile == null) return;

        String command = getQuickTileCommand(this);
        tile.setLabel(getString(R.string.quick_settings_tile_label));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(command.isEmpty()
                ? getString(R.string.quick_settings_tile_default_subtitle)
                : command);
        }
        tile.setState(Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}

