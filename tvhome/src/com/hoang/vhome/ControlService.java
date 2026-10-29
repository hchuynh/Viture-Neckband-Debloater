package com.hoang.vhome;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/**
 * Floating control button for head-gaze navigation. Click it to open a panel with
 * Close app, Close all, Notifications, Quick settings, Volume -/+ and Head gaze.
 * Closing works the way a finger would: open Recents and swipe the card up.
 */
public class ControlService extends AccessibilityService {
    private final Handler h = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private TextView fab;
    private LinearLayout panel;
    private final Runnable autoHide = this::hidePanel;

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }

    @Override protected void onServiceConnected() {
        wm = getSystemService(WindowManager.class);
        fab = new TextView(this);
        fab.setText("☰");
        fab.setTextSize(26);
        fab.setTextColor(Color.WHITE);
        fab.setGravity(Gravity.CENTER);
        fab.setBackground(round(0xCC1F2937, 999));
        fab.setAlpha(0.75f);
        fab.setOnClickListener(v -> togglePanel());
        WindowManager.LayoutParams lp = params(dp(64), dp(64));
        lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        lp.x = dp(12);
        wm.addView(fab, lp);
    }

    @Override public void onDestroy() {
        try { if (panel != null) wm.removeView(panel); if (fab != null) wm.removeView(fab); } catch (Exception ignored) { }
        super.onDestroy();
    }

    private WindowManager.LayoutParams params(int w, int hgt) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, hgt,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        return lp;
    }

    private void togglePanel() { if (panel == null) showPanel(); else hidePanel(); }

    private void showPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(10), dp(10), dp(10));
        panel.setBackground(round(0xEE111827, dp(18)));
        boolean gaze = Settings.Global.getInt(getContentResolver(), HeadGazeTile.KEY, 0) == 1;
        add("✕  Close app", this::closeApp);
        add("✕✕  Close all apps", this::closeAll);
        add("▾  Notifications", () -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS));
        add("⚙  Quick settings", () -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS));
        add("−  Volume down", () -> volume(AudioManager.ADJUST_LOWER));
        add("+  Volume up", () -> volume(AudioManager.ADJUST_RAISE));
        add((gaze ? "◉  Head gaze: on" : "○  Head gaze: off"), this::toggleGaze);
        WindowManager.LayoutParams lp = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        lp.x = dp(88);
        wm.addView(panel, lp);
        h.removeCallbacks(autoHide);
        h.postDelayed(autoHide, 8000);
    }

    private void hidePanel() {
        h.removeCallbacks(autoHide);
        if (panel != null) { try { wm.removeView(panel); } catch (Exception ignored) { } panel = null; }
    }

    private void add(String label, Runnable action) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(20);
        b.setTextColor(Color.WHITE);
        b.setPadding(dp(22), dp(14), dp(28), dp(14));
        b.setBackground(round(0xFF1F2937, dp(12)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(300), LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        b.setOnClickListener(v -> { hidePanel(); h.postDelayed(action, 150); });
        panel.addView(b, lp);
    }

    // --- actions ---
    private void volume(int dir) {
        getSystemService(AudioManager.class).adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI);
    }

    private void toggleGaze() {
        try {
            int on = Settings.Global.getInt(getContentResolver(), HeadGazeTile.KEY, 0);
            Settings.Global.putInt(getContentResolver(), HeadGazeTile.KEY, on == 1 ? 0 : 1);
        } catch (SecurityException ignored) { }
    }

    private void closeApp() {
        performGlobalAction(GLOBAL_ACTION_RECENTS);
        h.postDelayed(() -> swipeUp(() -> h.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_HOME), 500)), 900);
    }

    private void closeAll() {
        performGlobalAction(GLOBAL_ACTION_RECENTS);
        h.postDelayed(() -> clearStep(0), 900);
    }

    private void clearStep(int n) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            List<AccessibilityNodeInfo> clear = root.findAccessibilityNodeInfosByViewId("com.android.launcher3:id/clear_all");
            for (AccessibilityNodeInfo c : clear) {
                if (c.isVisibleToUser() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    h.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_HOME), 600);
                    return;
                }
            }
            boolean inRecents = root.getPackageName() != null && "com.android.launcher3".contentEquals(root.getPackageName());
            if (!inRecents) return;   // recents closed itself: nothing left
        }
        if (n >= 20) { performGlobalAction(GLOBAL_ACTION_HOME); return; }
        swipeUp(() -> h.postDelayed(() -> clearStep(n + 1), 450));
    }

    private void swipeUp(Runnable then) {
        DisplayMetrics m = getResources().getDisplayMetrics();
        float x = m.widthPixels / 2f, y0 = m.heightPixels * 0.48f, y1 = m.heightPixels * 0.04f;
        Path p = new Path(); p.moveTo(x, y0); p.lineTo(x, y1);
        GestureDescription g = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(p, 0, 220)).build();
        dispatchGesture(g, new Done(then), null);
    }

    static final class Done extends AccessibilityService.GestureResultCallback {
        private final Runnable then;
        Done(Runnable then) { this.then = then; }
        @Override public void onCompleted(GestureDescription d) { if (then != null) then.run(); }
        @Override public void onCancelled(GestureDescription d) { if (then != null) then.run(); }
    }

    // --- helpers ---
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private static GradientDrawable round(int argb, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(argb); d.setCornerRadius(radius); return d;
    }
}
