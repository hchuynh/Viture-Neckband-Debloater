package com.hoang.vhome;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/** Quick-settings tile toggling Viture head-gaze navigation (needs WRITE_SECURE_SETTINGS granted via adb). */
public class HeadGazeTile extends TileService {
    static final String KEY = "viture_settings_enable_head_gaze";
    private boolean on() { return Settings.Global.getInt(getContentResolver(), KEY, 0) == 1; }
    private void refresh() {
        Tile t = getQsTile(); if (t == null) return;
        boolean on = on();
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setSubtitle(on ? "On" : "Off");
        t.updateTile();
    }
    @Override public void onStartListening() { refresh(); }
    @Override public void onTileAdded() { refresh(); }
    @Override public void onClick() {
        try { Settings.Global.putInt(getContentResolver(), KEY, on() ? 0 : 1); }
        catch (SecurityException e) { Toast.makeText(this, "Permission missing: grant WRITE_SECURE_SETTINGS via adb", Toast.LENGTH_LONG).show(); }
        refresh();
    }
}
