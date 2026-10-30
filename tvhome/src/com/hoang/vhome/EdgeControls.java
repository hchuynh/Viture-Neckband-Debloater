package com.hoang.vhome;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Right-edge controls for head gaze. Rest the cursor on the right edge of the screen for a second:
 * a thin bar appears. Keep it on the bar for another second: a strip opens with media controls
 * while something is playing, or a scroll control otherwise (a button switches between the two).
 *
 * Scroll control: a vertical pill split in two. Click the top or bottom half to scroll one page,
 * double-click it to jump to the top or the bottom, or press and drag along it to scroll
 * continuously; the further you drag, the faster it goes.
 */
final class EdgeControls {
    static final int EDGE_DP = 6, BAR_DP = 30, STRIP_DP = 84, DWELL_MS = 1000, HIDE_MS = 3000;
    static final int MEDIA = 0, SCROLL = 1;

    private final AccessibilityService s;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private EdgeFrame edge, bar;
    private FrameLayout strip;
    private int mode = -1;
    private boolean overStrip = false;

    private final Runnable showBar = this::showBar;
    private final Runnable showStrip = this::openStrip;
    private final Runnable hideAll = this::hideAll;

    EdgeControls(AccessibilityService s) { this.s = s; this.wm = s.getSystemService(WindowManager.class); }

    // ---------------------------------------------------------------- windows
    void start() {
        if (edge != null) return;
        edge = new EdgeFrame(s, (inside, y) -> {
            if (inside) { if (bar == null && strip == null && !h.hasCallbacks(showBar)) h.postDelayed(showBar, DWELL_MS); }
            else h.removeCallbacks(showBar);
        });
        WindowManager.LayoutParams lp = params(dp(EDGE_DP), height());
        lp.gravity = Gravity.END | Gravity.TOP;
        lp.y = top();
        wm.addView(edge, lp);
    }

    void stop() {
        hideAll();
        if (edge != null) { try { wm.removeView(edge); } catch (Exception ignored) { } edge = null; }
        h.removeCallbacksAndMessages(null);
    }

    private int top() { return dp(48); }                       // below the status bar
    private int height() {                                    // above the taskbar row
        return s.getResources().getDisplayMetrics().heightPixels - top() - dp(80);
    }

