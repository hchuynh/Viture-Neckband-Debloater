package com.hoang.vhome;
import android.content.Context;
import android.view.MotionEvent;
import android.widget.FrameLayout;

/** Window root that reports when the pointer enters or leaves it, before any child sees the event. */
final class HoverFrame extends FrameLayout {
    interface Listener { void onHover(boolean inside); }
    private final Listener l;
    private Runnable onBack;
    HoverFrame(Context c, Listener l) { super(c); this.l = l; }

    /** Back (also the one Viture sends from the neckband) closes the window while it has focus. */
    void setOnBack(Runnable r) { onBack = r; }

    @Override public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (onBack != null && e.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == android.view.KeyEvent.ACTION_UP) onBack.run();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

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
