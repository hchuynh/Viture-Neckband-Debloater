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
 * Edge controls for head gaze. Push the cursor against the right edge of the screen and a thin
 * scrollbar appears, set in from the edge. While something is playing, a media bar also appears in
 * the middle of the screen. Both stay while you use them and close on a click elsewhere, or a few
 * seconds after the cursor leaves them.
 *
 * Scrollbar: click the top or bottom half to scroll one page, double-click it to jump to the top or
 * the bottom, or press and drag along it to scroll continuously; the further you drag, the faster.
 */
final class EdgeControls {
    static final int EDGE_DP = 6, BAR_W_DP = 36, BAR_H_DP = 300, GAP_DP = 28, DWELL_MS = 350, HIDE_MS = 3000;

    private final AccessibilityService s;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private EdgeFrame edge, scrollBar, mediaBar;
    private int hovering = 0;   // how many of our windows the cursor is over

    private final Runnable open = this::open;
    private final Runnable hideAll = this::hideAll;

    EdgeControls(AccessibilityService s) { this.s = s; this.wm = s.getSystemService(WindowManager.class); }

    boolean isOpen() { return scrollBar != null; }

    // ---------------------------------------------------------------- windows
    void start() {
        if (edge != null) return;
        // a short dwell filters a cursor that only sweeps past the edge
        edge = new EdgeFrame(s, inside -> {
            if (inside) { if (!isOpen() && !h.hasCallbacks(open)) h.postDelayed(open, DWELL_MS); }
            else h.removeCallbacks(open);
        });
        int top = dp(48);
        WindowManager.LayoutParams lp = params(dp(EDGE_DP), s.getResources().getDisplayMetrics().heightPixels - top - dp(80));
        lp.gravity = Gravity.END | Gravity.TOP;
        lp.y = top;
        wm.addView(edge, lp);
    }

    void stop() {
        hideAll();
        if (edge != null) { try { wm.removeView(edge); } catch (Exception ignored) { } edge = null; }
        h.removeCallbacksAndMessages(null);
    }

    private WindowManager.LayoutParams params(int w, int hgt) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, hgt, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        return lp;
    }

    private EdgeFrame overlay() {
        EdgeFrame f = new EdgeFrame(s, inside -> {
            hovering = Math.max(0, hovering + (inside ? 1 : -1));
            h.removeCallbacks(hideAll);
            if (hovering == 0) h.postDelayed(hideAll, HIDE_MS);
        });
        // a click anywhere else closes both. A click on the other bar also counts as "outside" for this
        // one, so wait a moment: a press inside either bar cancels the pending close.
        f.setTouchWatcher(outside -> {
            if (outside) { h.removeCallbacks(outsideClose); h.postDelayed(outsideClose, 150); }
            else { h.removeCallbacks(outsideClose); keepOpen(); }
        });
        return f;
    }

    private final Runnable outsideClose = this::hideAll;

    private void open() {
        if (isOpen()) return;
        hovering = 0;
        // thin scrollbar, set in from the right edge
        scrollBar = overlay();
        scrollBar.addView(scroller(), new FrameLayout.LayoutParams(dp(BAR_W_DP), dp(BAR_H_DP)));
        WindowManager.LayoutParams lp = params(dp(BAR_W_DP), dp(BAR_H_DP));
        lp.flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
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
            row.addView(button(Icon.REWIND, KeyEvent.KEYCODE_MEDIA_REWIND), btnLp());
            row.addView(button(Icon.PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE), btnLp());
            row.addView(button(Icon.FORWARD, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD), btnLp());
            row.addView(button(Icon.NEXT, KeyEvent.KEYCODE_MEDIA_NEXT), btnLp());
            mediaBar.addView(row);
            WindowManager.LayoutParams ml = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
            ml.flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
            ml.gravity = Gravity.CENTER;
            wm.addView(mediaBar, ml);
        }
        h.removeCallbacks(hideAll);
        h.postDelayed(hideAll, HIDE_MS + 1500);   // closes if the cursor never reaches them
    }

    void hideAll() {
        h.removeCallbacks(open); h.removeCallbacks(hideAll); h.removeCallbacks(outsideClose);
        stopDrag();
        if (scrollBar != null) { try { wm.removeView(scrollBar); } catch (Exception ignored) { } scrollBar = null; }
        if (mediaBar != null) { try { wm.removeView(mediaBar); } catch (Exception ignored) { } mediaBar = null; }
        hovering = 0;
    }

    private void keepOpen() { h.removeCallbacks(hideAll); if (hovering == 0) h.postDelayed(hideAll, HIDE_MS); }

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
            if (now != in) { in = now; l.onHover(now); }
            super.dispatchHoverEvent(e);
            return true;
        }
    }
}
