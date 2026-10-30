package com.hoang.vhome;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * Keeps Headgaze Navigation on after every boot (unless switched off in TV Home settings), and hand
 * gestures off when the owner chose so. It also turns Wireless debugging on once, at the next boot, on request.
 * Viture's firmware resets both at startup, so they are applied at boot and re-applied after
 * 20 s, 60 s and 180 s while the XR layer starts. Gestures are touched only while the global
 * flag vnd_gestures_off = 1 (set by `vnd.sh apply gestures`, removed by its rollback).
 * The firmware also clears the list of enabled accessibility services at boot; while the flag
 * vnd_tvhome_controls = 1 (set by `vnd.sh tvhome install`) the control service is added back,
 * keeping any other enabled services as they are.
 */
public class BootReceiver extends BroadcastReceiver {
    static final String ACTION_APPLY = "com.hoang.vhome.APPLY_HEAD_GAZE";
    static final long[] DELAYS = {20_000, 60_000, 180_000};

    static final String GESTURES = "eden_gesture_mode", GESTURES_OFF_FLAG = "vnd_gestures_off";
    static final String CONTROLS_FLAG = "vnd_tvhome_controls";
    static final String CONTROL_SERVICE = "com.hoang.vhome/com.hoang.vhome.ControlService";

    static void ensureControls(Context c) {
        if (Settings.Global.getInt(c.getContentResolver(), CONTROLS_FLAG, 0) != 1) return;
        String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null || list.isEmpty() || list.equals("null")) list = "";
        for (String item : list.split(":")) if (item.equalsIgnoreCase(CONTROL_SERVICE)) {
            Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            return;
        }
        Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            list.isEmpty() ? CONTROL_SERVICE : list + ":" + CONTROL_SERVICE);
        Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
    }

    static void apply(Context c) {
        try {
            if (Prefs.getInt(c, Prefs.BOOT_GAZE, 1) == 1)
                Settings.Global.putInt(c.getContentResolver(), HeadGazeTile.KEY, 1);
            // "Wireless debugging at next startup": Android switches it off at boot, so turn it on
            // first minutes after startup. The control service shows the address and clears the request.
            if (Prefs.getInt(c, Prefs.ADB_NEXT_BOOT, 0) == 1 && SystemClock.elapsedRealtime() < 10 * 60_000) {
                if (!Net.adbWifiOn(c)) Net.setAdbWifi(c, true);
                Prefs.putInt(c, Prefs.ADB_POPUP, 1);
            }
            if (Settings.Global.getInt(c.getContentResolver(), GESTURES_OFF_FLAG, 0) == 1
                    && Settings.Global.getInt(c.getContentResolver(), GESTURES, 0) != 0)
                Settings.Global.putInt(c.getContentResolver(), GESTURES, 0);
            ensureControls(c);
        } catch (SecurityException ignored) { /* WRITE_SECURE_SETTINGS not granted */ }
    }

    @Override public void onReceive(Context c, Intent i) {
        apply(c);
        // the re-checks are only needed while Viture's layer starts after a boot, not after an app update
        if (!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
        AlarmManager am = c.getSystemService(AlarmManager.class);
        for (int n = 0; n < DELAYS.length; n++) {
            PendingIntent pi = PendingIntent.getBroadcast(c, n,
                new Intent(c, BootReceiver.class).setAction(ACTION_APPLY), PendingIntent.FLAG_IMMUTABLE);
            am.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + DELAYS[n], 5_000, pi);
        }
    }
}
