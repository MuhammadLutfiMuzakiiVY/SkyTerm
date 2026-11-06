package com.termux.app;

import android.app.AlertDialog;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.os.StatFs;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.termux.R;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Read-only monitor for processes descended from SkyTerm terminal sessions. */
public final class ProcessMonitorActivity extends AppCompatActivity {

    private static final String PREFERENCES_NAME = "skyterm_process_monitor";
    private static final String CPU_LIMIT_KEY = "cpu_limit_percent";
    private static final int DEFAULT_CPU_LIMIT = 80;
    private static final long REFRESH_INTERVAL_MS = 2000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mProcessReader = Executors.newSingleThreadExecutor();
    private final Map<Integer, Sample> mLastSamples = new HashMap<>();
    private final Map<Integer, Integer> mKnownSessionForProcess = new HashMap<>();
    private final Map<Integer, ProcessInfo> mRenderedProcesses = new HashMap<>();
    private long mClockTicksPerSecond = -1;
    private long mLastSystemTotalTicks = -1;
    private long mLastSystemIdleTicks = -1;
    private final Runnable mRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshProcessList();
            mHandler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    private LinearLayout mProcessList;
    private TextView mSummary;
    private TextView mResourceSummary;
    private TextView mConnectionStatus;
    private Button mCpuLimitButton;
    private TermuxService mService;
    private boolean mBound;
    private int mCpuLimitPercent = DEFAULT_CPU_LIMIT;

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mService = ((TermuxService.LocalBinder) binder).service;
            mConnectionStatus.setVisibility(View.GONE);
            refreshProcessList();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
            mConnectionStatus.setText(R.string.process_monitor_loading);
            mConnectionStatus.setVisibility(View.VISIBLE);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mCpuLimitPercent = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .getInt(CPU_LIMIT_KEY, DEFAULT_CPU_LIMIT);
        createLayout();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent serviceIntent = new Intent(this, TermuxService.class);
        try {
            startService(serviceIntent);
            mBound = bindService(serviceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            mConnectionStatus.setText(R.string.process_monitor_loading);
            mConnectionStatus.setVisibility(View.VISIBLE);
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
        mProcessReader.shutdownNow();
        super.onDestroy();
    }

    private void createLayout() {
        int padding = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(resolveThemeColor(android.R.attr.colorBackground, 0xff101418));

        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle(R.string.process_monitor_title);
        toolbar.setTitleTextColor(0xffffffff);
        toolbar.setBackgroundColor(0xff263c52);
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert);
        toolbar.setNavigationContentDescription(android.R.string.cancel);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(padding, dp(6), padding, dp(2));
        Button refreshButton = new Button(this);
        refreshButton.setText(R.string.process_monitor_refresh);
        refreshButton.setOnClickListener(v -> refreshProcessList());
        controls.addView(refreshButton, new LinearLayout.LayoutParams(0, dp(48), 1f));

        mCpuLimitButton = new Button(this);
        mCpuLimitButton.setText(getCpuLimitLabel());
        mCpuLimitButton.setContentDescription(getString(R.string.process_monitor_cpu_limit));
        mCpuLimitButton.setOnClickListener(v -> showCpuLimitDialog());
        controls.addView(mCpuLimitButton, new LinearLayout.LayoutParams(0, dp(48), 1f));
        root.addView(controls);

        mSummary = new TextView(this);
        mSummary.setTextColor(resolveThemeColor(android.R.attr.textColorPrimary, 0xff202124));
        mSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mSummary.setPadding(padding, dp(6), padding, dp(10));
        mSummary.setText(getString(R.string.process_monitor_loading));
        root.addView(mSummary);

        mResourceSummary = new TextView(this);
        mResourceSummary.setTextColor(resolveThemeColor(android.R.attr.textColorSecondary, 0xff616161));
        mResourceSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mResourceSummary.setPadding(padding, 0, padding, dp(8));
        root.addView(mResourceSummary);

        mConnectionStatus = new TextView(this);
        mConnectionStatus.setTextColor(resolveThemeColor(android.R.attr.textColorSecondary, 0xff616161));
        mConnectionStatus.setPadding(padding, dp(6), padding, dp(8));
        mConnectionStatus.setText(R.string.process_monitor_loading);
        root.addView(mConnectionStatus);

        ScrollView scrollView = new ScrollView(this);
        mProcessList = new LinearLayout(this);
        mProcessList.setOrientation(LinearLayout.VERTICAL);
        mProcessList.setPadding(padding, 0, padding, padding);
        scrollView.addView(mProcessList);
        root.addView(scrollView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView note = new TextView(this);
        note.setText(R.string.process_monitor_scope_note);
        note.setTextColor(resolveThemeColor(android.R.attr.textColorSecondary, 0xff616161));
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setPadding(padding, dp(8), padding, padding);
        root.addView(note);

        setContentView(root);
    }

    private void refreshProcessList() {
        if (mService == null || isFinishing()) return;

        final List<SessionRoot> roots = getSessionRoots(mService);
        final int cpuLimit = mCpuLimitPercent;
        mProcessReader.execute(() -> {
            Snapshot snapshot = collectProcesses(roots, cpuLimit);
            mHandler.post(() -> {
                if (!isFinishing()) renderSnapshot(snapshot);
            });
        });
    }

    private List<SessionRoot> getSessionRoots(@NonNull TermuxService service) {
        List<SessionRoot> roots = new ArrayList<>();
        List<TermuxSession> sessions = new ArrayList<>(service.getTermuxSessions());
        for (TermuxSession session : sessions) {
            TerminalSession terminalSession = session.getTerminalSession();
            ExecutionCommand command = session.getExecutionCommand();
            String label = terminalSession.mSessionName;
            if (label == null || label.trim().isEmpty()) label = command.commandLabel;
            if (label == null || label.trim().isEmpty()) label = getString(R.string.application_name);
            roots.add(new SessionRoot(terminalSession.getPid(), label,
                terminalSession.isRunning(), terminalSession.getExitStatus()));
        }
        return roots;
    }

    private Snapshot collectProcesses(List<SessionRoot> roots, int cpuLimit) {
        Map<Integer, ProcessInfo> visible = scanOwnProcesses(roots);
        Map<Integer, Integer> sessionForPid = new HashMap<>();

        for (SessionRoot root : roots) {
            if (root.pid > 0) sessionForPid.put(root.pid, root.pid);
        }
        for (Map.Entry<Integer, Integer> known : mKnownSessionForProcess.entrySet()) {
            ProcessInfo process = visible.get(known.getKey());
            Sample previous = mLastSamples.get(known.getKey());
            if (process != null && previous != null && previous.startTimeTicks == process.startTimeTicks)
                sessionForPid.put(known.getKey(), known.getValue());
        }

        boolean changed;
        int rounds = 0;
        do {
            changed = false;
            for (ProcessInfo process : visible.values()) {
                if (!sessionForPid.containsKey(process.pid) && sessionForPid.containsKey(process.parentPid)) {
                    sessionForPid.put(process.pid, sessionForPid.get(process.parentPid));
                    changed = true;
                }
            }
        } while (changed && ++rounds < visible.size());

        long now = SystemClock.elapsedRealtime();
        long ticksPerSecond = getClockTicksPerSecond();
        Map<Integer, Sample> nextSamples = new HashMap<>();
        List<ProcessInfo> managed = new ArrayList<>();
        int zombies = 0;
        int orphans = 0;
        int highCpu = 0;

        for (ProcessInfo process : visible.values()) {
            Integer sessionPid = sessionForPid.get(process.pid);
            if (sessionPid == null) continue;
            process.sessionPid = sessionPid;

            Sample previous = mLastSamples.get(process.pid);
            if (previous != null && previous.startTimeTicks == process.startTimeTicks && now > previous.timeMs) {
                long tickDelta = process.cpuTicks - previous.cpuTicks;
                long timeDelta = now - previous.timeMs;
                if (tickDelta >= 0 && ticksPerSecond > 0) {
                    process.cpuPercent = tickDelta * 100000.0 / (ticksPerSecond * timeDelta);
                    process.hasCpuSample = true;
                }
            }
            nextSamples.put(process.pid, new Sample(process.cpuTicks, process.startTimeTicks, now));

            if ("Z".equals(process.state)) zombies++;
            if (process.parentPid == 1 && process.pid != sessionPid) orphans++;
            if (process.hasCpuSample && process.cpuPercent >= cpuLimit) highCpu++;
            managed.add(process);
        }

        Collections.sort(managed, Comparator.comparingInt(process -> process.pid));
        mLastSamples.clear();
        mLastSamples.putAll(nextSamples);
        mKnownSessionForProcess.clear();
        mKnownSessionForProcess.putAll(sessionForPid);
        double deviceCpuPercent = readDeviceCpuPercent();
        ResourceInfo resources = readResourceInfo(deviceCpuPercent);
        return new Snapshot(roots, managed, zombies, orphans, highCpu, cpuLimit, resources);
    }

    private Map<Integer, ProcessInfo> scanOwnProcesses(List<SessionRoot> roots) {
        Map<Integer, ProcessInfo> processes = new HashMap<>();
        Set<Integer> rootPids = new HashSet<>();
        for (SessionRoot root : roots) if (root.pid > 0) rootPids.add(root.pid);

        File[] entries = new File("/proc").listFiles();
        if (entries == null) return processes;
        int appUid = Process.myUid();
        for (File entry : entries) {
            if (!entry.isDirectory()) continue;
            int pid;
            try {
                pid = Integer.parseInt(entry.getName());
            } catch (NumberFormatException ignored) {
                continue;
            }

            ProcessInfo process = readProcessInfo(entry, pid);
            if (process == null) continue;
            Sample previous = mLastSamples.get(pid);
            boolean previouslyTracked = mKnownSessionForProcess.containsKey(pid) && previous != null
                && previous.startTimeTicks == process.startTimeTicks;
            if (process.uid == appUid || rootPids.contains(pid) || previouslyTracked) {
                processes.put(pid, process);
            }
        }
        return processes;
    }

    @Nullable
    private ProcessInfo readProcessInfo(File processDirectory, int pid) {
        String stat = readSmallText(new File(processDirectory, "stat"), 8192);
        if (stat == null) return null;
        int commandStart = stat.indexOf('(');
        int commandEnd = stat.lastIndexOf(')');
        if (commandStart < 0 || commandEnd <= commandStart || commandEnd + 2 >= stat.length()) return null;
        String[] fields = stat.substring(commandEnd + 2).trim().split("\\s+");
        if (fields.length < 20) return null;

        try {
            String comm = stat.substring(commandStart + 1, commandEnd);
            String state = fields[0];
            int parentPid = Integer.parseInt(fields[1]);
            long cpuTicks = Long.parseLong(fields[11]) + Long.parseLong(fields[12]);
            long startTimeTicks = Long.parseLong(fields[19]);
            int uid = readUid(new File(processDirectory, "status"));
            String commandLine = readCommandLine(new File(processDirectory, "cmdline"));
            if (commandLine.isEmpty()) commandLine = comm;
            return new ProcessInfo(pid, parentPid, uid, state, cpuTicks, startTimeTicks, commandLine);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int readUid(File statusFile) {
        String status = readSmallText(statusFile, 16384);
        if (status == null) return -1;
        for (String line : status.split("\\n")) {
            if (line.startsWith("Uid:")) {
                String[] values = line.substring(4).trim().split("\\s+");
                try {
                    return Integer.parseInt(values[0]);
                } catch (Exception ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private String readCommandLine(File cmdlineFile) {
        try (FileInputStream input = new FileInputStream(cmdlineFile);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) != -1 && output.size() < 4096) {
                output.write(buffer, 0, Math.min(read, 4096 - output.size()));
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8)
                .replace('\0', ' ').trim();
        } catch (IOException ignored) {
            return "";
        }
    }

    @Nullable
    private String readSmallText(File file, int maximumBytes) {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int read;
            while ((read = input.read(buffer)) != -1 && output.size() < maximumBytes) {
                output.write(buffer, 0, Math.min(read, maximumBytes - output.size()));
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return null;
        }
    }

    private long getClockTicksPerSecond() {
        if (mClockTicksPerSecond > 0) return mClockTicksPerSecond;
        for (String fieldName : new String[]{"_SC_CLK_TCK", "SC_CLK_TCK"}) {
            try {
                Field field = android.system.OsConstants.class.getField(fieldName);
                int value = field.getInt(null);
                long ticks = android.system.Os.sysconf(value);
                if (ticks > 0) {
                    mClockTicksPerSecond = ticks;
                    return ticks;
                }
            } catch (Exception | LinkageError ignored) {
                // Fall through to Android/Linux's standard USER_HZ value.
            }
        }
        mClockTicksPerSecond = 100L;
        return mClockTicksPerSecond;
    }

    private double readDeviceCpuPercent() {
        String stat = readSmallText(new File("/proc/stat"), 4096);
        if (stat == null) return -1;
        String[] lines = stat.split("\\n", 2);
        if (lines.length == 0 || !lines[0].startsWith("cpu ")) return -1;

        String[] fields = lines[0].trim().split("\\s+");
        if (fields.length < 5) return -1;
        long total = 0;
        long idle = 0;
        try {
            for (int i = 1; i < fields.length; i++) {
                long value = Long.parseLong(fields[i]);
                total += value;
                if (i == 4 || i == 5) idle += value;
            }
        } catch (NumberFormatException e) {
            return -1;
        }

        double cpuPercent = -1;
        if (mLastSystemTotalTicks >= 0 && total > mLastSystemTotalTicks) {
            long totalDelta = total - mLastSystemTotalTicks;
            long idleDelta = Math.max(0, idle - mLastSystemIdleTicks);
            cpuPercent = Math.max(0, Math.min(100, (totalDelta - idleDelta) * 100.0 / totalDelta));
        }
        mLastSystemTotalTicks = total;
        mLastSystemIdleTicks = idle;
        return cpuPercent;
    }

    private ResourceInfo readResourceInfo(double deviceCpuPercent) {
        long totalMemory = 0;
        long availableMemory = 0;
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (activityManager != null) {
            ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(memoryInfo);
            totalMemory = memoryInfo.totalMem;
            availableMemory = memoryInfo.availMem;
        }

        long totalStorage = 0;
        long availableStorage = 0;
        try {
            StatFs statFs = new StatFs(getFilesDir().getAbsolutePath());
            totalStorage = statFs.getBlockCountLong() * statFs.getBlockSizeLong();
            availableStorage = statFs.getAvailableBlocksLong() * statFs.getBlockSizeLong();
        } catch (IllegalArgumentException ignored) {
            // Storage statistics may be unavailable while the app's data directory changes.
        }
        return new ResourceInfo(deviceCpuPercent, availableMemory, totalMemory,
            availableStorage, totalStorage);
    }

    private void renderSnapshot(Snapshot snapshot) {
        mRenderedProcesses.clear();
        for (ProcessInfo process : snapshot.processes) mRenderedProcesses.put(process.pid, process);
        int runningSessions = 0;
        for (SessionRoot root : snapshot.roots) if (root.running) runningSessions++;
        mSummary.setText(getString(R.string.process_monitor_summary,
            runningSessions, snapshot.processes.size(), snapshot.zombies, snapshot.orphans, snapshot.highCpu));
        String deviceCpu = snapshot.resources.cpuPercent >= 0
            ? String.format(Locale.getDefault(), "%.0f%%", snapshot.resources.cpuPercent)
            : getString(R.string.process_monitor_cpu_time_unknown);
        mResourceSummary.setText(getString(R.string.process_monitor_resources,
            deviceCpu, formatBytes(snapshot.resources.availableMemory), formatBytes(snapshot.resources.totalMemory),
            formatBytes(snapshot.resources.availableStorage), formatBytes(snapshot.resources.totalStorage)));
        mCpuLimitButton.setText(getCpuLimitLabel());
        mProcessList.removeAllViews();

        if (snapshot.roots.isEmpty()) {
            addMessageCard(getString(R.string.process_monitor_no_sessions));
            return;
        }

        for (SessionRoot root : snapshot.roots) {
            if (root.running && root.pid > 0) {
                addSectionHeader(getString(R.string.process_monitor_session, root.label, root.pid));
                List<ProcessInfo> sessionProcesses = new ArrayList<>();
                for (ProcessInfo process : snapshot.processes) {
                    if (process.sessionPid == root.pid) sessionProcesses.add(process);
                }
                if (sessionProcesses.isEmpty()) {
                    addProcessCard("PID " + root.pid + " • /proc tidak dapat dibaca");
                } else {
                    for (ProcessInfo process : sessionProcesses) addProcessCard(formatProcess(process, root.pid, snapshot.cpuLimit));
                }
            } else {
                addSectionHeader(getString(R.string.process_monitor_session_stopped, root.label, root.exitStatus));
            }
        }
    }

    private String formatProcess(ProcessInfo process, int sessionPid, int cpuLimit) {
        String state = process.state;
        if ("Z".equals(process.state)) state += " • " + getString(R.string.process_monitor_state_zombie);
        else if (process.parentPid == 1 && process.pid != sessionPid) state += " • " + getString(R.string.process_monitor_state_orphan);
        if (process.hasCpuSample && process.cpuPercent >= cpuLimit) state += " • " + getString(R.string.process_monitor_state_high_cpu);

        String cpu = process.hasCpuSample
            ? String.format(Locale.getDefault(), "%.1f%%", process.cpuPercent)
            : getString(R.string.process_monitor_cpu_first_sample);
        int depth = process.pid == sessionPid ? 0 : 1;
        int parent = process.parentPid;
        while (parent > 0 && parent != sessionPid && depth < 8) {
            ProcessInfo parentInfo = findProcess(parent);
            if (parentInfo == null) break;
            parent = parentInfo.parentPid;
            depth++;
        }
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < depth; i++) indent.append("  ");
        if (depth > 0) indent.append("↳ ");
        return getString(R.string.process_monitor_process, indent + process.command,
            process.pid, process.parentPid, state, cpu);
    }

    private ProcessInfo findProcess(int pid) {
        return mRenderedProcesses.get(pid);
    }

    private void addSectionHeader(String text) {
        TextView view = makeTextView(text, 15, true);
        view.setPadding(dp(12), dp(10), dp(12), dp(8));
        mProcessList.addView(view, spacedParams(LinearLayout.LayoutParams.MATCH_PARENT, -2, dp(5), dp(4)));
    }

    private void addProcessCard(String text) {
        TextView view = makeTextView(text, 13, false);
        view.setTypeface(Typeface.MONOSPACE);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setBackground(cardBackground());
        mProcessList.addView(view, spacedParams(LinearLayout.LayoutParams.MATCH_PARENT, -2, dp(5), dp(4)));
    }

    private void addMessageCard(String text) {
        TextView view = makeTextView(text, 14, false);
        view.setPadding(dp(12), dp(12), dp(12), dp(12));
        view.setBackground(cardBackground());
        mProcessList.addView(view, spacedParams(LinearLayout.LayoutParams.MATCH_PARENT, -2, dp(5), dp(4)));
    }

    private TextView makeTextView(String text, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(resolveThemeColor(android.R.attr.textColorPrimary, 0xff202124));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable cardBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(resolveThemeColor(android.R.attr.colorBackgroundFloating, 0xff202830));
        drawable.setCornerRadius(dp(10));
        return drawable;
    }

    private LinearLayout.LayoutParams spacedParams(int width, int height, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(0, top, 0, bottom);
        return params;
    }

    private void showCpuLimitDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(mCpuLimitPercent));
        input.setSelection(input.length());
        int sidePadding = dp(24);
        input.setPadding(sidePadding, dp(8), sidePadding, dp(8));
        new AlertDialog.Builder(this)
            .setTitle(R.string.process_monitor_cpu_limit)
            .setMessage(R.string.process_monitor_threshold_prompt)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                try {
                    int value = Integer.parseInt(input.getText().toString().trim());
                    if (value < 10 || value > 1000) throw new NumberFormatException();
                    mCpuLimitPercent = value;
                    getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE).edit()
                        .putInt(CPU_LIMIT_KEY, value).apply();
                    mCpuLimitButton.setText(getCpuLimitLabel());
                    refreshProcessList();
                } catch (NumberFormatException e) {
                    Toast.makeText(this, R.string.process_monitor_threshold_invalid, Toast.LENGTH_SHORT).show();
                }
            })
            .show();
    }

    private String getCpuLimitLabel() {
        return getString(R.string.process_monitor_cpu_limit) + ": " + mCpuLimitPercent + "%";
    }

    private String formatBytes(long bytes) {
        if (bytes <= 0) return "—";
        double mebibytes = bytes / (1024.0 * 1024.0);
        if (mebibytes >= 1024.0) return String.format(Locale.getDefault(), "%.1f GiB", mebibytes / 1024.0);
        return String.format(Locale.getDefault(), "%.0f MiB", mebibytes);
    }

    private int resolveThemeColor(int attribute, int fallback) {
        TypedValue value = new TypedValue();
        if (!getTheme().resolveAttribute(attribute, value, true)) return fallback;
        if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return value.data;
        }
        if (value.resourceId != 0) {
            try {
                return getResources().getColor(value.resourceId);
            } catch (Exception ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class SessionRoot {
        final int pid;
        final String label;
        final boolean running;
        final int exitStatus;

        SessionRoot(int pid, String label, boolean running, int exitStatus) {
            this.pid = pid;
            this.label = label;
            this.running = running;
            this.exitStatus = exitStatus;
        }
    }

    private static final class ProcessInfo {
        final int pid;
        final int parentPid;
        final int uid;
        final String state;
        final long cpuTicks;
        final long startTimeTicks;
        final String command;
        int sessionPid;
        double cpuPercent;
        boolean hasCpuSample;

        ProcessInfo(int pid, int parentPid, int uid, String state, long cpuTicks,
                    long startTimeTicks, String command) {
            this.pid = pid;
            this.parentPid = parentPid;
            this.uid = uid;
            this.state = state;
            this.cpuTicks = cpuTicks;
            this.startTimeTicks = startTimeTicks;
            this.command = command;
        }
    }

    private static final class Sample {
        final long cpuTicks;
        final long startTimeTicks;
        final long timeMs;

        Sample(long cpuTicks, long startTimeTicks, long timeMs) {
            this.cpuTicks = cpuTicks;
            this.startTimeTicks = startTimeTicks;
            this.timeMs = timeMs;
        }
    }

    private static final class Snapshot {
        final List<SessionRoot> roots;
        final List<ProcessInfo> processes;
        final int zombies;
        final int orphans;
        final int highCpu;
        final int cpuLimit;
        final ResourceInfo resources;

        Snapshot(List<SessionRoot> roots, List<ProcessInfo> processes, int zombies,
                 int orphans, int highCpu, int cpuLimit, ResourceInfo resources) {
            this.roots = roots;
            this.processes = processes;
            this.zombies = zombies;
            this.orphans = orphans;
            this.highCpu = highCpu;
            this.cpuLimit = cpuLimit;
            this.resources = resources;
        }
    }

    private static final class ResourceInfo {
        final double cpuPercent;
        final long availableMemory;
        final long totalMemory;
        final long availableStorage;
        final long totalStorage;

        ResourceInfo(double cpuPercent, long availableMemory, long totalMemory,
                     long availableStorage, long totalStorage) {
            this.cpuPercent = cpuPercent;
            this.availableMemory = availableMemory;
            this.totalMemory = totalMemory;
            this.availableStorage = availableStorage;
            this.totalStorage = totalStorage;
        }
    }
}
