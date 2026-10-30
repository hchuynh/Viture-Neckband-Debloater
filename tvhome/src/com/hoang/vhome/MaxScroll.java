package com.hoang.vhome;
import android.content.Context;
import android.widget.ScrollView;

/** ScrollView that never grows taller than a given height, so popups stay clear of the taskbar. */
final class MaxScroll extends ScrollView {
    private final int max;
    MaxScroll(Context c, int max) { super(c); this.max = max; }

    @Override protected void onMeasure(int w, int h) {
        int size = MeasureSpec.getSize(h);
        int limit = MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED ? max : Math.min(max, size);
        super.onMeasure(w, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
    }
}
