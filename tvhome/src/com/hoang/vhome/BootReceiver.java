package com.hoang.vhome;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * Keeps Headgaze Navigation on after every boot, and hand gestures off when the owner chose so.
 * Viture's firmware resets both at startup, so they are applied at boot and re-applied after
 * 20 s, 60 s and 180 s while the XR layer starts. Gestures are touched only while the global
 * flag vnd_gestures_off = 1 (set by `vnd.sh apply gestures`, removed by its rollback).
 */
public class BootReceiver extends BroadcastReceiver {
    static final String ACTION_APPLY = "com.hoang.vhome.APPLY_HEAD_GAZE";
    static final long[] DELAYS = {20_000, 60_000, 180_000};

    static final String GESTURES = "eden_gesture_mode", GESTURES_OFF_FLAG = "vnd_gestures_off";

    static void apply(Context c) {
        try {
            Settings.Global.putInt(c.getContentResolver(), HeadGazeTile.KEY, 1);
            if (Settings.Global.getInt(c.getContentResolver(), GESTURES_OFF_FLAG, 0) == 1
                    && Settings.Global.getInt(c.getContentResolver(), GESTURES, 0) != 0)
                Settings.Global.putInt(c.getContentResolver(), GESTURES, 0);
        } catch (SecurityException ignored) { /* WRITE_SECURE_SETTINGS not granted */ }
    }

    @Override public void onReceive(Context c, Intent i) {
        apply(c);
        if (ACTION_APPLY.equals(i.getAction())) return;
        AlarmManager am = c.getSystemService(AlarmManager.class);
        for (int n = 0; n < DELAYS.length; n++) {
            PendingIntent pi = PendingIntent.getBroadcast(c, n,
                new Intent(c, BootReceiver.class).setAction(ACTION_APPLY), PendingIntent.FLAG_IMMUTABLE);
            am.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + DELAYS[n], 5_000, pi);
        }
    }
}