    private WindowManager.LayoutParams params(int w, int hgt) {
        return new WindowManager.LayoutParams(w, hgt, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
    }

    /** Step 1: a thin glowing bar along the edge says "there is something here". */
    private void showBar() {
        if (bar != null || strip != null) return;
        bar = new EdgeFrame(s, (inside, y) -> {
            if (inside) { h.removeCallbacks(hideAll); if (strip == null && !h.hasCallbacks(showStrip)) h.postDelayed(showStrip, DWELL_MS); }
            else { h.removeCallbacks(showStrip); h.removeCallbacks(hideAll); h.postDelayed(hideAll, 800); }
        });
        View line = new View(s);
        line.setBackground(round(ControlService.ACCENT & 0xCCFFFFFF, 999));
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(dp(6), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END);
        ll.setMargins(0, dp(40), dp(4), dp(40));
        bar.addView(line, ll);
        WindowManager.LayoutParams lp = params(dp(BAR_DP), height());
        lp.gravity = Gravity.END | Gravity.TOP;
        lp.y = top();
        wm.addView(bar, lp);
        h.postDelayed(hideAll, 1500);   // gone soon if it was not intentional
    }

    /** Step 2: the strip itself. */
    private void openStrip() {
        AudioManager am = s.getSystemService(AudioManager.class);
        showStrip(am != null && am.isMusicActive() ? MEDIA : SCROLL);
    }

    private void showStrip(int m) {
        removeBar();
        if (strip != null) { try { wm.removeView(strip); } catch (Exception ignored) { } strip = null; }
        mode = m;
        strip = new EdgeFrame(s, (inside, y) -> {
            overStrip = inside;
            h.removeCallbacks(hideAll);
            if (!inside) h.postDelayed(hideAll, HIDE_MS);
        });
        LinearLayout col = new LinearLayout(s);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(8), dp(10), dp(8), dp(10));
        col.setBackground(round(ControlService.POPUP, dp(22)));
        if (m == MEDIA) {
            col.addView(button(Icon.PREV, v -> media(KeyEvent.KEYCODE_MEDIA_PREVIOUS)), btnLp());
            col.addView(button(Icon.REWIND, v -> media(KeyEvent.KEYCODE_MEDIA_REWIND)), btnLp());
            col.addView(button(Icon.PLAY_PAUSE, v -> media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)), btnLp());
            col.addView(button(Icon.FORWARD, v -> media(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)), btnLp());
            col.addView(button(Icon.NEXT, v -> media(KeyEvent.KEYCODE_MEDIA_NEXT)), btnLp());
            col.addView(divider(), new LinearLayout.LayoutParams(dp(40), dp(2)));
            col.addView(button(Icon.SCROLL, v -> showStrip(SCROLL)), btnLp());
        } else {
            col.addView(scroller(), new LinearLayout.LayoutParams(dp(STRIP_DP - 16), dp(300)));
            col.addView(divider(), new LinearLayout.LayoutParams(dp(40), dp(2)));
            col.addView(button(Icon.MUSIC, v -> showStrip(MEDIA)), btnLp());
        }
        strip.addView(col);
        // closes on a click anywhere else, or after HIDE_MS with the cursor away; using it keeps it open
        strip.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) { hideAll(); return true; }
            return false;
        });
        WindowManager.LayoutParams lp = params(dp(STRIP_DP), WindowManager.LayoutParams.WRAP_CONTENT);
        lp.flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        lp.x = dp(8);
        wm.addView(strip, lp);
        h.removeCallbacks(hideAll);
        h.postDelayed(hideAll, HIDE_MS + 1000);   // closes if the cursor never reaches it
    }

    private void removeBar() {
        if (bar != null) { try { wm.removeView(bar); } catch (Exception ignored) { } bar = null; }
    }

    void hideAll() {
        h.removeCallbacks(showBar); h.removeCallbacks(showStrip); h.removeCallbacks(hideAll);
        stopDrag();
        removeBar();
        if (strip != null) { try { wm.removeView(strip); } catch (Exception ignored) { } strip = null; }
        overStrip = false;
    }

    private void keepOpen() { h.removeCallbacks(hideAll); if (!overStrip) h.postDelayed(hideAll, HIDE_MS); }

    // ---------------------------------------------------------------- media
    private void media(int key) {
        AudioManager am = s.getSystemService(AudioManager.class);
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, key, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, key, 0));
        keepOpen();
    }

    // ---------------------------------------------------------------- scroll
    private long lastClickAt = 0;
    private int lastClickDir = 0;
    private float downY;
    private boolean dragging;
    private int dragDir = 0, dragSpeed = 0;
    private final Runnable dragStep = this::dragStep;
    private final Runnable singlePage = this::singlePage;

    /** Split pill: top half up, bottom half down. Click = one page, double-click = to the end, drag = continuous. */
    private View scroller() {
        LinearLayout pill = new LinearLayout(s);
        pill.setOrientation(LinearLayout.VERTICAL);
        pill.setBackground(round(ControlService.BUTTON, dp(30)));
        View up = button(Icon.UP, null), down = button(Icon.DOWN, null);
        up.setBackground(null); down.setBackground(null);
        pill.addView(up, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        View mid = divider();
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(dp(36), dp(2));
        ml.gravity = Gravity.CENTER_HORIZONTAL;
        pill.addView(mid, ml);
        pill.addView(down, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        pill.setOnTouchListener((v, e) -> {
            int half = v.getHeight() / 2;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = e.getY(); dragging = false; keepOpen(); return true;
                case MotionEvent.ACTION_MOVE: {
                    float dy = e.getY() - downY;
                    if (!dragging && Math.abs(dy) > dp(14)) { dragging = true; h.removeCallbacks(singlePage); }
                    if (dragging) {
                        dragDir = dy > 0 ? 1 : -1;
                        dragSpeed = (int) Math.min(10, Math.abs(dy) / dp(12));
                        if (!h.hasCallbacks(dragStep)) h.post(dragStep);
                    }
                    return true; }
                case MotionEvent.ACTION_UP:
                    if (dragging) { stopDrag(); keepOpen(); return true; }
                    click(e.getY() < half ? -1 : 1);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    stopDrag(); return true;
            }
            return false;
        });
        return pill;
    }

    private void click(int dir) {
        long now = SystemClock.uptimeMillis();
        if (now - lastClickAt < 350 && dir == lastClickDir) {   // double-click: all the way
            h.removeCallbacks(singlePage);
            lastClickAt = 0;
            toEnd(dir, 0);
        } else {
            lastClickAt = now; lastClickDir = dir;
            h.removeCallbacks(singlePage);
            h.postDelayed(singlePage, 360);
        }
        keepOpen();
    }

    private void singlePage() { scroll(lastClickDir, true); }

    private void toEnd(int dir, int n) {
        if (n >= 60) return;
        if (scroll(dir, n == 0)) h.postDelayed(() -> toEnd(dir, n + 1), 70);
    }

    private void dragStep() {
        if (!dragging || dragSpeed <= 0) return;
        scroll(dragDir, false);
        keepOpen();
        h.postDelayed(dragStep, Math.max(60, 480 - dragSpeed * 45));   // further = faster
    }

    private void stopDrag() { dragging = false; dragSpeed = 0; h.removeCallbacks(dragStep); }

    /** Scrolls the largest scrollable area of the app in front. Falls back to a swipe when allowed. */
    private boolean scroll(int dir, boolean swipeFallback) {
        AccessibilityNodeInfo node = target();
        int action = dir > 0 ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        if (node != null && node.performAction(action)) return true;
        if (swipeFallback) swipe(dir);
        return false;
    }

    private AccessibilityNodeInfo target() {
        AccessibilityNodeInfo best = null;
        long bestArea = 0;
        List<AccessibilityWindowInfo> windows = s.getWindows();
        for (AccessibilityWindowInfo w : windows) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
            q.add(root);
            int seen = 0;
            while (!q.isEmpty() && seen++ < 600) {
                AccessibilityNodeInfo n = q.poll();
                if (n.isScrollable() && n.isVisibleToUser()) {
                    Rect r = new Rect(); n.getBoundsInScreen(r);
                    long area = (long) r.width() * r.height();
                    if (area > bestArea) { bestArea = area; best = n; }
                }
                for (int i = 0; i < n.getChildCount(); i++) { AccessibilityNodeInfo c = n.getChild(i); if (c != null) q.add(c); }
            }
            if (best != null) break;   // windows come front to back: stop at the first app with something to scroll
        }
        return best;
    }

    private void swipe(int dir) {
        DisplayMetrics m = s.getResources().getDisplayMetrics();
        float x = m.widthPixels * 0.5f, a = m.heightPixels * 0.70f, b = m.heightPixels * 0.30f;
        Path p = new Path();
        if (dir > 0) { p.moveTo(x, a); p.lineTo(x, b); } else { p.moveTo(x, b); p.lineTo(x, a); }
        s.dispatchGesture(new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(p, 0, 280)).build(), null, null);
    }

    // ---------------------------------------------------------------- views
    private View button(int icon, View.OnClickListener l) {
        View b = new IconView(s, icon);
        b.setBackground(round(ControlService.BUTTON, 999));
        if (l != null) b.setOnClickListener(v -> { l.onClick(v); keepOpen(); });
        return b;
    }

    private View divider() {
        View d = new View(s);
        d.setBackgroundColor(0x33FFFFFF);
        return d;
    }

    private LinearLayout.LayoutParams btnLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(60), dp(60));
        lp.setMargins(0, dp(5), 0, dp(5));
        return lp;
    }

    private int dp(int v) { return Math.round(v * s.getResources().getDisplayMetrics().density); }

    private static GradientDrawable round(int argb, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(argb); d.setCornerRadius(radius); return d;
    }

    /** A view that draws one centred icon. */
    static final class IconView extends View {
        private final Icon icon;
        IconView(android.content.Context c, int type) { super(c); icon = new Icon(type, ControlService.FG, ControlService.BUTTON); }
        @Override protected void onDraw(android.graphics.Canvas c) {
            int size = Math.round(28 * getResources().getDisplayMetrics().density);
            int l = (getWidth() - size) / 2, t = (getHeight() - size) / 2;
            icon.setBounds(l, t, l + size, t + size);
            icon.draw(c);
        }
    }

    /** Window root that reports hover enter/move (true) and exit (false), with the cursor height. */
    static final class EdgeFrame extends FrameLayout {
        interface Listener { void onHover(boolean inside, float y); }
        private final Listener l;
        EdgeFrame(android.content.Context c, Listener l) { super(c); this.l = l; }
        @Override public boolean dispatchHoverEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_HOVER_ENTER:
                case MotionEvent.ACTION_HOVER_MOVE: l.onHover(true, e.getY()); break;
                case MotionEvent.ACTION_HOVER_EXIT: l.onHover(false, e.getY()); break;
            }
            super.dispatchHoverEvent(e);
            return true;
        }
    }
}
