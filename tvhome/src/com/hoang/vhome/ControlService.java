package com.hoang.vhome;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Control button for head-gaze navigation, placed in the taskbar row next to Back/Home/Recents.
 * It is invisible until the cursor hovers over its spot, and fades out 1.5 s after the cursor
 * leaves, so it never covers full-screen apps. The panel stays open until a click outside it or a
 * second click on the button; volume and head gaze keep it open.
 * Panel: Close app, Close all apps, Notifications, Volume - / +, Head gaze switch, Settings.
 * Settings: startup defaults, Wireless debugging, button remapping, About.
 */
public class ControlService extends AccessibilityService {
    // Taskbar look on the VITURE Neckband (measured from the stock launcher): pill #263054
    static final int PILL = 0xFF263054, PANEL = 0xF0151A2E, BUTTON = 0xFF263054, FG = 0xFFE8ECF8,
                     DIM = 0xFFA9B2CF, ACCENT = 0xFF6F8CFF, POPUP = 0xFF151A2E;
    // In the taskbar row, just left of the Back/Home/Recents pill (dp, density 1.5 on this device)
    static final int SIZE_DP = 55, RIGHT_DP = 199, BOTTOM_DP = 7, ROW_W = 300;
    static final String REPO = "https://github.com/hchuynh/Viture-Neckband-Debloater";

    private final Handler h = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private TextView fab;
    private HoverFrame fabFrame, panel, popup;
    private LinearLayout list, body, target;
    private int popupWidth = 0;
    private long closedByOutsideAt = 0;   // so a click on the button that closes the panel does not reopen it
    private Map<Integer, String> keymap;
    private boolean capturing = false;
    private final Runnable fadeOut = () -> { if (panel == null && fab != null) fab.animate().alpha(0f).setDuration(350).start(); };
    private final Runnable stopCapture = () -> { if (capturing) { capturing = false; pageButtons(); } };

    private ContentObserver observer;
    private EdgeControls edge;
    private long sleepAt = 0;   // uptime when the sleep timer ends, 0 = off
    static final int[] SLEEP_MIN = {0, 15, 30, 60, 90};
    private final Runnable sleepNow = this::sleepNow;

