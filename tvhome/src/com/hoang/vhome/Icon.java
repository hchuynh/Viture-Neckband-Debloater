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
                     VOLUME_DOWN = 5, VOLUME_UP = 6, GAZE_ON = 7, GAZE_OFF = 8,
                     SETTINGS = 9, INFO = 10, BUTTONS = 11, DEBUG = 12, BACK = 13, STARTUP = 14, CHECK = 15,
                     UP = 16, DOWN = 17, PREV = 18, NEXT = 19, PLAY_PAUSE = 20, REWIND = 21, FORWARD = 22, SCROLL = 23, MUSIC = 24, TIMER = 25, HAND = 26;
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
            case SETTINGS: {   // gear: ring with eight teeth
                stroke(); c.drawCircle(12, 12, 3, p);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    c.drawLine((float) (12 + 6 * Math.cos(a)), (float) (12 + 6 * Math.sin(a)),
                               (float) (12 + 9 * Math.cos(a)), (float) (12 + 9 * Math.sin(a)), p);
                }
                c.drawCircle(12, 12, 6, p); break; }
            case INFO:
                stroke(); c.drawCircle(12, 12, 9, p); c.drawLine(12, 11, 12, 16.5f, p);
                fill(fg); c.drawCircle(12, 7.6f, 1.3f, p); break;
            case BUTTONS:   // controller with a d-pad and two buttons
                stroke(); c.drawRoundRect(new RectF(2.5f, 7, 21.5f, 17), 5, 5, p);
                c.drawLine(6, 12, 10, 12, p); c.drawLine(8, 10, 8, 14, p);
                fill(fg); c.drawCircle(15.5f, 11, 1.3f, p); c.drawCircle(18, 13.5f, 1.3f, p); break;
            case DEBUG:   // angle brackets
                stroke(); c.drawLine(8, 7, 3.5f, 12, p); c.drawLine(3.5f, 12, 8, 17, p);
                c.drawLine(16, 7, 20.5f, 12, p); c.drawLine(20.5f, 12, 16, 17, p); c.drawLine(13.5f, 5.5f, 10.5f, 18.5f, p); break;
            case BACK:
                stroke(); c.drawLine(19, 12, 5, 12, p); c.drawLine(5, 12, 11, 6, p); c.drawLine(5, 12, 11, 18, p); break;
            case UP:
                stroke(); c.drawLine(6, 15, 12, 9, p); c.drawLine(12, 9, 18, 15, p); break;
            case DOWN:
                stroke(); c.drawLine(6, 9, 12, 15, p); c.drawLine(12, 15, 18, 9, p); break;
            case PREV: case NEXT: {   // bar plus triangle
                fill(fg); Path t = new Path();
                if (type == NEXT) { t.moveTo(6, 6); t.lineTo(15, 12); t.lineTo(6, 18); }
                else { t.moveTo(18, 6); t.lineTo(9, 12); t.lineTo(18, 18); }
                t.close(); c.drawPath(t, p);
                stroke(); if (type == NEXT) c.drawLine(17.5f, 6, 17.5f, 18, p); else c.drawLine(6.5f, 6, 6.5f, 18, p); break; }
            case PLAY_PAUSE: {
                fill(fg); Path t = new Path(); t.moveTo(4, 6); t.lineTo(12, 12); t.lineTo(4, 18); t.close(); c.drawPath(t, p);
                c.drawRoundRect(new RectF(14, 6, 16.5f, 18), 1, 1, p); c.drawRoundRect(new RectF(18.5f, 6, 21, 18), 1, 1, p); break; }
            case REWIND: case FORWARD: {   // two arrowheads
                fill(fg); Path t = new Path();
                if (type == FORWARD) { t.moveTo(3, 6); t.lineTo(12, 12); t.lineTo(3, 18); t.close(); t.moveTo(12, 6); t.lineTo(21, 12); t.lineTo(12, 18); t.close(); }
                else { t.moveTo(21, 6); t.lineTo(12, 12); t.lineTo(21, 18); t.close(); t.moveTo(12, 6); t.lineTo(3, 12); t.lineTo(12, 18); t.close(); }
                c.drawPath(t, p); break; }
            case SCROLL:   // up and down arrows
                stroke(); c.drawLine(12, 3.5f, 12, 20.5f, p); c.drawLine(8, 7.5f, 12, 3.5f, p); c.drawLine(16, 7.5f, 12, 3.5f, p);
                c.drawLine(8, 16.5f, 12, 20.5f, p); c.drawLine(16, 16.5f, 12, 20.5f, p); break;
            case MUSIC:   // note
                stroke(); c.drawLine(10, 17, 10, 5, p); c.drawLine(10, 5, 19, 3.5f, p); c.drawLine(19, 3.5f, 19, 15, p);
                fill(fg); c.drawCircle(7.5f, 17.5f, 2.6f, p); c.drawCircle(16.5f, 15.5f, 2.6f, p); break;
            case TIMER:   // moon
                fill(fg); Path moon = new Path(); moon.addCircle(12, 12, 8, Path.Direction.CW);
                Path cut = new Path(); cut.addCircle(16, 8.5f, 7, Path.Direction.CW);
                moon.op(cut, Path.Op.DIFFERENCE); c.drawPath(moon, p); break;
            case HAND: {   // open palm: four fingers and a thumb
                stroke(); Path hp = new Path();
                hp.moveTo(7, 12); hp.lineTo(7, 6.5f); hp.moveTo(10, 11); hp.lineTo(10, 4.5f); hp.moveTo(13, 11); hp.lineTo(13, 4.5f);
                hp.moveTo(16, 11.5f); hp.lineTo(16, 6.5f);
                hp.moveTo(16, 11.5f); hp.quadTo(18.5f, 9, 19.5f, 11.5f); hp.lineTo(16.5f, 18); hp.quadTo(15, 21, 11.5f, 21);
                hp.quadTo(7, 21, 7, 16); hp.lineTo(7, 12);
                c.drawPath(hp, p); break; }
            case CHECK:
                stroke(); c.drawLine(5, 12.5f, 10, 17.5f, p); c.drawLine(10, 17.5f, 19.5f, 7, p); break;
            case STARTUP: {   // power symbol
                stroke(); c.drawArc(new RectF(4, 4.5f, 20, 20.5f), -60, 300, false, p); c.drawLine(12, 3, 12, 11, p); break; }
        }
        c.restore();
    }
    private void stroke() { p.setStyle(Paint.Style.STROKE); p.setColor(fg); }
    private void fill(int color) { p.setStyle(Paint.Style.FILL); p.setColor(color); }
    @Override public void setAlpha(int a) { p.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
