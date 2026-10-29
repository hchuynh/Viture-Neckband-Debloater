package com.hoang.vhome;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Simple line icons drawn on a 24x24 grid. Original artwork, no external icon set. */
final class Icon extends Drawable {
    static final int MENU = 0, CLOSE = 1, CLOSE_ALL = 2, NOTIFICATIONS = 3, QUICK_SETTINGS = 4,
                     VOLUME_DOWN = 5, VOLUME_UP = 6, GAZE_ON = 7, GAZE_OFF = 8;
    private final int type, fg, bg;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    Icon(int type, int fg, int bg) { this.type = type; this.fg = fg; this.bg = bg; }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height()) / 24f;
        c.save();
        c.translate(b.left + (b.width() - 24 * s) / 2f, b.top + (b.height() - 24 * s) / 2f);
        c.scale(s, s);
        p.setStrokeWidth(2f); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        switch (type) {
            case MENU:
                stroke(); c.drawLine(5, 7, 19, 7, p); c.drawLine(5, 12, 19, 12, p); c.drawLine(5, 17, 19, 17, p); break;
            case CLOSE:
                stroke(); c.drawLine(6, 6, 18, 18, p); c.drawLine(18, 6, 6, 18, p); break;
            case CLOSE_ALL: {   // two stacked windows, the front one with an X
                stroke(); c.drawRoundRect(new RectF(8, 3, 21, 15), 2, 2, p);
                fill(bg); RectF front = new RectF(3, 8, 16, 21); c.drawRoundRect(front, 2, 2, p);
                stroke(); c.drawRoundRect(front, 2, 2, p);
                c.drawLine(7, 12, 12, 17, p); c.drawLine(12, 12, 7, 17, p); break; }
            case NOTIFICATIONS: {   // bell
                stroke(); Path bell = new Path();
                bell.moveTo(6.5f, 17); bell.lineTo(6.5f, 11.5f);
                bell.arcTo(new RectF(6.5f, 6, 17.5f, 17), 180, 180, false);
                bell.lineTo(17.5f, 17); c.drawPath(bell, p);
                c.drawLine(4.5f, 17, 19.5f, 17, p); c.drawLine(12, 3.5f, 12, 6, p);
                fill(fg); c.drawCircle(12, 20, 1.6f, p); break; }
            case QUICK_SETTINGS:   // two toggles
                stroke(); c.drawRoundRect(new RectF(3, 4.5f, 21, 11), 3.25f, 3.25f, p); c.drawRoundRect(new RectF(3, 13, 21, 19.5f), 3.25f, 3.25f, p);
                fill(fg); c.drawCircle(17.5f, 7.75f, 1.9f, p); c.drawCircle(6.5f, 16.25f, 1.9f, p); break;
            case VOLUME_DOWN: case VOLUME_UP: {
                stroke(); Path spk = new Path();
                spk.moveTo(3.5f, 9.5f); spk.lineTo(7, 9.5f); spk.lineTo(11, 6); spk.lineTo(11, 18); spk.lineTo(7, 14.5f); spk.lineTo(3.5f, 14.5f); spk.close();
                c.drawPath(spk, p); c.drawLine(14.5f, 12, 20.5f, 12, p);
                if (type == VOLUME_UP) c.drawLine(17.5f, 9, 17.5f, 15, p); break; }
            case GAZE_ON: case GAZE_OFF: {   // eye
                stroke(); Path eye = new Path(); eye.moveTo(2.5f, 12); eye.quadTo(12, 3.5f, 21.5f, 12); eye.quadTo(12, 20.5f, 2.5f, 12); eye.close();
                c.drawPath(eye, p);
                if (type == GAZE_ON) { fill(fg); c.drawCircle(12, 12, 3.2f, p); }
                else { c.drawCircle(12, 12, 3, p); c.drawLine(4, 20, 20, 4, p); }
                break; }
        }
        c.restore();
    }
    private void stroke() { p.setStyle(Paint.Style.STROKE); p.setColor(fg); }
    private void fill(int color) { p.setStyle(Paint.Style.FILL); p.setColor(color); }
    @Override public void setAlpha(int a) { p.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
