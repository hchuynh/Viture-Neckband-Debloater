package com.hoang.vhome;
import android.content.Context;
import android.view.MotionEvent;
import android.widget.FrameLayout;

/** Window root that reports when the pointer enters or leaves it, before any child sees the event. */
final class HoverFrame extends FrameLayout {
    interface Listener { void onHover(boolean inside); }
    private final Listener l;
    HoverFrame(Context c, Listener l) { super(c); this.l = l; }

    @Override public boolean dispatchHoverEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        boolean inside = x >= 0 && y >= 0 && x <= getWidth() && y <= getHeight();
        if (e.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT && !inside) l.onHover(false);
        else if (inside) l.onHover(true);
        return super.dispatchHoverEvent(e);
    }
}
