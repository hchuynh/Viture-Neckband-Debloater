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
    private CursorHider hider;
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
        // watch mode switched head gaze off for a video: a neckband volume button brings it back
        if ((k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN) && autoGaze != null && autoGaze.holdsGaze()) {
            if (e.getAction() == KeyEvent.ACTION_UP) h.post(() -> autoGaze.volumePressed());
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
        if (autoGaze != null && e.getEventType() == AccessibilityEvent.TYPE_VIEW_HOVER_ENTER) autoGaze.activity();
        if (hider != null && (e.getEventType() == AccessibilityEvent.TYPE_VIEW_HOVER_ENTER
                || e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) hider.activity();
        if (e.getEventType() == AccessibilityEvent.TYPE_VIEW_HOVER_ENTER) return;
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
        fabFrame = new HoverFrame(this, inside -> { if (hider != null) hider.activity(); if (inside) reveal(); else concealSoon(); });
        fabFrame.addView(fab, new HoverFrame.LayoutParams(dp(SIZE_DP), dp(SIZE_DP)));
        WindowManager.LayoutParams lp = params(dp(SIZE_DP), dp(SIZE_DP), 0);
        lp.gravity = Gravity.END | Gravity.BOTTOM;
        lp.x = dp(RIGHT_DP);
        lp.y = dp(BOTTOM_DP);
        wm.addView(fabFrame, lp);

        edge = new EdgeControls(this);
        hider = new CursorHider(this);
        edge.setOpenListener(open -> hider.setPaused(open || panel != null || popup != null));
        setHideCursor(Prefs.getInt(this, Prefs.HIDE_CURSOR, 1) == 1);
        if (Prefs.getInt(this, Prefs.EDGE, 1) == 1) edge.start();
        if (Prefs.getInt(this, Prefs.ADB_POPUP, 0) == 1) waitForAdb(0);
        updater = new Updater(this);
        if (Prefs.getInt(this, Prefs.ONBOARDED, 0) != 1) h.postDelayed(() -> { if (popup == null) pageTour(0); }, 4000);
        h.postDelayed(updateTick, 60_000);
        autoGaze = new AutoGaze(this);
        refreshAutoGaze();
    }

    // ------------------------------------------------------------------ profiles
    private AutoGaze autoGaze;

    private void refreshAutoGaze() {
        int b = Profiles.bits(this, Profiles.current(this));
        if (autoGaze != null) autoGaze.configure(Profiles.has(b, Profiles.WATCH), Profiles.has(b, Profiles.PERIPHERAL));
        if (hider != null) setHideCursor(Prefs.getInt(this, Prefs.HIDE_CURSOR, 1) == 1);   // hover reports also feed watch mode
    }

    private void activate(int profile) {
        Profiles.apply(this, profile);
        refreshAutoGaze();
    }

    /** One small icon per switch of the profile; a slashed, dimmed icon when it is off. */
    private LinearLayout iconStrip(int bits, int sizeDp) {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < Profiles.ITEMS.length; i++) {
            boolean on = Profiles.has(bits, Profiles.ITEMS[i]);
            View v = new EdgeControls.IconView(this, Profiles.ICONS[i], sizeDp, on ? FG : DIM, POPUP, !on);
            if (!on) v.setAlpha(0.6f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sizeDp + 4), dp(sizeDp + 4));
            lp.setMarginEnd(dp(6));
            strip.addView(v, lp);
        }
        return strip;
    }

    /** A profile line: name and its icons, a check on the active one. */
    private LinearLayout profileRow(int p, int active, View.OnClickListener action, View right) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(round(BUTTON, dp(12)));
        r.setPaddingRelative(dp(22), dp(10), dp(18), dp(10));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(Profiles.NAMES[p], 19, p == active ? ACCENT : FG);
        texts.addView(t);
        TextView d = text(Profiles.DESCRIPTIONS[p], 14, DIM);
        texts.addView(d);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sl.topMargin = dp(6);
        texts.addView(iconStrip(Profiles.bits(this, p), 18), sl);
        r.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (right != null) r.addView(right);
        else if (p == active) r.addView(new EdgeControls.IconView(this, Icon.CHECK, 26, ACCENT, BUTTON, false), new LinearLayout.LayoutParams(dp(32), dp(32)));
        r.setOnClickListener(action);
        return r;
    }

    private void pageProfiles() {
        openPopup("Profile", false, 640);
        backTo = null;
        h.removeCallbacks(refreshAdb);
        int active = Profiles.current(this);
        for (int p = 0; p < Profiles.CUSTOM; p++) {
            final int fp = p;
            body.addView(profileRow(p, active, v -> { activate(fp); pageProfiles(); }, null), rowLp(LinearLayout.LayoutParams.MATCH_PARENT));
        }
        TextView edit = text(active == Profiles.CUSTOM ? "\u2713  Edit \u203a" : "Edit \u203a", 17, ACCENT);
        edit.setPadding(dp(12), dp(8), dp(4), dp(8));
        edit.setOnClickListener(v -> pageCustom());
        body.addView(profileRow(Profiles.CUSTOM, active, v -> { activate(Profiles.CUSTOM); pageProfiles(); }, edit),
            rowLp(LinearLayout.LayoutParams.MATCH_PARENT));
        note("Choosing a profile sets head gaze and hand gestures now and at every startup, and switches Wi-Fi and Bluetooth once.");
    }

    private int customDraft;

    private void pageCustom() {
        openPopup("Custom profile", true, 640);
        backTo = this::pageProfiles;
        customDraft = Profiles.bits(this, Profiles.CUSTOM);
        for (int i = 0; i < Profiles.ITEMS.length; i++) {
            final int item = Profiles.ITEMS[i];
            body.addView(cell(Profiles.ICONS[i], Profiles.ITEM_NAMES[i], Profiles.ITEM_SUBS[i],
                toggle(Profiles.has(customDraft, item), on -> {
                    customDraft = on ? customDraft | item : customDraft & ~item;
                    return on; })), cellLp());
        }
        TextView save = row(Icon.CHECK, "Save and activate", false, v -> {
            Profiles.saveCustom(this, customDraft);
            activate(Profiles.CUSTOM);
            pageProfiles();
        });
        save.setTextColor(ACCENT);
        body.addView(save, rowLp(LinearLayout.LayoutParams.MATCH_PARENT));
    }

    /** Header of the panel: Wi-Fi and Bluetooth switches and the battery estimate. */
    private LinearLayout panelHeader() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));
        bar.setBackground(round(0xFF1C2340, 999));
        bar.addView(radioButton(Icon.WIFI, Radio.wifiOn(this), on -> { Radio.setWifi(this, on); return on; }),
            new LinearLayout.LayoutParams(dp(64), dp(44)));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(64), dp(44));
        bl.setMarginStart(dp(4));
        bar.addView(radioButton(Icon.BLUETOOTH, Radio.bluetoothOn(), on -> { Radio.setBluetooth(on); return on; }), bl);
        LinearLayout batt = new LinearLayout(this);
        batt.setOrientation(LinearLayout.HORIZONTAL);
        batt.setGravity(Gravity.CENTER);
        batt.addView(new EdgeControls.IconView(this, Icon.BATTERY, 20, DIM, 0xFF1C2340, false), new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView bt = text(" " + Radio.battery(this), 14, FG);
        bt.setSingleLine(true);
        batt.addView(bt);
        bar.addView(batt, new LinearLayout.LayoutParams(0, dp(44), 1));
        return bar;
    }

    /** A pill-shaped switch with an icon: filled with the accent colour when on. */
    private View radioButton(int icon, boolean on, Toggler t) {
        final boolean[] state = {on};
        final EdgeControls.IconView[] iv = new EdgeControls.IconView[1];
        android.widget.FrameLayout f = new android.widget.FrameLayout(this);
        Runnable paint = () -> {
            f.removeAllViews();
            iv[0] = new EdgeControls.IconView(this, icon, 22, state[0] ? 0xFF0F1426 : FG, state[0] ? ACCENT : 0xFF1C2340, !state[0]);
            f.addView(iv[0]);
            f.setBackground(state[0] ? round(ACCENT, 999) : null);
        };
        paint.run();
        f.setOnClickListener(v -> { state[0] = t.set(!state[0]); paint.run(); });
        return f;
    }

    // ------------------------------------------------------------------ updates
    private Updater updater;
    private boolean installing = false;
    private final Runnable updateTick = this::updateTick;

    /** Every few hours: if automatic checks are on and a day has passed, ask GitHub once. */
    private void updateTick() {
        h.removeCallbacks(updateTick);
        h.postDelayed(updateTick, 6 * 3600_000L);
        if (!updater.autoCheck() || !updater.dueForCheck()) return;
        updater.check((latest, apk, error) -> {
            if (error == null && updater.shouldPrompt() && popup == null && panel == null) pageUpdate();
        });
    }

    /** Offers the new version: Update, Not now, or Skip this version (never asked again for it). */
    /** Google Play Protect scans every install; it blocks an app with an accessibility service from updating itself. */
    private boolean playProtectOn() {
        return Settings.Global.getInt(getContentResolver(), "package_verifier_user_consent", 1) == 1;
    }

    /**
     * Offers the new version. Download from GitHub always works; installing inside TV Home works only while
     * Play Protect is off, because Play Protect blocks accessibility apps that install their own updates.
     */
    private void pageUpdate() {
        openPopup("Update available", false, 600);
        backTo = null;
        h.removeCallbacks(refreshAdb);
        TextView t = text("TV Home " + updater.latestKnown() + " is available. You have " + Updater.installed(this) + ".", 19, FG);
        t.setPadding(dp(6), dp(10), dp(6), dp(6));
        body.addView(t);
        boolean pp = playProtectOn();
        TextView status = note(pp
            ? "Google Play Protect is on, so TV Home cannot install the update itself: Play Protect blocks apps with "
              + "an accessibility service from updating themselves. Download it from GitHub and open the file, or run "
              + "./vnd.sh tvhome install from a computer. To update here instead, switch off \u201cScan apps with Play Protect\u201d "
              + "in the Play Store (profile \u203a Play Protect \u203a settings) at your own risk, and switch it back on afterwards."
            : "Android asks you to confirm the update and accepts it only if it is signed by the same developer.");
        body.addView(row(Icon.DOWN, "Download from GitHub", false, v -> {
            hidePopup();
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(REPO + "/releases/tag/v" + updater.latestKnown()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) { }
        }), rowLp(LinearLayout.LayoutParams.MATCH_PARENT));
        TextView install = row(Icon.CHECK, pp ? "Install here (needs Play Protect off)" : "Install here", false, v -> {
            if (playProtectOn()) {
                status.setText("Play Protect is still on: it would block the update. Use Download from GitHub, "
                    + "or switch Play Protect scanning off first.");
                return;
            }
            if (installing) return;
            installing = true;
            updater.install(updater.apkKnown(), (m, failed) -> {
                status.setText(m);
                if (failed) installing = false;
                else if (m.startsWith("Waiting")) h.postDelayed(() -> { installing = false; hidePopup(); }, 1500);
            });
        });
        if (pp) install.setAlpha(0.55f);
        body.addView(install, rowLp(LinearLayout.LayoutParams.MATCH_PARENT));
        body.addView(row(Icon.CLOSE, "Not now", false, v -> hidePopup()), rowLp(LinearLayout.LayoutParams.MATCH_PARENT));
        body.addView(row(Icon.NEXT, "Skip this version", false, v -> { updater.skip(updater.latestKnown()); hidePopup(); }),
            rowLp(LinearLayout.LayoutParams.MATCH_PARENT));
    }

    // ------------------------------------------------------------------ welcome tour
    private static final String[][] TOUR = {
        {"Welcome to TV Home", "TV Home makes Google TV your home on the VITURE Neckband and adds a few handy controls. "
            + "This short tour shows the main ones.\n\nTo move through it, turn your head to move the pointer onto Next, "
            + "then click by pressing either volume button on the neckband (with head gaze on, they act as click buttons)."},
        {"The controls button", "Move the cursor to the bottom-right corner, next to the taskbar: a \u2630 button appears. "
            + "It opens Close app, Close all apps, Notifications, volume, head gaze and Settings. The Menu key on a controller opens it too."},
        {"Edge controls", "Rest the cursor on the left or right edge of the screen for a moment and a scrollbar appears. "
            + "While something is playing, a media bar with play, pause and 10-second skips appears in the middle, "
            + "with buttons above and below it to jump to the previous or next clip."},
        {"A quiet cursor", "The cursor hides after 5 seconds without moving. Turn your head or click and it comes back. "
            + "A long press on the neckband mode button recenters the view."},
        {"Settings", "Settings is the last row of the \u2630 panel. It chooses what the Home button opens (Google TV, Android home or Viture 3D), "
            + "what starts with the glasses, the sleep timer and button remapping."},
        {"Updates", "TV Home can check GitHub once a day for a new version and tell you when one is out. "
            + "Only the public release page is read: nothing about you or your device is sent. You can change this later in About. "
            + "With Google Play Protect on, updates are downloaded from GitHub rather than installed by TV Home."},
    };

    private TourPointer pointer;

    /** Arrows towards what each tour page talks about; the hidden ☰ button is shown while pointed at. */
    private void pointFor(int i) {
        if (pointer == null) pointer = new TourPointer(this);
        pointer.clear();
        if (i == 1 || i == 4) {   // the ☰ button, bottom right next to the taskbar
            reveal();
            pointer.show(TourPointer.DOWN, Gravity.END | Gravity.BOTTOM, RIGHT_DP + SIZE_DP / 2 - 42, BOTTOM_DP + SIZE_DP + 2);
        } else if (i == 2) {      // both screen edges
            pointer.show(TourPointer.LEFT, Gravity.START | Gravity.CENTER_VERTICAL, 4, 0);
            pointer.show(TourPointer.RIGHT, Gravity.END | Gravity.CENTER_VERTICAL, 4, 0);
        }
    }

    private void pageTour(int i) {
        Prefs.putInt(this, Prefs.ONBOARDED, 1);   // shown once; About offers it again
        hidePanel();
        openPopup(TOUR[i][0], i > 0, 640);
        pointFor(i);
        backTo = i > 0 ? () -> pageTour(i - 1) : null;
        h.removeCallbacks(refreshAdb);
        TextView step = text((i + 1) + " of " + TOUR.length, 13, DIM);
        step.setPadding(dp(6), dp(2), 0, dp(4));
        body.addView(step);
        TextView t = text(TOUR[i][1], 18, FG);
        t.setLineSpacing(0, 1.2f);
        t.setPadding(dp(6), dp(8), dp(6), dp(14));
        body.addView(t);
        if (i == TOUR.length - 1) {
            body.addView(cell(Icon.DOWN, "Check for updates automatically", "Once a day, from GitHub",
                toggle(updater.autoCheck(), on -> { Prefs.putInt(this, Prefs.UPDATE_CHECK, on ? 1 : 0); return on; })), cellLp());
            body.addView(row(Icon.CHECK, "Done", false, v -> { hidePopup(); h.postDelayed(updateTick, 2000); }),
                rowLp(LinearLayout.LayoutParams.MATCH_PARENT));
            return;
        }
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        TextView skip = row(Icon.CLOSE, "Skip tour", false, v -> pageTour(TOUR.length - 1));
        nav.addView(skip, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        np.setMarginStart(dp(12));
        TextView next = row(Icon.NEXT, "Next", false, v -> pageTour(i + 1));
        next.setTextColor(ACCENT);
        nav.addView(next, np);
        body.addView(nav, rowLp(LinearLayout.LayoutParams.MATCH_PARENT));
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

    /** Hover reports are only requested while "Hide cursor when still" is on: no cost otherwise. */
    private void setHideCursor(boolean on) {
        android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            if (on || (autoGaze != null && autoGaze.wantsHover())) info.eventTypes |= AccessibilityEvent.TYPE_VIEW_HOVER_ENTER;
            else info.eventTypes &= ~AccessibilityEvent.TYPE_VIEW_HOVER_ENTER;
            setServiceInfo(info);
        }
        hider.setEnabled(on);
        updatePause();
    }

    /** Never hide the cursor while the panel, a popup or the edge bars are open. */
    private void updatePause() {
        if (hider != null) hider.setPaused(panel != null || popup != null || (edge != null && edge.isOpen()));
    }

    @Override public void onDestroy() {
        try {
            if (observer != null) getContentResolver().unregisterContentObserver(observer);
            if (edge != null) edge.stop();
            if (autoGaze != null) autoGaze.stop();
            if (hider != null) hider.setEnabled(false);
            h.removeCallbacks(sleepNow);
            h.removeCallbacks(updateTick);
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
        list.addView(panelHeader(), rowLp(ROW_W));
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
        list.addView(profileEntry(), rowLp(ROW_W));

        panel.setOnBack(this::hidePanel);
        WindowManager.LayoutParams lp = focusParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END | Gravity.BOTTOM;   // opens upward from the button, above the taskbar
        lp.x = dp(RIGHT_DP - 120);
        lp.y = dp(BOTTOM_DP + SIZE_DP + 10);
        wm.addView(panel, lp);
        updatePause();
    }

    /** Last panel row: the active profile and its icons; opens the profile list. */
    private View profileEntry() {
        int p = Profiles.current(this);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(round(BUTTON, dp(12)));
        r.setPaddingRelative(dp(24), dp(10), dp(16), dp(10));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text("Profile: " + Profiles.NAMES[p], 20, FG));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sl.topMargin = dp(4);
        texts.addView(iconStrip(Profiles.bits(this, p), 16), sl);
        r.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        r.addView(text("\u203a", 28, ACCENT));   // chevron: opens a sub menu
        r.setOnClickListener(v -> { hidePanel(); h.postDelayed(this::pageProfiles, 150); });
        return r;
    }

    private void hidePanel() {
        if (panel != null) { try { wm.removeView(panel); } catch (Exception ignored) { } panel = null; }
        concealSoon();
        updatePause();
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
            updatePause();
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
            // shows or hides TV Home's own Wireless debugging row; Android's Developer options are untouched
            boolean dev = Prefs.getInt(this, Prefs.DEV_MODE, 0) == 1;
            TextView devBtn = iconButton(Icon.DEBUG, v -> { Prefs.putInt(this, Prefs.DEV_MODE, dev ? 0 : 1); pageMain(); });
            if (dev) {
                devBtn.setBackground(round(ACCENT, 999));
                Icon d = new Icon(Icon.DEBUG, 0xFF0F1426, ACCENT);
                d.setBounds(0, 0, dp(26), dp(26));
                devBtn.setCompoundDrawablesRelative(d, null, null, null);
            }
            devBtn.setContentDescription(dev ? "Hide Wireless debugging" : "Show Wireless debugging");   // TV Home only, not Android's Developer options
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(48), dp(48));
            dl.setMarginEnd(dp(10));
            head.addView(devBtn, dl);
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
        if (pointer != null) pointer.clear();
        capturing = false;
        h.removeCallbacks(stopCapture);
        h.removeCallbacks(refreshAdb);
        if (popup != null) { try { wm.removeView(popup); } catch (Exception ignored) { } popup = null; body = null; }
        updatePause();
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
        // first, full width: which home the Home button opens
        LinearLayout home = cell(Icon.HOME, "Home button opens", null, segmented(
            homeOptions(), Prefs.getInt(this, Prefs.HOME_MODE, 0),
            i -> Prefs.putInt(this, Prefs.HOME_MODE, i)));
        target.addView(home, cellLp());
        target.addView(cols);

        target = left;
        section("At startup");
        target.addView(cell(Icon.GAZE_ON, "Head gaze", "Cursor follows your head", toggle(Prefs.getInt(this, Prefs.BOOT_GAZE, 1) == 1, on -> {
            Prefs.putInt(this, Prefs.BOOT_GAZE, on ? 1 : 0); Profiles.startupChanged(this); return on; })), cellLp());
        // On = Viture's default (hand gestures allowed). Off = gesture camera off now and after every restart.
        target.addView(cell(Icon.HAND, "Hand gestures", "Off keeps the camera off, saves battery", toggle(Prefs.getInt(this, BootReceiver.GESTURES_OFF_FLAG, 0) == 0, on -> {
            Prefs.putInt(this, BootReceiver.GESTURES_OFF_FLAG, on ? 0 : 1);
            Prefs.putInt(this, BootReceiver.GESTURES, on ? 1 : 0);
            Profiles.startupChanged(this);
            return on; })), cellLp());
        section("Cursor");
        target.addView(cell(Icon.GAZE_OFF, "Hide when still", "After 5 s; moves or clicks bring it back", toggle(Prefs.getInt(this, Prefs.HIDE_CURSOR, 1) == 1, on -> {
            Prefs.putInt(this, Prefs.HIDE_CURSOR, on ? 1 : 0);
            setHideCursor(on);
            return on; })), cellLp());

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

        // full width under both columns, only in developer mode (the </> button in the header)
        h.removeCallbacks(refreshAdb);
        adbLine = null;
        if (Prefs.getInt(this, Prefs.DEV_MODE, 0) != 1) { footer(); return; }
        target = body;
        section("Wireless debugging");
        LinearLayout adb = cell(Icon.DEBUG, "Wireless debugging", adbText(), toggle(Net.adbWifiOn(this), on -> {
            Net.setAdbWifi(this, on);
            portTried = false;
            if (on) h.postDelayed(this::readPortBehind, 2500);   // the port exists a moment after switching on
            return Net.adbWifiOn(this); }));
        adbLine = subtitleOf(adb);
        target.addView(adb, cellLp());
        h.removeCallbacks(refreshAdb);
        h.post(refreshAdb);
        if (Net.adbWifiOn(this) && Net.adbPort() == 0 && !portTried) h.postDelayed(this::readPortBehind, 300);
        footer();
    }

    /** Small version label at the bottom of the settings popup. */
    private void footer() {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.HORIZONTAL);
        f.setGravity(Gravity.CENTER_VERTICAL);
        f.setPadding(dp(6), dp(10), dp(6), 0);
        TextView t = text("TV Home " + Updater.installed(this), 10, DIM);
        t.setAlpha(0.7f);
        f.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (updater != null && updater.updateAvailable()) {   // on the right: a newer version is known
            TextView u = text("Update available: " + updater.latestKnown() + "  \u203a", 13, ACCENT);
            u.setOnClickListener(v -> pageUpdate());
            f.addView(u);
        }
        body.addView(f, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
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
        texts.addView(t);
        if (subtitle != null) {
            TextView sub = text(subtitle, 14, DIM);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(sub);
        }
        r.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (right instanceof Toggle) {
            r.addView(right, new LinearLayout.LayoutParams(dp(46), dp(26)));
            final Toggle tg = (Toggle) right;
            final boolean[] state = {tg.isOn()};
            r.setOnClickListener(v -> { state[0] = ((Toggler) tg.getTag()).set(!state[0]); tg.set(state[0]); });
        } else if (right != null) r.addView(right);
        return r;
    }

    interface Choice { void chosen(int index); }

    /** Viture 3D is offered only if its home is installed and enabled (debloated devices may lack it). */
    private String[] homeOptions() {
        try {
            if (getPackageManager().getApplicationInfo(HomeActivity.VITURE, 0).enabled)
                return new String[]{"Google TV", "Android home", "Viture 3D"};
        } catch (Exception ignored) { }
        return new String[]{"Google TV", "Android home"};
    }

    /** A two-or-more option bar; the selected option is filled with the accent colour. */
    private LinearLayout segmented(String[] options, int selected, Choice choice) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));
        bar.setBackground(round(0xFF1C2340, 999));
        final TextView[] items = new TextView[options.length];
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            TextView t = text(options[i], 16, FG);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(18), dp(8), dp(18), dp(8));
            t.setOnClickListener(v -> {
                for (int k = 0; k < items.length; k++) {
                    items[k].setBackground(k == idx ? round(ACCENT, 999) : null);
                    items[k].setTextColor(k == idx ? 0xFF0F1426 : FG);
                }
                choice.chosen(idx);
            });
            t.setBackground(i == selected ? round(ACCENT, 999) : null);
            t.setTextColor(i == selected ? 0xFF0F1426 : FG);
            items[i] = t;
            bar.addView(t);
        }
        return bar;
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
        openPopup("About", true, 640);
        backTo = this::pageMain;
        h.removeCallbacks(refreshAdb);
        TextView t = text("TV Home " + Updater.installed(this), 20, FG);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(6), dp(6), dp(6), dp(6));
        body.addView(t);
        String me = "Hi, I'm Hoang \u201cAetheros\u201d Huynh. I'm a technonerd and an enterprise value proposition strategist "
            + "and I vibecoded this TV Home with \u2665 for you.";
        android.text.SpannableString sp = new android.text.SpannableString(me);
        int heart = me.indexOf('\u2665');
        sp.setSpan(new android.text.style.ForegroundColorSpan(0xFFFF6F91), heart, heart + 1, 0);
        TextView hi = text("", 17, FG);
        hi.setText(sp);
        hi.setLineSpacing(0, 1.2f);
        hi.setPadding(dp(6), dp(2), dp(6), dp(12));
        body.addView(hi);

        body.addView(row(Icon.INFO, "Open on GitHub", false, v -> {
            hidePopup();
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(REPO)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) { }
        }), rowLp(LinearLayout.LayoutParams.MATCH_PARENT));
        body.addView(row(Icon.PLAY_PAUSE, "Play tutorial again", false, v -> pageTour(0)), rowLp(LinearLayout.LayoutParams.MATCH_PARENT));
        body.addView(cell(Icon.DOWN, "Check for updates automatically", "Once a day, from GitHub",
            toggle(updater.autoCheck(), on -> { Prefs.putInt(this, Prefs.UPDATE_CHECK, on ? 1 : 0); return on; })), cellLp());
        TextView nowValue = text(updater.updateAvailable() ? updater.latestKnown() + " available  \u203a" : "\u203a", 17, ACCENT);
        LinearLayout now = cell(Icon.CHECK, "Check now", null, nowValue);
        now.setOnClickListener(v -> {
            if (updater.updateAvailable()) { pageUpdate(); return; }
            nowValue.setText("Checking\u2026");
            updater.check((latest, apk, error) -> {
                if (popup == null) return;
                if (error != null) nowValue.setText(error);
                else if (updater.updateAvailable()) pageUpdate();
                else nowValue.setText("You have the latest version");
            });
        });
        body.addView(now, cellLp());

        note("Part of Viture Neckband Debloater, an unofficial open-source project (MIT license), "
            + "not affiliated with or endorsed by VITURE. No data is collected; the internet is used only to check GitHub for updates.");
        TextView quote = text("\u201cHave no regrets and live a great life!\u201d", 18, FG);
        quote.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
        quote.setGravity(Gravity.CENTER);
        quote.setPadding(dp(6), dp(14), dp(6), dp(6));
        body.addView(quote, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
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
