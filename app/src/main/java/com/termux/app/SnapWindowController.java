package com.termux.app;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.R;
import com.termux.view.TerminalView;

/** Manages a draggable, resizable terminal overlay with four edge snap targets. */
public final class SnapWindowController {

    public interface Listener {
        void onOverlayClosed();
    }

    private enum Edge { LEFT, RIGHT, TOP, BOTTOM }

    private static final int COLOR_SURFACE = 0xFF111821;
    private static final int COLOR_HEADER = 0xFF1B2530;
    private static final int COLOR_BUTTON = 0xFF263543;
    private static final int COLOR_STROKE = 0xFF344451;
    private static final int COLOR_TEXT = 0xFFD9E2EC;
    private static final int COLOR_ACCENT = 0xFF77D9C6;
    private static final float TRANSPARENT_WINDOW_ALPHA = 0.78f;

    private final Activity mActivity;
    private final TerminalView mTerminalView;
    private final Listener mListener;
    private final WindowManager mWindowManager;

    private ViewGroup mOriginalParent;
    private ViewGroup.LayoutParams mOriginalLayoutParams;
    private int mOriginalIndex;
    private LinearLayout mWindowRoot;
    private FrameLayout mTerminalContainer;
    private TextView mMinimizeButton;
    private TextView mMaximizeButton;
    private TextView mTransparencyButton;
    private WindowManager.LayoutParams mWindowParams;
    private ValueAnimator mWindowAnimator;

    private boolean mAttached;
    private boolean mClosing;
    private boolean mMinimized;
    private boolean mMaximized;
    private int mExpandedWidth;
    private int mExpandedHeight;
    private int mRestoreX;
    private int mRestoreY;
    private int mRestoreWidth;
    private int mRestoreHeight;

    public SnapWindowController(Activity activity, TerminalView terminalView, Listener listener) {
        mActivity = activity;
        mTerminalView = terminalView;
        mListener = listener;
        mWindowManager = (WindowManager) activity.getSystemService(Activity.WINDOW_SERVICE);
    }

    public boolean show() {
        if (mAttached)
            return true;

        ViewParent parent = mTerminalView.getParent();
        if (!(parent instanceof ViewGroup))
            return false;

        mOriginalParent = (ViewGroup) parent;
        mOriginalIndex = mOriginalParent.indexOfChild(mTerminalView);
        mOriginalLayoutParams = mTerminalView.getLayoutParams();

        createWindow();

        mOriginalParent.removeView(mTerminalView);
        mTerminalContainer.addView(mTerminalView,
            new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Point displaySize = getDisplaySize();
        int margin = dp(12);
        int width = Math.min(dp(360), Math.max(dp(240), displaySize.x - margin * 2));
        int height = Math.min(dp(420), Math.max(dp(180), displaySize.y - margin * 2));
        mWindowParams = new WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        mWindowParams.gravity = Gravity.TOP | Gravity.LEFT;
        mWindowParams.x = Math.max(margin, (displaySize.x - width) / 3);
        mWindowParams.y = Math.max(margin, (displaySize.y - height) / 3);
        mWindowParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
            WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED;
        mExpandedWidth = width;
        mExpandedHeight = height;
        mRestoreX = mWindowParams.x;
        mRestoreY = mWindowParams.y;
        mRestoreWidth = width;
        mRestoreHeight = height;

        try {
            mWindowRoot.setAlpha(0f);
            mWindowRoot.setScaleX(0.96f);
            mWindowRoot.setScaleY(0.96f);
            mWindowManager.addView(mWindowRoot, mWindowParams);
            mAttached = true;
            mWindowRoot.post(() -> {
                if (mAttached)
                    mWindowRoot.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(180).setInterpolator(new DecelerateInterpolator(1.5f)).start();
            });
            mTerminalView.requestFocus();
            return true;
        } catch (RuntimeException exception) {
            restoreTerminalView();
            return false;
        }
    }

    public boolean isShowing() {
        return mAttached;
    }

    public void onDisplayChanged() {
        if (!mAttached)
            return;

        Point size = getDisplaySize();
        int margin = dp(8);
        if (mMaximized) {
            animateTo(margin, margin, Math.max(dp(1), size.x - margin * 2),
                Math.max(dp(48), size.y - margin * 2));
        } else {
            int width = Math.min(mWindowParams.width, Math.max(dp(1), size.x - margin * 2));
            int height = mMinimized ? dp(48) : Math.min(mWindowParams.height, Math.max(dp(48), size.y - margin * 2));
            animateTo(clamp(mWindowParams.x, 0, size.x - width),
                clamp(mWindowParams.y, 0, size.y - height), width, height);
            if (!mMinimized)
                mActivity.getWindow().getDecorView().post(this::snapToNearestEdge);
        }
    }

    public void close() {
        if (mClosing)
            return;

        if (mAttached) {
            mClosing = true;
            cancelAnimation();
            mWindowRoot.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f)
                .setDuration(140).setInterpolator(new DecelerateInterpolator(1.5f))
                .withEndAction(this::finishClose).start();
        } else {
            finishClose();
        }
    }

