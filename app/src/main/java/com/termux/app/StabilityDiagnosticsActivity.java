package com.termux.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.termux.R;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-triggered, read-only stability report screen. */
public final class StabilityDiagnosticsActivity extends AppCompatActivity {

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();

    private TextView mSummary;
    private LinearLayout mChecks;
    private Button mRunButton;
    private Button mCopyButton;
    private String mReport;
    private boolean mDestroyed;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createLayout();
        runChecks();
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        mWorker.shutdownNow();
        super.onDestroy();
    }

    private void createLayout() {
        int padding = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(resolveThemeColor(android.R.attr.colorBackground, 0xff101418));

        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle(R.string.stability_diagnostics_title);
        toolbar.setTitleTextColor(0xffffffff);
        toolbar.setBackgroundColor(0xff263c52);
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert);
        toolbar.setNavigationContentDescription(android.R.string.cancel);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(padding, dp(4), padding, dp(4));
        mRunButton = new Button(this);
        mRunButton.setText(R.string.stability_diagnostics_run);
        mRunButton.setOnClickListener(v -> runChecks());
        actions.addView(mRunButton, new LinearLayout.LayoutParams(0, dp(48), 1f));

        mCopyButton = new Button(this);
        mCopyButton.setText(R.string.stability_diagnostics_copy);
        mCopyButton.setEnabled(false);
        mCopyButton.setOnClickListener(v -> copyReport());
        actions.addView(mCopyButton, new LinearLayout.LayoutParams(0, dp(48), 1f));
        root.addView(actions);

        mSummary = new TextView(this);
        mSummary.setText(R.string.stability_diagnostics_loading);
        mSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mSummary.setTextColor(resolveThemeColor(android.R.attr.textColorPrimary, 0xff202124));
        mSummary.setPadding(padding, dp(8), padding, dp(10));
        root.addView(mSummary);

        ScrollView scrollView = new ScrollView(this);
        mChecks = new LinearLayout(this);
        mChecks.setOrientation(LinearLayout.VERTICAL);
        mChecks.setPadding(padding, 0, padding, padding);
        scrollView.addView(mChecks);
        root.addView(scrollView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView note = new TextView(this);
        note.setText(R.string.stability_diagnostics_read_only);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setTextColor(resolveThemeColor(android.R.attr.textColorSecondary, 0xff616161));
        note.setPadding(padding, dp(8), padding, padding);
        root.addView(note);
        setContentView(root);
    }

    private void runChecks() {
        mRunButton.setEnabled(false);
        mCopyButton.setEnabled(false);
        mSummary.setText(R.string.stability_diagnostics_loading);
        mChecks.removeAllViews();
        mReport = null;

        mWorker.execute(() -> {
            List<StabilityDiagnostics.Check> checks;
            try {
                checks = StabilityDiagnostics.collect(getApplicationContext());
            } catch (RuntimeException e) {
                checks = new ArrayList<>();
                checks.add(new StabilityDiagnostics.Check("Diagnostik", StabilityDiagnostics.Level.ERROR,
                    "Pemeriksaan gagal dengan " + e.getClass().getSimpleName() + "."));
            }
            String report = StabilityDiagnostics.createReport(getApplicationContext(), checks);
            List<StabilityDiagnostics.Check> result = checks;
            mHandler.post(() -> {
                if (mDestroyed) return;
                renderChecks(result, report);
            });
        });
    }

    private void renderChecks(List<StabilityDiagnostics.Check> checks, String report) {
        int issues = 0;
        int information = 0;
        for (StabilityDiagnostics.Check check : checks) {
            if (check.level == StabilityDiagnostics.Level.WARNING || check.level == StabilityDiagnostics.Level.ERROR)
                issues++;
            else if (check.level == StabilityDiagnostics.Level.INFO)
                information++;
        }

        mSummary.setText(getString(R.string.stability_diagnostics_ready, issues, information));
        mChecks.removeAllViews();
        for (StabilityDiagnostics.Check check : checks) addCheckCard(check);
        if (checks.isEmpty()) addCheckCard(new StabilityDiagnostics.Check(
            "Diagnostik", StabilityDiagnostics.Level.INFO, getString(R.string.stability_diagnostics_empty)));

        mReport = report;
        mRunButton.setEnabled(true);
        mCopyButton.setEnabled(true);
    }

    private void addCheckCard(StabilityDiagnostics.Check check) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(cardBackground());

        TextView title = new TextView(this);
        title.setText(statusLabel(check.level) + "  •  " + check.name);
        title.setTextColor(statusColor(check.level));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(title);

        TextView detail = new TextView(this);
        detail.setText(check.detail);
        detail.setTextColor(resolveThemeColor(android.R.attr.textColorPrimary, 0xff202124));
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = dp(4);
        card.addView(detail, detailParams);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dp(5);
        cardParams.bottomMargin = dp(5);
        mChecks.addView(card, cardParams);
    }

    private void copyReport() {
        if (mReport == null || mReport.isEmpty()) {
            Toast.makeText(this, R.string.stability_diagnostics_copy_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(this, R.string.stability_diagnostics_copy_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("SkyTerm diagnostics", mReport));
        Toast.makeText(this, R.string.stability_diagnostics_copied, Toast.LENGTH_SHORT).show();
    }

    private String statusLabel(StabilityDiagnostics.Level level) {
        switch (level) {
            case OK:
                return getString(R.string.stability_diagnostics_status_ok);
            case INFO:
                return getString(R.string.stability_diagnostics_status_info);
            case WARNING:
                return getString(R.string.stability_diagnostics_status_warning);
            case ERROR:
            default:
                return getString(R.string.stability_diagnostics_status_error);
        }
    }

    private int statusColor(StabilityDiagnostics.Level level) {
        switch (level) {
            case OK:
                return 0xff2e7d32;
            case INFO:
                return 0xff1565c0;
            case WARNING:
                return 0xffef6c00;
            case ERROR:
            default:
                return 0xffc62828;
        }
    }

    private GradientDrawable cardBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(resolveThemeColor(android.R.attr.colorBackgroundFloating, 0xff202830));
        drawable.setCornerRadius(dp(10));
        return drawable;
    }

    private int resolveThemeColor(int attribute, int fallback) {
        TypedValue value = new TypedValue();
        if (!getTheme().resolveAttribute(attribute, value, true)) return fallback;
        if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT)
            return value.data;
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
}
