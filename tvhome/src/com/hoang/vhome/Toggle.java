package com.hoang.vhome;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** On/off switch drawn in the panel's colors. It only displays state; the row it sits in handles clicks. */
final class Toggle extends View {
    static final int ON = 0xFF6F8CFF, OFF = 0xFF3A4366, KNOB = 0xFFF4F6FC;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private boolean on;

    Toggle(Context c, boolean on) { super(c); this.on = on; setClickable(false); setFocusable(false); }

    void set(boolean v) { on = v; invalidate(); }
    boolean isOn() { return on; }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), rad = h / 2f;
        r.set(0, 0, w, h);
        p.setColor(on ? ON : OFF);
        c.drawRoundRect(r, rad, rad, p);
        p.setColor(KNOB);
        float k = rad - h * 0.14f;
        c.drawCircle(on ? w - rad : rad, rad, k, p);
    }
}