    public void closeImmediately() {
        mClosing = false;
        if (mWindowRoot != null)
            mWindowRoot.animate().cancel();
        cancelAnimation();
        finishClose();
    }

    private void finishClose() {
        if (mWindowAnimator != null) {
            mWindowAnimator.cancel();
            mWindowAnimator = null;
        }

        if (mAttached) {
            try {
                mWindowManager.removeViewImmediate(mWindowRoot);
            } catch (RuntimeException ignored) {
                // The window may already have been removed by Android.
            }
            mAttached = false;
        }

        restoreTerminalView();
        mClosing = false;
        if (mListener != null)
            mListener.onOverlayClosed();
    }

    private void createWindow() {
        mWindowRoot = new LinearLayout(mActivity);
        mWindowRoot.setOrientation(LinearLayout.VERTICAL);
        mWindowRoot.setBackground(rounded(COLOR_SURFACE, dp(14), COLOR_STROKE));
        mWindowRoot.setClipToOutline(true);
        mWindowRoot.setElevation(dp(12));

        LinearLayout header = new LinearLayout(mActivity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(6), 0, dp(4), 0);
        header.setBackgroundColor(COLOR_HEADER);
        mWindowRoot.addView(header, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        TextView title = new TextView(mActivity);
        title.setText(R.string.application_name);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(13);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        title.setOnTouchListener(this::handleDragTouch);

        addHeaderButton(header, "←", R.string.action_snap_left, () -> snapTo(Edge.LEFT));
        addHeaderButton(header, "→", R.string.action_snap_right, () -> snapTo(Edge.RIGHT));
        addHeaderButton(header, "↑", R.string.action_snap_top, () -> snapTo(Edge.TOP));
        addHeaderButton(header, "↓", R.string.action_snap_bottom, () -> snapTo(Edge.BOTTOM));
        mTransparencyButton = addHeaderButton(header, "◐", R.string.action_make_terminal_transparent,
            this::toggleTransparency);
        mMinimizeButton = addHeaderButton(header, "−", R.string.action_minimize_window, this::toggleMinimize);
        mMaximizeButton = addHeaderButton(header, "□", R.string.action_maximize_window, this::toggleMaximize);
        addHeaderButton(header, "×", R.string.action_close_snap_window, this::close);

        mTerminalContainer = new FrameLayout(mActivity);
        mTerminalContainer.setBackgroundColor(0xFF000000);
        mWindowRoot.addView(mTerminalContainer, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView resizeHandle = new TextView(mActivity);
        resizeHandle.setText("↘");
        resizeHandle.setTextColor(COLOR_ACCENT);
        resizeHandle.setTextSize(16);
        resizeHandle.setGravity(Gravity.CENTER);
        resizeHandle.setContentDescription(mActivity.getString(R.string.action_resize_window));
        resizeHandle.setBackground(rounded(COLOR_BUTTON, dp(8), COLOR_STROKE));
        FrameLayout.LayoutParams handleParams = new FrameLayout.LayoutParams(dp(30), dp(30),
            Gravity.BOTTOM | Gravity.RIGHT);
        handleParams.setMargins(0, 0, dp(5), dp(5));
        mTerminalContainer.addView(resizeHandle, handleParams);
        resizeHandle.setOnTouchListener(this::handleResizeTouch);
    }

    private TextView addHeaderButton(LinearLayout parent, String symbol, int description, Runnable action) {
        TextView button = new TextView(mActivity);
        button.setText(symbol);
        button.setTextColor(COLOR_TEXT);
        button.setTextSize(15);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        button.setClickable(true);
        button.setContentDescription(mActivity.getString(description));
        button.setBackground(rounded(COLOR_BUTTON, dp(8), COLOR_STROKE));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(30), dp(34));
        params.setMargins(dp(1), 0, dp(1), 0);
        parent.addView(button, params);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void toggleTransparency() {
        if (!mAttached || mWindowParams == null)
            return;

        boolean currentlyTransparent = mWindowParams.alpha < 1f;
        mWindowParams.alpha = currentlyTransparent ? 1f : TRANSPARENT_WINDOW_ALPHA;
        mTransparencyButton.setContentDescription(mActivity.getString(currentlyTransparent
            ? R.string.action_make_terminal_transparent
            : R.string.action_restore_terminal_opacity));
        updateWindowLayout();
    }

    private boolean handleDragTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelAnimation();
                mRestoreX = mWindowParams.x;
                mRestoreY = mWindowParams.y;
                mDragStartX = event.getRawX();
                mDragStartY = event.getRawY();
                return true;
            case MotionEvent.ACTION_MOVE:
                Point size = getDisplaySize();
                mWindowParams.x = clamp(mRestoreX + Math.round(event.getRawX() - mDragStartX),
                    0, size.x - mWindowParams.width);
                mWindowParams.y = clamp(mRestoreY + Math.round(event.getRawY() - mDragStartY),
                    0, size.y - mWindowParams.height);
                updateWindowLayout();
                return true;
            case MotionEvent.ACTION_UP:
                snapToNearestEdge();
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return false;
        }
    }