    // ------------------------------------------------------------------ buttons
    @Override protected boolean onKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (capturing) {
            if (!Prefs.remappable(k)) return false;
            if (e.getAction() == KeyEvent.ACTION_UP) {
                capturing = false; h.removeCallbacks(stopCapture);
                h.post(() -> pageChoose(k));
            }
            return true;
        }
        // Back closes the TV Home popup or panel first, like a dialog
        if (k == KeyEvent.KEYCODE_BACK && (popup != null || panel != null)) {
            if (e.getAction() == KeyEvent.ACTION_UP) h.post(() -> { if (popup != null) hidePopup(); else hidePanel(); });
            return true;
        }
        String a = keymap.get(k);
        if (a == null) return false;
        // with head gaze on, Viture uses the neckband volume buttons to click: leave them alone
        if ((k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN) && gazeOn()) return false;
        if (e.getAction() == KeyEvent.ACTION_UP && e.getRepeatCount() == 0) h.post(() -> perform(a));
        return true;   // consume both down and up so the app underneath does not also react
    }

    private void perform(String a) {
        switch (a) {
            case Prefs.A_PANEL: reveal(); togglePanel(); break;
            case Prefs.A_BACK: performGlobalAction(GLOBAL_ACTION_BACK); break;
            case Prefs.A_HOME: performGlobalAction(GLOBAL_ACTION_HOME); break;
            case Prefs.A_RECENTS: performGlobalAction(GLOBAL_ACTION_RECENTS); break;
            case Prefs.A_NOTIFICATIONS: performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); break;
            case Prefs.A_CLOSE_APP: closeApp(); break;
            case Prefs.A_GAZE: toggleGaze(); break;
            case Prefs.A_VOL_UP: volume(AudioManager.ADJUST_RAISE); break;
            case Prefs.A_VOL_DOWN: volume(AudioManager.ADJUST_LOWER); break;
            case Prefs.A_PLAY_PAUSE: {
                AudioManager am = getSystemService(AudioManager.class);
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
                break; }
            default: break;   // none
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        CharSequence pkg = e.getPackageName();
        // learn the debugging port whenever the system Wireless debugging page is on screen
        if (pkg != null && "com.android.settings".contentEquals(pkg) && Net.adbWifiOn(this))
            h.postDelayed(this::scanPort, 400);
        // The neckband's Back may reach the app behind instead of the panel or popup. When the app
        // behind changes screen, close them too, so they never stay on top of something else.
        if (pkg == null || getPackageName().contentEquals(pkg) || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence cls = e.getClassName();
        if (cls == null || !cls.toString().contains("Activity")) return;
        String screen = pkg + "/" + cls;
        boolean changed = !frontScreen.isEmpty() && !screen.equals(frontScreen);
        frontScreen = screen;
        // an app re-announcing the same screen (it does when our popup takes focus) is not a change
        if (changed && !fetching && SystemClock.uptimeMillis() > quietUntil && (panel != null || popup != null)) { hidePanel(); hidePopup(); }
    }

    private String frontScreen = "";
    private long quietUntil = 0;   // ignore screen changes caused by our own port reading

    /** Reads "IP address & Port" from the system page if it is on screen. */
    boolean scanPort() {
        AccessibilityNodeInfo root = topAppRoot();
        if (root == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(":");
        for (AccessibilityNodeInfo n : nodes) {
            CharSequence t = n.getText();
            if (t == null) continue;
            java.util.regex.Matcher m = Net.ADDRESS.matcher(t.toString().trim());
            if (m.matches()) { Net.port = Integer.parseInt(m.group(2)); return true; }
        }
        return false;
    }

    /** Opens the system Wireless debugging page for a moment, reads the port, goes back, then runs then. */
    private boolean fetching = false, portTried = false;

    /** Reads the port with the popup still on screen: the system page opens behind it and closes again. */
    private void readPortBehind() {
        if (fetching || popup == null) return;
        fetching = true; portTried = true;
        setPopupFocusable(false);   // Back must go to the system page, not to the popup
        fetchPort(() -> { fetching = false; quietUntil = SystemClock.uptimeMillis() + 1500; setPopupFocusable(true); if (adbLine != null) adbLine.setText(adbText()); });
    }

    private void setPopupFocusable(boolean f) {
        if (popup == null) return;
        try {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) popup.getLayoutParams();
            if (f) lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; else lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            wm.updateViewLayout(popup, lp);
        } catch (Exception ignored) { }
    }

    private void fetchPort(Runnable then) {
        String before = activePackage();
        try { startActivity(Net.pageIntent()); } catch (Exception e) { then.run(); return; }
        fetchStep(0, "com.android.settings".equals(before), then);
    }

    private String activePackage() {
        AccessibilityNodeInfo root = topAppRoot();
        return root == null || root.getPackageName() == null ? "" : root.getPackageName().toString();
    }

    /** The app window in front, ignoring our own overlays (the "active" window can be the popup). */
    private AccessibilityNodeInfo topAppRoot() {
        for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
            if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo r = w.getRoot();
            if (r != null) return r;
        }
        return getRootInActiveWindow();
    }

    private void fetchStep(int n, boolean wasInSettings, Runnable then) {
        h.postDelayed(() -> {
            if (scanPort() || n >= 8) leaveSettings(0, wasInSettings, then);
            else fetchStep(n + 1, wasInSettings, then);
        }, 500);
    }

    /**
     * Back out of the system page to the app that was in front. The page opens in its own task with
     * Developer options under it, so exactly two steps back are needed; each one waits until the
     * screen has really changed, so no extra Back ever reaches the app behind.
     */
    private void leaveSettings(int n, boolean wasInSettings, Runnable then) {
        int before = topAppWindowId();
        performGlobalAction(GLOBAL_ACTION_BACK);
        waitForChange(before, 0, () -> {
            if (n == 0 && "com.android.settings".equals(activePackage())) leaveSettings(1, wasInSettings, then);
            else then.run();
        });
    }

    private void waitForChange(int windowId, int tries, Runnable next) {
        h.postDelayed(() -> {
            if (topAppWindowId() != windowId || tries >= 12) next.run();
            else waitForChange(windowId, tries + 1, next);
        }, 120);
    }

    private int topAppWindowId() {
        for (android.view.accessibility.AccessibilityWindowInfo w : getWindows())
            if (w.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) return w.getId();
        return -1;
    }

    @Override public void onInterrupt() { }

    // ------------------------------------------------------------------ lifecycle
    @Override protected void onServiceConnected() {
        wm = getSystemService(WindowManager.class);
        keymap = Prefs.keymap(this);
        observer = new Observer(this, h);
        for (String k : new String[]{Prefs.KEYMAP, Prefs.LEGACY_MENU_KEY, Prefs.ADB_POPUP})
            getContentResolver().registerContentObserver(Settings.Global.getUriFor(k), false, observer);

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
        WindowManager.LayoutParams lp = params(dp(SIZE_DP), dp(SIZE_DP), 0);
        lp.gravity = Gravity.END | Gravity.BOTTOM;
        lp.x = dp(RIGHT_DP);
        lp.y = dp(BOTTOM_DP);
        wm.addView(fabFrame, lp);

        edge = new EdgeControls(this);
        if (Prefs.getInt(this, Prefs.EDGE, 1) == 1) edge.start();
        if (Prefs.getInt(this, Prefs.ADB_POPUP, 0) == 1) waitForAdb(0);
    }

    // ------------------------------------------------------------------ sleep timer
    private void setSleep(int minutes) {
        h.removeCallbacks(sleepNow);
        sleepAt = minutes > 0 ? SystemClock.uptimeMillis() + minutes * 60_000L : 0;
        if (sleepAt > 0) h.postAtTime(sleepNow, sleepAt);
    }

    private int sleepLeftMin() { return sleepAt == 0 ? 0 : (int) Math.max(1, (sleepAt - SystemClock.uptimeMillis() + 59_999) / 60_000); }

    /** Time is up: pause whatever plays and lock the screen, so the glasses go dark. */
    private void sleepNow() {
        sleepAt = 0;
        AudioManager am = getSystemService(AudioManager.class);
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
        hidePanel(); hidePopup(); if (edge != null) edge.hideAll();
        h.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN), 800);
    }

    private String sleepLabel() {
        return sleepAt == 0 ? "Off" : "Ends in " + sleepLeftMin() + " min";
    }

    @Override public void onDestroy() {
        try {
            if (observer != null) getContentResolver().unregisterContentObserver(observer);
            if (edge != null) edge.stop();
            h.removeCallbacks(sleepNow);
            hidePopup();
            if (panel != null) wm.removeView(panel);
            if (fabFrame != null) wm.removeView(fabFrame);
        } catch (Exception ignored) { }
        super.onDestroy();
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

    /** Focusable but not modal: receives Back while open, clicks elsewhere still reach the app. */
    private WindowManager.LayoutParams focusParams(int w, int hgt) {
        WindowManager.LayoutParams lp = params(w, hgt, WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        return lp;
    }

    private WindowManager.LayoutParams params(int w, int hgt, int extraFlags) {
        return new WindowManager.LayoutParams(w, hgt,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | extraFlags,
            PixelFormat.TRANSLUCENT);
    }

    // ------------------------------------------------------------------ panel
    private void togglePanel() { if (panel == null) showPanel(); else hidePanel(); }

    private void showPanel() {
        hidePopup();
        panel = new HoverFrame(this, inside -> { if (inside) reveal(); });
        panel.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                closedByOutsideAt = SystemClock.uptimeMillis();
                hidePanel();
                return true;
            }
            return false;
        });
        list = column(10);
        list.setBackground(round(PANEL, dp(18)));
        panel.addView(list);
        // navigating actions close the panel; volume and head gaze keep it open
        list.addView(row(Icon.CLOSE, "Close app", true, v -> closeApp()), rowLp(ROW_W));
        list.addView(row(Icon.CLOSE_ALL, "Close all apps", true, v -> closeAll()), rowLp(ROW_W));
        list.addView(row(Icon.NOTIFICATIONS, "Notifications", true, v -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)), rowLp(ROW_W));

        LinearLayout vol = new LinearLayout(this);   // split Volume - | Volume +
        vol.setOrientation(LinearLayout.HORIZONTAL);
        int half = (ROW_W - 8) / 2;
        vol.addView(row(Icon.VOLUME_DOWN, "Vol −", false, v -> volume(AudioManager.ADJUST_LOWER)), new LinearLayout.LayoutParams(dp(half), LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(dp(half), LinearLayout.LayoutParams.WRAP_CONTENT);
        right.setMarginStart(dp(8));
        vol.addView(row(Icon.VOLUME_UP, "Vol +", false, v -> volume(AudioManager.ADJUST_RAISE)), right);
        list.addView(vol, rowLp(ROW_W));

        list.addView(switchRow(Icon.GAZE_ON, "Head gaze", gazeOn(), on -> {
            Prefs.putInt(this, HeadGazeTile.KEY, on ? 1 : 0);
            return gazeOn();
        }), rowLp(ROW_W));
        if (sleepAt > 0)
            list.addView(row(Icon.TIMER, "Sleep in " + sleepLeftMin() + " min: cancel", true, v -> setSleep(0)), rowLp(ROW_W));
        list.addView(row(Icon.SETTINGS, "Settings", true, v -> pageMain()), rowLp(ROW_W));

        panel.setOnBack(this::hidePanel);
        WindowManager.LayoutParams lp = focusParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END | Gravity.BOTTOM;   // opens upward from the button, above the taskbar
        lp.x = dp(RIGHT_DP - 120);
        lp.y = dp(BOTTOM_DP + SIZE_DP + 10);
        wm.addView(panel, lp);
    }

    private void hidePanel() {
        if (panel != null) { try { wm.removeView(panel); } catch (Exception ignored) { } panel = null; }
        concealSoon();
    }

    // ------------------------------------------------------------------ settings popup
    private void openPopup(String title, boolean back) { openPopup(title, back, 480); }

    private void openPopup(String title, boolean back, int widthDp) {
        if (popup != null && popupWidth != widthDp) { try { wm.removeView(popup); } catch (Exception ignored) { } popup = null; }
        popupWidth = widthDp;
        if (popup == null) {
            popup = new HoverFrame(this, inside -> { });
            popup.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE && !capturing) { hidePopup(); return true; }
                return false;
            });
            // keep the popup clear of the status bar and the taskbar row
            ScrollView sv = new MaxScroll(this, getResources().getDisplayMetrics().heightPixels - dp(2 * (SIZE_DP + 2 * BOTTOM_DP)));
            sv.setBackground(round(POPUP, dp(18)));
            body = column(16);
            sv.addView(body);
            popup.addView(sv, new HoverFrame.LayoutParams(dp(widthDp), HoverFrame.LayoutParams.WRAP_CONTENT));
            popup.setOnBack(() -> { if (backTo != null) backTo.run(); else hidePopup(); });
            WindowManager.LayoutParams lp = focusParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER;
            lp.y = -dp(SIZE_DP / 2);   // sit slightly above centre, away from the taskbar
            wm.addView(popup, lp);
        }
        body.removeAllViews();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        if (back) head.addView(iconButton(Icon.BACK, v -> pageBack()), new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView t = text(title, 22, FG);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(back ? 10 : 6), 0, 0, 0);
        head.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (headerAbout) {
            LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(dp(48), dp(48));
            al.setMarginEnd(dp(10));
            head.addView(iconButton(Icon.INFO, v -> pageAbout()), al);
        }
        head.addView(iconButton(Icon.CLOSE, v -> hidePopup()), new LinearLayout.LayoutParams(dp(48), dp(48)));
        body.addView(head, rowLp(LinearLayout.LayoutParams.MATCH_PARENT));
        target = body;
    }

    private boolean headerAbout = false;   // the main page shows an About button next to Close
    private Runnable backTo;
    private void pageBack() { if (backTo != null) backTo.run(); }

    private void hidePopup() {
        capturing = false;
        h.removeCallbacks(stopCapture);
        h.removeCallbacks(refreshAdb);
        if (popup != null) { try { wm.removeView(popup); } catch (Exception ignored) { } popup = null; body = null; }
    }

    private TextView adbLine;
    private final Runnable refreshAdb = new AdbRefresher(this);

    void refreshAdbNow() {
        if (adbLine == null || popup == null) return;
        adbLine.setText(adbText());
        h.postDelayed(refreshAdb, 1500);
    }

    void settingsChanged() {
        keymap = Prefs.keymap(this);
        if (Prefs.getInt(this, Prefs.ADB_POPUP, 0) == 1) waitForAdb(0);
    }

    static final class AdbRefresher implements Runnable {
        private final ControlService s;
        AdbRefresher(ControlService s) { this.s = s; }
        @Override public void run() { s.refreshAdbNow(); }
    }

    static final class Observer extends ContentObserver {
        private final ControlService s;
        Observer(ControlService s, Handler handler) { super(handler); this.s = s; }
        @Override public void onChange(boolean self, Uri uri) { s.settingsChanged(); }
    }

    private void pageMain() {
        headerAbout = true;
        openPopup("TV Home settings", false, 860);
        headerAbout = false;
        backTo = null;
        // a strict grid: each column has two sections of the same height, every row is the same height
        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout left = column(0), right = column(0);
        cols.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        rp.setMarginStart(dp(16));
        cols.addView(right, rp);
        target.addView(cols);

        target = left;
        section("At startup");
        target.addView(cell(Icon.GAZE_ON, "Head gaze", "Cursor follows your head", toggle(Prefs.getInt(this, Prefs.BOOT_GAZE, 1) == 1, on -> {
            Prefs.putInt(this, Prefs.BOOT_GAZE, on ? 1 : 0); return on; })), cellLp());
        // On = Viture's default (hand gestures allowed). Off = gesture camera off now and after every restart.
        target.addView(cell(Icon.HAND, "Hand gestures", "Off keeps the camera off, saves battery", toggle(Prefs.getInt(this, BootReceiver.GESTURES_OFF_FLAG, 0) == 0, on -> {
            Prefs.putInt(this, BootReceiver.GESTURES_OFF_FLAG, on ? 0 : 1);
            Prefs.putInt(this, BootReceiver.GESTURES, on ? 1 : 0);
            return on; })), cellLp());
        section("Wireless debugging");
        LinearLayout adb = cell(Icon.DEBUG, "Wireless debugging", adbText(), toggle(Net.adbWifiOn(this), on -> {
            Net.setAdbWifi(this, on);
            portTried = false;
            if (on) h.postDelayed(this::readPortBehind, 2500);   // the port exists a moment after switching on
            return Net.adbWifiOn(this); }));
        adbLine = subtitleOf(adb);
        adbLine.setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP);   // shrink rather than cut
        target.addView(adb, cellLp());
        h.removeCallbacks(refreshAdb);
        h.post(refreshAdb);
        if (Net.adbWifiOn(this) && Net.adbPort() == 0 && !portTried) h.postDelayed(this::readPortBehind, 300);

        target = right;
        section("Controls");
        target.addView(cell(Icon.SCROLL, "Edge controls", "Scroll and media at the screen edges", toggle(Prefs.getInt(this, Prefs.EDGE, 1) == 1, on -> {
            Prefs.putInt(this, Prefs.EDGE, on ? 1 : 0);
            if (on) edge.start(); else edge.stop();
            return on; })), cellLp());
        TextView sleepValue = text(sleepLabel(), 17, ACCENT);
        LinearLayout sleep = cell(Icon.TIMER, "Sleep timer", "Pauses and locks the screen", sleepValue);
        sleep.setOnClickListener(v -> {
            int cur = 0;   // cycle Off, 15, 30, 60, 90 minutes
            for (int i = 0; i < SLEEP_MIN.length; i++) if (sleepAt > 0 && sleepLeftMin() <= SLEEP_MIN[i]) { cur = i; break; }
            int next = sleepAt == 0 ? 1 : (cur + 1) % SLEEP_MIN.length;
            setSleep(SLEEP_MIN[next]);
            sleepValue.setText(sleepLabel());
        });
        target.addView(sleep, cellLp());
        section("Buttons");
        LinearLayout buttons = cell(Icon.BUTTONS, "Remap buttons", "Neckband and controller", text("\u203a", 26, ACCENT));
        buttons.setOnClickListener(v -> pageButtons());
        target.addView(buttons, cellLp());
    }

    interface Toggler { boolean set(boolean on); }

    /** A switch for a cell; the whole cell toggles it. */
    private Toggle toggle(boolean on, Toggler t) {
        Toggle tg = new Toggle(this, on);
        tg.setTag(t);
        return tg;
    }

    /** One grid cell: icon, title and a one-line subtitle on the left, a switch or value on the right. */
    private LinearLayout cell(int icon, String title, String subtitle, View right) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(round(BUTTON, dp(12)));
        r.setPaddingRelative(dp(22), 0, dp(18), 0);
        View ic = new EdgeControls.IconView(this, icon, 28);
        r.addView(ic, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPaddingRelative(dp(22), 0, dp(10), 0);
        TextView t = text(title, 19, FG);
        t.setSingleLine(true);
        TextView sub = text(subtitle, 14, DIM);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(t); texts.addView(sub);
        r.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (right instanceof Toggle) {
            r.addView(right, new LinearLayout.LayoutParams(dp(46), dp(26)));
            final Toggle tg = (Toggle) right;
            final boolean[] state = {tg.isOn()};
            r.setOnClickListener(v -> { state[0] = ((Toggler) tg.getTag()).set(!state[0]); tg.set(state[0]); });
        } else if (right != null) r.addView(right);
        return r;
    }

    private TextView subtitleOf(LinearLayout cell) { return (TextView) ((LinearLayout) cell.getChildAt(1)).getChildAt(1); }

    private LinearLayout.LayoutParams cellLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64));
        lp.setMargins(0, dp(4), 0, dp(4));
        return lp;
    }

    private String adbText() {
        if (!Net.adbWifiOn(this)) return "Off";
        int port = Net.adbPort();
        List<String[]> ips = Net.addresses(this);
        if (ips.isEmpty()) return "Waiting for a network";
        StringBuilder sb = new StringBuilder();
        for (String[] ip : ips) {   // the port is the same on every address: show it once
            if (sb.length() > 0) sb.append("  \u00b7  ");
            sb.append(ip[1]);
        }
        if (port > 0) sb.append("  :").append(port);
        else if (fetching) sb.append("  reading port\u2026");
        return sb.toString();
    }

    private void pageButtons() {
        openPopup("Buttons", true);
        backTo = this::pageMain;
        h.removeCallbacks(refreshAdb);
        List<Integer> keys = new ArrayList<>();
        for (int k : Prefs.KNOWN_KEYS) keys.add(k);
        for (int k : keymap.keySet()) if (!keys.contains(k)) keys.add(k);
        for (int k : keys) {
            String a = keymap.get(k);
            body.addView(valueRow(Prefs.keyName(k), Prefs.actionName(a == null ? Prefs.A_DEFAULT : a), v -> pageChoose(k)),
                rowLp(LinearLayout.LayoutParams.MATCH_PARENT));
        }
        body.addView(row(Icon.BUTTONS, "Add another button…", false, v -> pageCapture()), rowLp(LinearLayout.LayoutParams.MATCH_PARENT));
        note("While head gaze is on, the neckband volume buttons keep clicking as Viture designed; "
            + "a new function for them applies with head gaze off. The power and mode buttons belong to the system and cannot be changed.");
    }

    private void pageCapture() {
        openPopup("Add a button", true);
        backTo = this::pageButtons;
        TextView t = text("Press the button on the neckband or on your controller that you want to change.", 19, FG);
        t.setPadding(dp(6), dp(18), dp(6), dp(18));
        body.addView(t);
        note("Waiting 15 seconds…");
        capturing = true;
        h.removeCallbacks(stopCapture);
        h.postDelayed(stopCapture, 15000);
    }

    private void pageChoose(int key) {
        openPopup(Prefs.keyName(key), true);
        backTo = this::pageButtons;
        String cur = keymap.get(key);
        if (cur == null) cur = Prefs.A_DEFAULT;
        for (String a : Prefs.ACTIONS) {
            if (a.equals(Prefs.A_PANEL) && key == KeyEvent.KEYCODE_UNKNOWN) continue;
            boolean sel = a.equals(cur);
            TextView r = row(sel ? Icon.CHECK : -1, Prefs.actionName(a), false, v -> {
                Map<Integer, String> m = Prefs.keymap(this);
                if (Prefs.A_DEFAULT.equals(a)) m.remove(key); else m.put(key, a);
                Prefs.saveKeymap(this, m);
                keymap = Prefs.keymap(this);
                pageButtons();
            });
            if (sel) r.setTextColor(ACCENT);
            body.addView(r, rowLp(LinearLayout.LayoutParams.MATCH_PARENT));
        }
    }

    private void pageAbout() {
        openPopup("About", true);
        backTo = this::pageMain;
        h.removeCallbacks(refreshAdb);
        String version = "";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        TextView t = text("TV Home " + version, 20, FG);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(6), dp(10), dp(6), dp(4));
        body.addView(t);
        note("Google TV as home, head gaze controls and this panel for the VITURE Neckband. "
            + "Part of Viture Neckband Debloater, an unofficial open-source project (MIT license). "
            + "Not affiliated with or endorsed by VITURE. No internet access, no data collected.");
        note(REPO.replace("https://", ""));
        body.addView(row(Icon.INFO, "Open on GitHub", false, v -> {
            hidePopup();
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(REPO)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) { }
        }), rowLp(LinearLayout.LayoutParams.MATCH_PARENT));
    }

    /** After a startup with "Wireless debugging at next startup", show the address once it is known. */
    private void waitForAdb(int n) {
        h.removeCallbacksAndMessages(ADB_TOKEN);
        if (Prefs.getInt(this, Prefs.ADB_POPUP, 0) != 1) return;
        boolean up = Net.adbWifiOn(this) && !Net.addresses(this).isEmpty();
        if (up && Net.adbPort() == 0 && n < 60) {   // read the port from the system page, then come back here
            h.postAtTime(() -> fetchPort(() -> waitForAdb(60)), ADB_TOKEN, SystemClock.uptimeMillis() + 3000);
            return;
        }
        if (up || n >= 60) {
            boolean ready = up;
            Prefs.putInt(this, Prefs.ADB_POPUP, 0);
            Prefs.putInt(this, Prefs.ADB_NEXT_BOOT, 0);
            hidePanel();
            openPopup("Wireless debugging", false);
            backTo = null;
            TextView t = text(ready ? adbText() : "Could not start it. Turn it on in Settings › System › Developer options.", 22, FG);
            t.setPadding(dp(6), dp(14), dp(6), dp(10));
            body.addView(t);
            note("Use this address with adb connect, or “./vnd.sh connect”. Remember to switch it off when you are done.");
            body.addView(row(Icon.CLOSE, "OK", false, v -> hidePopup()), rowLp(LinearLayout.LayoutParams.MATCH_PARENT));
            return;
        }
        h.postAtTime(() -> waitForAdb(n + 1), ADB_TOKEN, SystemClock.uptimeMillis() + 2000);
    }
    private static final Object ADB_TOKEN = new Object();

    // ------------------------------------------------------------------ building blocks
    interface Switch { boolean set(boolean on); }

    private LinearLayout column(int padDp) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp));
        return l;
    }

    private LinearLayout.LayoutParams rowLp(int w) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w > 0 ? dp(w) : w, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        return lp;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        return t;
    }

    private void section(String s) {
        TextView t = text(s.toUpperCase(), 14, DIM);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(6), dp(14), dp(6), dp(4));
        target.addView(t);
    }

    private TextView note(String s) {
        TextView t = text(s, 15, DIM);
        t.setPadding(dp(6), dp(4), dp(6), dp(6));
        t.setLineSpacing(0, 1.15f);
        target.addView(t);
        return t;
    }

    private void setIcon(TextView b, int icon) {
        if (icon < 0) { b.setCompoundDrawablesRelative(null, null, null, null); b.setPaddingRelative(dp(24 + 28 + 24), dp(14), dp(20), dp(14)); return; }
        Icon d = new Icon(icon, FG, BUTTON);
        d.setBounds(0, 0, dp(28), dp(28));
        b.setCompoundDrawablesRelative(d, null, null, null);
        b.setCompoundDrawablePadding(dp(24));   // gap between icon and label
    }

    private TextView row(int icon, String label, boolean closesPanel, View.OnClickListener action) {
        TextView b = text(label, 20, FG);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPaddingRelative(dp(24), dp(14), dp(20), dp(14));
        setIcon(b, icon);
        b.setBackground(round(BUTTON, dp(12)));
        b.setOnClickListener(v -> {
            if (closesPanel) { hidePanel(); h.postDelayed(() -> action.onClick(v), 150); }
            else action.onClick(v);
        });
        return b;
    }

    /** A row with a label and a switch on the right; the whole row toggles. */
    private LinearLayout switchRow(int icon, String label, boolean on, Switch sw) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(round(BUTTON, dp(12)));
        r.setPaddingRelative(0, 0, dp(18), 0);
        TextView t = text(label, 20, FG);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPaddingRelative(dp(24), dp(14), dp(12), dp(14));
        setIcon(t, icon);
        r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Toggle tg = new Toggle(this, on);
        r.addView(tg, new LinearLayout.LayoutParams(dp(46), dp(26)));
        final boolean[] state = {on};
        r.setOnClickListener(v -> { state[0] = sw.set(!state[0]); tg.set(state[0]); });
        return r;
    }

    /** One-line row: icon and label on the left, the current value on the right; click to change it. */
    private LinearLayout inlineRow(int icon, String label, String value, View.OnClickListener action) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(round(BUTTON, dp(12)));
        r.setPaddingRelative(0, 0, dp(20), 0);
        TextView t = text(label, 20, FG);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPaddingRelative(dp(24), dp(14), dp(12), dp(14));
        setIcon(t, icon);
        r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        r.addView(text(value, 17, ACCENT));
        r.setOnClickListener(action);
        return r;
    }

    /** A row with a label on the left and the current value on the right. */
    private LinearLayout valueRow(String label, String value, View.OnClickListener action) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setBackground(round(BUTTON, dp(12)));
        r.setPaddingRelative(dp(24), dp(12), dp(20), dp(12));
        r.addView(text(label, 19, FG));
        TextView v = text(value + "  ›", 16, ACCENT);
        v.setPadding(0, dp(2), 0, 0);
        r.addView(v);
        r.setOnClickListener(action);
        return r;
    }

    private TextView iconButton(int icon, View.OnClickListener action) {
        TextView b = new TextView(this);
        Icon d = new Icon(icon, FG, PANEL);
        d.setBounds(0, 0, dp(26), dp(26));
        b.setCompoundDrawablesRelative(d, null, null, null);
        b.setPadding(dp(11), dp(11), dp(11), dp(11));
        b.setBackground(round(BUTTON, 999));
        b.setOnClickListener(action);
        return b;
    }

    // ------------------------------------------------------------------ actions
    private boolean gazeOn() { return Prefs.getInt(this, HeadGazeTile.KEY, 0) == 1; }

    private void volume(int dir) {
        getSystemService(AudioManager.class).adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI);
    }

    private void toggleGaze() { Prefs.putInt(this, HeadGazeTile.KEY, gazeOn() ? 0 : 1); }

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

    // ------------------------------------------------------------------ helpers
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private static GradientDrawable round(int argb, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(argb); d.setCornerRadius(radius); return d;
    }
}
