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
        // The window root only gets HOVER_EXIT when the pointer leaves the whole window. Android
        // reports it with the last position, which is still inside, so never test the coordinates here.
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE: l.onHover(true); break;
            case MotionEvent.ACTION_HOVER_EXIT: l.onHover(false); break;
        }
        return super.dispatchHoverEvent(e);
    }
}