    private float mDragStartX;
    private float mDragStartY;

    private boolean handleResizeTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelAnimation();
                mResizeStartX = event.getRawX();
                mResizeStartY = event.getRawY();
                mResizeStartWidth = mWindowParams.width;
                mResizeStartHeight = mWindowParams.height;
                return true;
            case MotionEvent.ACTION_MOVE:
                Point size = getDisplaySize();
                int margin = dp(8);
                int maxWidth = Math.max(dp(1), size.x - mWindowParams.x - margin);
                int maxHeight = Math.max(dp(1), size.y - mWindowParams.y - margin);
                mWindowParams.width = clamp(mResizeStartWidth + Math.round(event.getRawX() - mResizeStartX),
                    Math.min(dp(280), maxWidth), maxWidth);
                mWindowParams.height = clamp(mResizeStartHeight + Math.round(event.getRawY() - mResizeStartY),
                    Math.min(dp(150), maxHeight), maxHeight);
                mExpandedWidth = mWindowParams.width;
                mExpandedHeight = mWindowParams.height;
                updateWindowLayout();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return false;
        }
    }

    private float mResizeStartX;
    private float mResizeStartY;
    private int mResizeStartWidth;
    private int mResizeStartHeight;

    private void snapToNearestEdge() {
        if (!mAttached || mMinimized)
            return;

        Point size = getDisplaySize();
        int left = Math.max(0, mWindowParams.x);
        int right = Math.max(0, size.x - mWindowParams.x - mWindowParams.width);
        int top = Math.max(0, mWindowParams.y);
        int bottom = Math.max(0, size.y - mWindowParams.y - mWindowParams.height);
        int nearest = Math.min(Math.min(left, right), Math.min(top, bottom));
        if (nearest == left)
            snapTo(Edge.LEFT);
        else if (nearest == right)
            snapTo(Edge.RIGHT);
        else if (nearest == top)
            snapTo(Edge.TOP);
        else
            snapTo(Edge.BOTTOM);
    }

    private void snapTo(Edge edge) {
        if (!mAttached)
            return;

        Point size = getDisplaySize();
        int margin = dp(8);
        int x = mWindowParams.x;
        int y = mWindowParams.y;
        switch (edge) {
            case LEFT:
                x = margin;
                y = Math.max(margin, (size.y - mWindowParams.height) / 2);
                break;
            case RIGHT:
                x = Math.max(margin, size.x - mWindowParams.width - margin);
                y = Math.max(margin, (size.y - mWindowParams.height) / 2);
                break;
            case TOP:
                x = Math.max(margin, (size.x - mWindowParams.width) / 2);
                y = margin;
                break;
            case BOTTOM:
                x = Math.max(margin, (size.x - mWindowParams.width) / 2);
                y = Math.max(margin, size.y - mWindowParams.height - margin);
                break;
        }
        animateTo(x, y, mWindowParams.width, mWindowParams.height);
    }

    private void toggleMinimize() {
        if (!mAttached)
            return;

        if (!mMinimized) {
            mExpandedWidth = mWindowParams.width;
            mExpandedHeight = mWindowParams.height;
            mTerminalContainer.setVisibility(View.GONE);
            mMinimized = true;
            mMinimizeButton.setText("+");
            mMinimizeButton.setContentDescription(mActivity.getString(R.string.action_restore_minimized_window));
            animateTo(mWindowParams.x, mWindowParams.y, mWindowParams.width, dp(48));
        } else {
            mTerminalContainer.setVisibility(View.VISIBLE);
            mMinimized = false;
            mMinimizeButton.setText("−");
            mMinimizeButton.setContentDescription(mActivity.getString(R.string.action_minimize_window));
            animateTo(mWindowParams.x, mWindowParams.y,
                mExpandedWidth, Math.max(dp(150), mExpandedHeight));
        }
    }

    private void toggleMaximize() {
        if (!mAttached)
            return;

        Point size = getDisplaySize();
        int margin = dp(8);
        if (!mMaximized) {
            mRestoreX = mWindowParams.x;
            mRestoreY = mWindowParams.y;
            mRestoreWidth = mMinimized ? mExpandedWidth : mWindowParams.width;
            mRestoreHeight = mMinimized ? mExpandedHeight : mWindowParams.height;
            mMaximized = true;
            mMinimized = false;
            mTerminalContainer.setVisibility(View.VISIBLE);
            mMaximizeButton.setText("↙");
            mMaximizeButton.setContentDescription(mActivity.getString(R.string.action_restore_window));
            animateTo(margin, margin, Math.max(dp(1), size.x - margin * 2),
                Math.max(dp(150), size.y - margin * 2));
        } else {
            mMaximized = false;
            mMaximizeButton.setText("□");
            mMaximizeButton.setContentDescription(mActivity.getString(R.string.action_maximize_window));
            animateTo(mRestoreX, mRestoreY, mRestoreWidth, mRestoreHeight);
        }
    }

    private void animateTo(int x, int y, int width, int height) {
        if (!mAttached)
            return;

        cancelAnimation();
        final int startX = mWindowParams.x;
        final int startY = mWindowParams.y;
        final int startWidth = mWindowParams.width;
        final int startHeight = mWindowParams.height;
        boolean sizeChanges = startWidth != width || startHeight != height;
        if (sizeChanges)
            mWindowRoot.setAlpha(0.94f);
        mWindowAnimator = ValueAnimator.ofFloat(0f, 1f);
        mWindowAnimator.setDuration(210);
        mWindowAnimator.setInterpolator(new DecelerateInterpolator(1.6f));
        mWindowAnimator.addUpdateListener(animator -> {
            float progress = (Float) animator.getAnimatedValue();
            mWindowParams.x = lerp(startX, x, progress);
            mWindowParams.y = lerp(startY, y, progress);
            if (progress >= 1f) {
                mWindowParams.width = width;
                mWindowParams.height = height;
                if (sizeChanges)
                    mWindowRoot.setAlpha(1f);
            }
            updateWindowLayout();
        });
        mWindowAnimator.start();
    }

    private void cancelAnimation() {
        if (mWindowAnimator != null) {
            mWindowAnimator.cancel();
            mWindowAnimator = null;
            if (mWindowRoot != null && !mClosing)
                mWindowRoot.setAlpha(1f);
        }
    }

    private void updateWindowLayout() {
        if (!mAttached)
            return;
        try {
            mWindowManager.updateViewLayout(mWindowRoot, mWindowParams);
        } catch (RuntimeException ignored) {
            // A pending close or display transition can invalidate one frame of animation.
        }
    }

    private void restoreTerminalView() {
        if (mTerminalView.getParent() instanceof ViewGroup)
            ((ViewGroup) mTerminalView.getParent()).removeView(mTerminalView);

        if (mOriginalParent != null && mTerminalView.getParent() == null) {
            int index = clamp(mOriginalIndex, 0, mOriginalParent.getChildCount());
            if (mOriginalLayoutParams != null)
                mOriginalParent.addView(mTerminalView, index, mOriginalLayoutParams);
            else
                mOriginalParent.addView(mTerminalView, index);
        }
    }

    private Point getDisplaySize() {
        Point point = new Point();
        mWindowManager.getDefaultDisplay().getRealSize(point);
        return point;
    }

    private GradientDrawable rounded(int color, int radius, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, Math.max(minimum, maximum)));
    }

    private int lerp(int start, int end, float progress) {
        return Math.round(start + (end - start) * progress);
    }
}
