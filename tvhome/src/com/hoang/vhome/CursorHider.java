package com.hoang.vhome;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.WindowManager;

/**
 * Hides the head-gaze cursor after it has been still for a while, and shows it again as soon as the
 * head really moves or a click arrives.
 *
 * Apps report when the cursor passes over a new element; without such reports for STILL_MS the
 * cursor is probably still, so an invisible full-screen layer without a pointer icon is placed on
 * top. That layer sees the exact cursor position, which defines "still" for a head: positions are
 * smoothed, and the cursor counts as moved only when the smoothed position leaves a radius around
 * where it stopped, or when it moves fast. Small head tremors stay inside the radius.
 * A click on the layer removes it and replays the same tap, press or drag to the app underneath.
 */
final class CursorHider {
    static final int STILL_MS = 5000;
    static final float RADIUS_DP = 28f;          // movement tolerated around the resting point
    static final float SPEED_DP_PER_S = 700f;    // a deliberate head turn, revealed at once
    static final float SMOOTH = 0.25f;           // weight of each new sample in the smoothed position

    private final AccessibilityService s;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private View layer;
    private boolean enabled = false, paused = false;
    private long lastActivity = 0;
    private float ax, ay, sx, sy, lx, ly;   // anchor, smoothed, last raw
    private long lt;
    private boolean anchored;

    private final Runnable check = this::check;

    CursorHider(AccessibilityService s) { this.s = s; this.wm = s.getSystemService(WindowManager.class); }

    void setEnabled(boolean on) {
        enabled = on;
        if (on) activity(); else { reveal(); h.removeCallbacks(check); }
    }

    /** Our own panels are open: never hide over them. */
    void setPaused(boolean p) { paused = p; if (p) reveal(); else activity(); }

    /** The cursor moved over something (an app reported a hover), or the user acted. */
    void activity() {
        lastActivity = SystemClock.uptimeMillis();
        if (!enabled) return;
        h.removeCallbacks(check);
        h.postDelayed(check, STILL_MS);
    }

    private void check() {
        if (!enabled || paused || layer != null) return;
        long idle = SystemClock.uptimeMillis() - lastActivity;
        if (idle < STILL_MS) { h.postDelayed(check, STILL_MS - idle); return; }
        hide();
    }

    private float dp(float v) { return v * s.getResources().getDisplayMetrics().density; }

    private void hide() {
        View v = new Layer(s, this);
        v.setPointerIcon(PointerIcon.getSystemIcon(s, PointerIcon.TYPE_NULL));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSPARENT);
        anchored = false;
        try { wm.addView(v, lp); layer = v; } catch (Exception ignored) { }
    }

    void reveal() {
        if (layer != null) { try { wm.removeView(layer); } catch (Exception ignored) { } layer = null; }
    }

    // ---------------------------------------------------------------- events on the layer
    void onHover(MotionEvent e) {
        float x = e.getRawX(), y = e.getRawY();
        long t = e.getEventTime();
        if (!anchored) { ax = sx = lx = x; ay = sy = ly = y; lt = t; anchored = true; return; }
        float dt = Math.max(1, t - lt) / 1000f;
        float speed = (float) Math.hypot(x - lx, y - ly) / dt;
        lx = x; ly = y; lt = t;
        sx += (x - sx) * SMOOTH; sy += (y - sy) * SMOOTH;   // smoothing absorbs head tremor
        boolean far = Math.hypot(sx - ax, sy - ay) > dp(RADIUS_DP);
        boolean fast = speed > dp(SPEED_DP_PER_S) && Math.hypot(x - ax, y - ay) > dp(RADIUS_DP) / 2;
        if (far || fast) { reveal(); activity(); }
    }

    private float downX, downY;
    private long downAt;

    void onTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX(); downY = e.getRawY(); downAt = SystemClock.uptimeMillis();
                break;
            case MotionEvent.ACTION_UP: {
                // the press happened on the layer: remove it and replay the same gesture underneath
                float ux = e.getRawX(), uy = e.getRawY();
                long dur = Math.max(40, Math.min(5000, SystemClock.uptimeMillis() - downAt));
                reveal();
                activity();
                Path p = new Path(); p.moveTo(downX, downY);
                if (Math.hypot(ux - downX, uy - downY) > dp(8)) p.lineTo(ux, uy);
                final GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, dur)).build();
                h.postDelayed(() -> s.dispatchGesture(g, null, null), 30);
                break; }
            case MotionEvent.ACTION_CANCEL:
                reveal(); activity();
                break;
        }
    }

    /** Full-screen, fully transparent layer without a pointer icon. */
    static final class Layer extends View {
        private final CursorHider c;
        Layer(android.content.Context ctx, CursorHider c) { super(ctx); this.c = c; }
        @Override public boolean onHoverEvent(MotionEvent e) { c.onHover(e); return true; }
        @Override public boolean onTouchEvent(MotionEvent e) { c.onTouch(e); return true; }
        @Override public PointerIcon onResolvePointerIcon(MotionEvent e, int i) {
            return PointerIcon.getSystemIcon(getContext(), PointerIcon.TYPE_NULL);
        }
    }
}
