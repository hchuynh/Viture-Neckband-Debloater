package com.hoang.vhome;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import java.util.ArrayList;
import java.util.List;

/**
 * Bouncing arrows for the welcome tour, pointing at what a page talks about (the ☰ button, the screen
 * edges). They are drawn in their own overlay windows that ignore touches, so clicks pass through.
 */
final class TourPointer {
    static final int DOWN = 0, LEFT = 1, RIGHT = 2;
    private static final int BOX_DP = 84, TRAVEL_DP = 16;

    private final Context c;
    private final WindowManager wm;
    private final List<View> shown = new ArrayList<>();

    TourPointer(Context c) { this.c = c; this.wm = c.getSystemService(WindowManager.class); }

    private int dp(float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    /**
     * Shows an arrow pointing in direction dir. gravity/x/y place its window like any overlay; the tip
     * of the arrow sits on the side of the box it points to.
     */
    void show(int dir, int gravity, int xDp, int yDp) {
        Arrow a = new Arrow(c, dir, dp(TRAVEL_DP));
        boolean vertical = dir == DOWN;
        int w = dp(vertical ? BOX_DP : BOX_DP + TRAVEL_DP), h = dp(vertical ? BOX_DP + TRAVEL_DP : BOX_DP);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        lp.gravity = gravity;
        lp.x = dp(xDp);
        lp.y = dp(yDp);
        try { wm.addView(a, lp); shown.add(a); } catch (Exception ignored) { return; }
        ObjectAnimator anim = ObjectAnimator.ofFloat(a, "phase", 0f, 1f);
        anim.setDuration(650);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setRepeatMode(ValueAnimator.REVERSE);
        anim.start();
        a.anim = anim;
    }

    void clear() {
        for (View v : shown) {
            if (v instanceof Arrow && ((Arrow) v).anim != null) ((Arrow) v).anim.cancel();
            try { wm.removeView(v); } catch (Exception ignored) { }
        }
        shown.clear();
    }

    /** A thick arrow with a soft halo; "phase" (0..1) moves it towards its tip. */
    static final class Arrow extends View {
        private final int dir, travel;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float phase;
        ObjectAnimator anim;

        Arrow(Context c, int dir, int travel) {
            super(c);
            this.dir = dir; this.travel = travel;
            fill.setColor(ControlService.ACCENT);
            fill.setStyle(Paint.Style.FILL);
            halo.setColor(0x660F1426);
            halo.setStyle(Paint.Style.STROKE);
            halo.setStrokeJoin(Paint.Join.ROUND);
            halo.setStrokeWidth(c.getResources().getDisplayMetrics().density * 6);
        }

        public void setPhase(float p) { phase = p; invalidate(); }
        public float getPhase() { return phase; }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float s = Math.min(w, h);            // the arrow lives in an s x s square, moving along its axis
            float off = phase * travel;
            cv.save();
            // place the arrow's square, then turn a downward arrow towards its target
            if (dir == DOWN) cv.translate((w - s) / 2, off);
            else if (dir == RIGHT) { cv.translate(off, (h - s) / 2); cv.rotate(-90, s / 2, s / 2); }
            else { cv.translate(travel - off, (h - s) / 2); cv.rotate(90, s / 2, s / 2); }
            Path p = new Path();
            float m = s / 2, shaft = s * 0.16f, headW = s * 0.42f, headTop = s * 0.48f, pad = s * 0.08f;
            p.moveTo(m - shaft, pad);
            p.lineTo(m + shaft, pad);
            p.lineTo(m + shaft, headTop);
            p.lineTo(m + headW, headTop);
            p.lineTo(m, s - pad);                 // tip at the bottom
            p.lineTo(m - headW, headTop);
            p.lineTo(m - shaft, headTop);
            p.close();
            cv.drawPath(p, halo);
            cv.drawPath(p, fill);
            cv.restore();
        }
    }
}
