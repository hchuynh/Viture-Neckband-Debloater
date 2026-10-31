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
 * Edge controls for head gaze. Push the cursor against the left or right edge of the screen and a
 * thin scrollbar appears on that side, set in from the edge. While something is playing, a media bar also appears in
 * the middle of the screen. Both stay while you use them and close on a click elsewhere, or a few
 * seconds after the cursor leaves them.
 *
 * Scrollbar: click the top or bottom half to scroll one page, double-click it to jump to the top or
 * the bottom, or press and drag along it to scroll continuously; the further you drag, the faster.
 */
final class EdgeControls {
    static final int EDGE_DP = 6, BAR_W_DP = 36, BAR_H_DP = 300, GAP_DP = 28, DWELL_MS = 350, HIDE_MS = 2000;

    private final AccessibilityService s;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private EdgeFrame edge, edgeLeft, scrollBar, mediaBar;
    private int side = Gravity.END;   // which edge opened the scrollbar
    private int hovering = 0;   // how many of our windows the cursor is over

    private final Runnable open = this::open;
    private final Runnable openLeft = () -> { side = Gravity.START; open(); };
    private final Runnable openRight = () -> { side = Gravity.END; open(); };
    private final Runnable hideAll = this::hideAll;

    /** Told when the bars open or close, so the cursor is never hidden over them. */
    interface OpenListener { void onOpen(boolean open); }
    private OpenListener openListener;
    void setOpenListener(OpenListener l) { openListener = l; }

    EdgeControls(AccessibilityService s) { this.s = s; this.wm = s.getSystemService(WindowManager.class); }

    boolean isOpen() { return scrollBar != null; }

    // ---------------------------------------------------------------- windows
    void start() {
        if (edge != null) return;
        edge = edgeHotspot(Gravity.END, openRight);
        edgeLeft = edgeHotspot(Gravity.START, openLeft);
    }

    /** A strip a few pixels wide along one edge; a short dwell filters a cursor that only sweeps past. */
    private EdgeFrame edgeHotspot(int gravity, Runnable opener) {
        EdgeFrame f = new EdgeFrame(s, inside -> {
            if (inside) { if (!isOpen() && !h.hasCallbacks(opener)) h.postDelayed(opener, DWELL_MS); }
            else h.removeCallbacks(opener);
        });
        int top = dp(48);
        WindowManager.LayoutParams lp = params(dp(EDGE_DP), s.getResources().getDisplayMetrics().heightPixels - top - dp(80));
        lp.gravity = gravity | Gravity.TOP;
        lp.y = top;
        wm.addView(f, lp);
        return f;
    }

    void stop() {
        hideAll();
        if (edge != null) { try { wm.removeView(edge); } catch (Exception ignored) { } edge = null; }
        if (edgeLeft != null) { try { wm.removeView(edgeLeft); } catch (Exception ignored) { } edgeLeft = null; }
        h.removeCallbacksAndMessages(null);
    }

