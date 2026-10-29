package com.hoang.vhome;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/**
 * Control button for head-gaze navigation, placed in the taskbar row next to Back/Home/Recents.
 * It is invisible until the cursor hovers over its spot (or the spot is clicked), and fades out
 * 1.5 s after the cursor leaves, so it never covers full-screen apps. The panel stays open until
 * a click outside it or a second click on the button; volume and head gaze keep it open.
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
    private HoverFrame fabFrame, panel;
    private long closedByOutsideAt = 0;   // so a click on the button that closes the panel does not reopen it
    private final Runnable fadeOut = () -> { if (panel == null && fab != null) fab.animate().alpha(0f).setDuration(350).start(); };

    // Key that opens the panel from an external controller (default: Menu, e.g. CheerTouch).
    // Change it with: adb shell settings put global vnd_menu_key <keycode>   (0 = off)
    static final String MENU_KEY = "vnd_menu_key";

    @Override protected boolean onKeyEvent(KeyEvent e) {
        int key = Settings.Global.getInt(getContentResolver(), MENU_KEY, KeyEvent.KEYCODE_MENU);
        if (key <= 0 || e.getKeyCode() != key) return false;
        if (e.getAction() == KeyEvent.ACTION_UP && e.getRepeatCount() == 0) { reveal(); togglePanel(); }
        return true;   // consume both down and up so the app underneath does not also react
    }

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
        fab.setOnClickListener(v -> {
            reveal();
            // this click already closed the panel as an "outside" touch: just fade out as after any close
            if (panel == null && SystemClock.uptimeMillis() - closedByOutsideAt < 400) { concealSoon(); return; }
            togglePanel();
        });
        fab.setAlpha(0f);   // invisible until the cursor moves onto its spot
        fabFrame = new HoverFrame(this, inside -> { if (inside) reveal(); else concealSoon(); });
        fabFrame.addView(fab, new HoverFrame.LayoutParams(dp(SIZE_DP), dp(SIZE_DP)));
        WindowManager.LayoutParams lp = params(dp(SIZE_DP), dp(SIZE_DP));
        lp.gravity = Gravity.END | Gravity.BOTTOM;
        lp.x = dp(RIGHT_DP);
        lp.y = dp(BOTTOM_DP);
        wm.addView(fabFrame, lp);
    }

    private void reveal() {
        h.removeCallbacks(fadeOut);
        fab.animate().cancel();
        fab.animate().alpha(1f).setDuration(120).start();
    }

    private void concealSoon() {
        h.removeCallbacks(fadeOut);
        h.postDelayed(fadeOut, 1500);
    }

    @Override public void onDestroy() {
        try { if (panel != null) wm.removeView(panel); if (fabFrame != null) wm.removeView(fabFrame); } catch (Exception ignored) { }
        super.onDestroy();
    }

    private WindowManager.LayoutParams params(int w, int hgt) {
        return params(w, hgt, 0);
    }

    private WindowManager.LayoutParams params(int w, int hgt, int extraFlags) {
        return new WindowManager.LayoutParams(w, hgt,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | extraFlags,
            PixelFormat.TRANSLUCENT);
    }

    private void togglePanel() { if (panel == null) showPanel(); else hidePanel(); }

    private LinearLayout list;

    private void showPanel() {
        // The panel stays open until a click outside it, or a second click on the button.
        panel = new HoverFrame(this, inside -> { if (inside) reveal(); });
        panel.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                closedByOutsideAt = SystemClock.uptimeMillis();
                hidePanel();
                return true;
            }
            return false;
        });
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(10), dp(10), dp(10), dp(10));
        list.setBackground(round(PANEL, dp(18)));
        panel.addView(list);
        boolean gaze = Settings.Global.getInt(getContentResolver(), HeadGazeTile.KEY, 0) == 1;
        // navigating actions close the panel; volume and head gaze keep it open
        add(Icon.CLOSE, "Close app", true, b -> closeApp());
        add(Icon.CLOSE_ALL, "Close all apps", true, b -> closeAll());
        add(Icon.NOTIFICATIONS, "Notifications", true, b -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS));
        add(Icon.QUICK_SETTINGS, "Quick settings", true, b -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS));
        add(Icon.VOLUME_DOWN, "Volume down", false, b -> volume(AudioManager.ADJUST_LOWER));
        add(Icon.VOLUME_UP, "Volume up", false, b -> volume(AudioManager.ADJUST_RAISE));
        TextView g = add(gaze ? Icon.GAZE_ON : Icon.GAZE_OFF, gaze ? "Head gaze: on" : "Head gaze: off", false, b -> {
            toggleGaze();
            boolean on = Settings.Global.getInt(getContentResolver(), HeadGazeTile.KEY, 0) == 1;
            setLabel(b, on ? Icon.GAZE_ON : Icon.GAZE_OFF, on ? "Head gaze: on" : "Head gaze: off");
        });
        WindowManager.LayoutParams lp = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
        lp.gravity = Gravity.END | Gravity.BOTTOM;   // opens upward from the button, above the taskbar
        lp.x = dp(RIGHT_DP - 120);
        lp.y = dp(BOTTOM_DP + SIZE_DP + 10);
        wm.addView(panel, lp);
    }

    private void hidePanel() {
        if (panel != null) { try { wm.removeView(panel); } catch (Exception ignored) { } panel = null; }
        concealSoon();
    }

    interface Action { void run(TextView button); }

    private void setLabel(TextView b, int icon, String label) {
        b.setText(label);
        Icon d = new Icon(icon, FG, BUTTON);
        d.setBounds(0, 0, dp(28), dp(28));
        b.setCompoundDrawablesRelative(d, null, null, null);
        b.setCompoundDrawablePadding(dp(24));   // gap between icon and label
    }

    private TextView add(int icon, String label, boolean closes, Action action) {
        TextView b = new TextView(this);
        setLabel(b, icon, label);
        b.setTextSize(20);
        b.setTextColor(FG);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(24), dp(14), dp(28), dp(14));
        b.setBackground(round(BUTTON, dp(12)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(300), LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        b.setOnClickListener(v -> {
            if (closes) { hidePanel(); h.postDelayed(() -> action.run(b), 150); }
            else action.run(b);
        });
        list.addView(b, lp);
        return b;
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
