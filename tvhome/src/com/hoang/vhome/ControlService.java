package com.hoang.vhome;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/**
 * Control button for head-gaze navigation, placed in the taskbar row next to Back/Home/Recents.
 * Click it to open a panel with Close app, Close all apps, Notifications, Quick settings,
 * Volume down/up and Head gaze. Closing works the way a finger would: open Recents and swipe the card up.
 */
public class ControlService extends AccessibilityService {
    // Taskbar look on the VITURE Neckband (measured from the stock launcher): pill #263054
    static final int PILL = 0xFF263054, PANEL = 0xF0151A2E, BUTTON = 0xFF263054, FG = 0xFFE8ECF8;
    // In the taskbar row, just left of the Back/Home/Recents pill (dp, density 1.5 on this device)
    static final int SIZE_DP = 55, RIGHT_DP = 199, BOTTOM_DP = 7;

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
        fab.setBackground(round(PILL, 999));
        int pad = dp(14);
        Icon menu = new Icon(Icon.MENU, FG, PILL);
        menu.setBounds(0, 0, dp(SIZE_DP) - 2 * pad, dp(SIZE_DP) - 2 * pad);
        fab.setCompoundDrawablesRelative(menu, null, null, null);
        fab.setPadding(pad, pad, pad, pad);
        fab.setContentDescription("TV Home controls");
        fab.setOnClickListener(v -> togglePanel());
        WindowManager.LayoutParams lp = params(dp(SIZE_DP), dp(SIZE_DP));
        lp.gravity = Gravity.END | Gravity.BOTTOM;
        lp.x = dp(RIGHT_DP);
        lp.y = dp(BOTTOM_DP);
        wm.addView(fab, lp);
    }

    @Override public void onDestroy() {
        try { if (panel != null) wm.removeView(panel); if (fab != null) wm.removeView(fab); } catch (Exception ignored) { }
        super.onDestroy();
    }

    private WindowManager.LayoutParams params(int w, int hgt) {
        return new WindowManager.LayoutParams(w, hgt,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
    }

    private void togglePanel() { if (panel == null) showPanel(); else hidePanel(); }

    private void showPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(10), dp(10), dp(10));
        panel.setBackground(round(PANEL, dp(18)));
        boolean gaze = Settings.Global.getInt(getContentResolver(), HeadGazeTile.KEY, 0) == 1;
        add(Icon.CLOSE, "Close app", this::closeApp);
        add(Icon.CLOSE_ALL, "Close all apps", this::closeAll);
        add(Icon.NOTIFICATIONS, "Notifications", () -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS));
        add(Icon.QUICK_SETTINGS, "Quick settings", () -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS));
        add(Icon.VOLUME_DOWN, "Volume down", () -> volume(AudioManager.ADJUST_LOWER));
        add(Icon.VOLUME_UP, "Volume up", () -> volume(AudioManager.ADJUST_RAISE));
        add(gaze ? Icon.GAZE_ON : Icon.GAZE_OFF, gaze ? "Head gaze: on" : "Head gaze: off", this::toggleGaze);
        WindowManager.LayoutParams lp = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END | Gravity.BOTTOM;   // opens upward from the button, above the taskbar
        lp.x = dp(RIGHT_DP - 120);
        lp.y = dp(BOTTOM_DP + SIZE_DP + 10);
        wm.addView(panel, lp);
        h.removeCallbacks(autoHide);
        h.postDelayed(autoHide, 8000);
    }

    private void hidePanel() {
        h.removeCallbacks(autoHide);
        if (panel != null) { try { wm.removeView(panel); } catch (Exception ignored) { } panel = null; }
    }

    private void add(int icon, String label, Runnable action) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(20);
        b.setTextColor(FG);
        b.setGravity(Gravity.CENTER_VERTICAL);
        Icon d = new Icon(icon, FG, BUTTON);
        d.setBounds(0, 0, dp(26), dp(26));
        b.setCompoundDrawablesRelative(d, null, null, null);
        b.setCompoundDrawablePadding(dp(16));
        b.setPadding(dp(20), dp(14), dp(28), dp(14));
        b.setBackground(round(BUTTON, dp(12)));
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
            if (!inRecents) return;   // Recents closed itself: nothing left
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