    private WindowManager.LayoutParams params(int w, int hgt) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, hgt, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        return lp;
    }

    private long lastSeen = 0;   // last time the cursor was on one of the bars, or they were used
    private final Runnable watch = this::watch;

    /** Closes the bars HIDE_MS after the cursor was last on them (and nothing is being dragged). */
    private void watch() {
        if (!isOpen()) return;
        if (!dragging && SystemClock.uptimeMillis() - lastSeen > HIDE_MS) { hideAll(); return; }
        h.postDelayed(watch, 250);
    }

    private EdgeFrame overlay() {
        EdgeFrame f = new EdgeFrame(s, inside -> lastSeen = SystemClock.uptimeMillis());
        f.setHoverTicker(() -> lastSeen = SystemClock.uptimeMillis());
        // a click anywhere else closes both. A click on the other bar also counts as "outside" for this
        // one, so wait a moment: a press inside either bar cancels the pending close.
        f.setTouchWatcher(outside -> {
            if (outside) { h.removeCallbacks(outsideClose); h.postDelayed(outsideClose, 150); }
            else { lastInsideDown = SystemClock.uptimeMillis(); h.removeCallbacks(outsideClose); keepOpen(); }
        });
        return f;
    }

    private final Runnable outsideClose = this::closeIfOutside;
    private long lastInsideDown = 0, ownGestureUntil = 0;

    /**
     * A click on one bar is "outside" for the other one, and the taps TV Home itself sends to the
     * player (YouTube skip) are outside for both. Neither of those should close the controls.
     */
    private void closeIfOutside() {
        long now = SystemClock.uptimeMillis();
        if (now - lastInsideDown < 400 || now < ownGestureUntil) return;
        hideAll();
    }

    private void open() {
        if (isOpen()) return;
        if (openListener != null) openListener.onOpen(true);
        // thin scrollbar, set in from the right edge
        scrollBar = overlay();
        scrollBar.addView(scroller(), new FrameLayout.LayoutParams(dp(BAR_W_DP), dp(BAR_H_DP)));
        WindowManager.LayoutParams lp = params(dp(BAR_W_DP), dp(BAR_H_DP));
        lp.flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        lp.gravity = side | Gravity.CENTER_VERTICAL;   // on the side the cursor came from
        lp.x = dp(GAP_DP);
        wm.addView(scrollBar, lp);

        // media bar in the middle of the screen, only while something is playing
        AudioManager am = s.getSystemService(AudioManager.class);
        if (am != null && am.isMusicActive()) {
            mediaBar = overlay();
            LinearLayout row = new LinearLayout(s);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setBackground(round(ControlService.POPUP & 0xE6FFFFFF, 999));
            row.addView(button(Icon.PREV, KeyEvent.KEYCODE_MEDIA_PREVIOUS), btnLp());
            row.addView(seekButton(Icon.REWIND, -1), btnLp());
            row.addView(button(Icon.PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE), btnLp());
            row.addView(seekButton(Icon.FORWARD, 1), btnLp());
            row.addView(button(Icon.NEXT, KeyEvent.KEYCODE_MEDIA_NEXT), btnLp());
            // scroll up above and scroll down below, centred: next or previous clip in Shorts-style feeds
            LinearLayout col = new LinearLayout(s);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.addView(feedButton(Icon.UP, -1), feedLp(false));
            col.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            col.addView(feedButton(Icon.DOWN, 1), feedLp(true));
            mediaBar.addView(col, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
            // explicit width: a wrap-content window is first measured at dialog width, which squeezed the row
            WindowManager.LayoutParams ml = params(dp(5 * 66 + 20), WindowManager.LayoutParams.WRAP_CONTENT);
            ml.flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
            ml.gravity = Gravity.CENTER;
            wm.addView(mediaBar, ml);
        }
        lastSeen = SystemClock.uptimeMillis() + 1500;   // a little longer the first time, to reach them
        h.removeCallbacks(watch);
        h.post(watch);
    }

    void hideAll() {
        boolean wasOpen = isOpen();
        h.removeCallbacks(open); h.removeCallbacks(openLeft); h.removeCallbacks(openRight); h.removeCallbacks(hideAll); h.removeCallbacks(outsideClose); h.removeCallbacks(watch);
        stopDrag();
        if (scrollBar != null) { try { wm.removeView(scrollBar); } catch (Exception ignored) { } scrollBar = null; }
        if (mediaBar != null) { try { wm.removeView(mediaBar); } catch (Exception ignored) { } mediaBar = null; }
        hovering = 0;
        if (wasOpen && openListener != null) openListener.onOpen(false);
    }

    private void keepOpen() { lastSeen = SystemClock.uptimeMillis(); }

    // ---------------------------------------------------------------- media
    private void media(int key) {
        AudioManager am = s.getSystemService(AudioManager.class);
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, key, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, key, 0));
        keepOpen();
    }

    /**
     * Skip back or forward. YouTube ignores the media skip keys, so there the button does what a finger
     * would: a double tap on the left or right part of the player (10 seconds by default). Other apps
     * get the standard media keys.
     */
    private View seekButton(int icon, int dir) {
        View b = new IconView(s, icon, 28);
        b.setBackground(round(ControlService.BUTTON, 999));
        b.setOnClickListener(v -> {
            keepOpen();
            Rect player = youtubePlayer();
            if (player != null) doubleTap(player.left + player.width() * (dir > 0 ? 0.85f : 0.15f), player.top + player.height() * 0.35f);
            else media(dir > 0 ? KeyEvent.KEYCODE_MEDIA_FAST_FORWARD : KeyEvent.KEYCODE_MEDIA_REWIND);
        });
        return b;
    }

    private Rect youtubePlayer() {
        for (AccessibilityWindowInfo w : s.getWindows()) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || root.getPackageName() == null || !"com.google.android.youtube".contentEquals(root.getPackageName())) return null;
            for (String id : new String[]{"com.google.android.youtube:id/watch_player", "com.google.android.youtube:id/player_view",
                                          "com.google.android.youtube:id/player_fragment_container"}) {
                List<AccessibilityNodeInfo> n = root.findAccessibilityNodeInfosByViewId(id);
                for (AccessibilityNodeInfo x : n) {
                    Rect r = new Rect(); x.getBoundsInScreen(r);
                    if (x.isVisibleToUser() && r.width() > 200 && r.height() > 100) return r;
                }
            }
            Rect r = new Rect(); root.getBoundsInScreen(r);   // full-screen video: the whole window
            return r;
        }
        return null;
    }

    private void doubleTap(float x, float y) {
        ownGestureUntil = SystemClock.uptimeMillis() + 800;
        Path p = new Path(); p.moveTo(x, y);
        s.dispatchGesture(new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(p, 0, 40))
            .addStroke(new GestureDescription.StrokeDescription(p, 140, 40)).build(), null, null);
    }

    // ---------------------------------------------------------------- scroll
    private long lastClickAt = 0;
    private int lastClickDir = 0;
    private float downY;
    private boolean dragging;
    private int dragDir = 0, dragSpeed = 0;
    private final Runnable dragStep = this::dragStep;
    private final Runnable singlePage = this::singlePage;

    /** Thin pill: top half up, bottom half down. Click = one page, double-click = to the end, drag = continuous. */
    private View scroller() {
        LinearLayout pill = new LinearLayout(s);
        pill.setOrientation(LinearLayout.VERTICAL);
        pill.setBackground(round(ControlService.BUTTON & 0xE6FFFFFF, 999));
        pill.addView(new IconView(s, Icon.UP, 22), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        pill.addView(new IconView(s, Icon.DOWN, 22), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        pill.setOnTouchListener((v, e) -> {
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
                    keepOpen();
                    return true; }
                case MotionEvent.ACTION_UP:
                    keepOpen();
                    if (dragging) { stopDrag(); return true; }
                    click(e.getY() < v.getHeight() / 2f ? -1 : 1);
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
    }

    private void singlePage() { scroll(lastClickDir, true); }

    private void toEnd(int dir, int n) {
        if (n >= 60) return;
        if (scroll(dir, n == 0)) h.postDelayed(() -> toEnd(dir, n + 1), 70);
    }

    private void dragStep() {
        if (!dragging || dragSpeed <= 0) return;
        scroll(dragDir, false);
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
        ownGestureUntil = SystemClock.uptimeMillis() + 800;
        DisplayMetrics m = s.getResources().getDisplayMetrics();
        float x = m.widthPixels * 0.5f, a = m.heightPixels * 0.70f, b = m.heightPixels * 0.30f;
        Path p = new Path();
        if (dir > 0) { p.moveTo(x, a); p.lineTo(x, b); } else { p.moveTo(x, b); p.lineTo(x, a); }
        s.dispatchGesture(new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(p, 0, 280)).build(), null, null);
    }

    // ---------------------------------------------------------------- views
    private View button(int icon, int key) {
        View b = new IconView(s, icon, 28);
        b.setBackground(round(ControlService.BUTTON, 999));
        b.setOnClickListener(v -> media(key));
        return b;
    }

    /** Round scroll button for the media bar, on its own translucent pill like the bar. */
    private View feedButton(int icon, int dir) {
        View b = new IconView(s, icon, 28);
        android.graphics.drawable.LayerDrawable bg = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
            round(ControlService.POPUP & 0xE6FFFFFF, 999), round(ControlService.BUTTON, 999)});
        bg.setLayerInset(1, dp(8), dp(8), dp(8), dp(8));
        b.setBackground(bg);
        b.setOnClickListener(v -> { keepOpen(); scroll(dir, true); });
        return b;
    }

    private LinearLayout.LayoutParams feedLp(boolean below) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(72), dp(72));
        if (below) lp.topMargin = dp(10); else lp.bottomMargin = dp(10);
        return lp;
    }

    private LinearLayout.LayoutParams btnLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(56), dp(56));
        lp.setMargins(dp(5), 0, dp(5), 0);
        return lp;
    }

    private int dp(int v) { return Math.round(v * s.getResources().getDisplayMetrics().density); }

    private static GradientDrawable round(int argb, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(argb); d.setCornerRadius(radius); return d;
    }

    /** A view that draws one centred icon. */
    static final class IconView extends View {
        private final Icon icon;
        private final int sizeDp;
        IconView(android.content.Context c, int type, int sizeDp) {
            super(c); this.sizeDp = sizeDp; icon = new Icon(type, ControlService.FG, ControlService.BUTTON);
        }
        @Override protected void onDraw(android.graphics.Canvas c) {
            int size = Math.round(sizeDp * getResources().getDisplayMetrics().density);
            int l = (getWidth() - size) / 2, t = (getHeight() - size) / 2;
            icon.setBounds(l, t, l + size, t + size);
            icon.draw(c);
        }
    }

    /** Window root that reports when the cursor enters (true) or leaves (false) it. */
    static final class EdgeFrame extends FrameLayout {
        interface Listener { void onHover(boolean inside); }
        private final Listener l;
        interface TouchWatcher { void onTouch(boolean outside); }
        private boolean in = false;
        private TouchWatcher tw;
        private Runnable ticker;
        void setHoverTicker(Runnable r) { ticker = r; }
        EdgeFrame(android.content.Context c, Listener l) { super(c); this.l = l; }
        void setTouchWatcher(TouchWatcher t) { tw = t; }
        @Override public boolean dispatchTouchEvent(MotionEvent e) {
            if (tw != null) {
                if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) { tw.onTouch(true); return true; }
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) tw.onTouch(false);
            }
            return super.dispatchTouchEvent(e);
        }
        @Override public boolean dispatchHoverEvent(MotionEvent e) {
            int a = e.getActionMasked();
            boolean now = a == MotionEvent.ACTION_HOVER_ENTER || a == MotionEvent.ACTION_HOVER_MOVE;
            if (a == MotionEvent.ACTION_HOVER_EXIT) now = false;
            if (now && ticker != null) ticker.run();
            if (now != in) { in = now; l.onHover(now); }
            super.dispatchHoverEvent(e);
            return true;
        }
    }
}
