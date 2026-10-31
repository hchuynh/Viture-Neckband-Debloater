package com.hoang.vhome;
import android.app.Activity;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * Home toggle. Home opens the chosen home (Google TV by default, or the Android home); Home while
 * that one is already in front opens the other. The choice is set in TV Home settings.
 */
public class HomeActivity extends Activity {
    static final String GTV = "com.google.android.videos";
    static final ComponentName LAUNCHER =
        new ComponentName("com.android.launcher3", "com.android.searchlauncher.SearchLauncher");
    static long lastPress = 0;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        // Home starts first after boot: switch head gaze on right away, before the boot broadcast arrives.
        if (SystemClock.elapsedRealtime() < 5 * 60 * 1000) BootReceiver.apply(this);
        route(); finish();
    }
    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); route(); finish(); }

    private void route() {
        long now = SystemClock.elapsedRealtime();
        String fg = lastForeground();
        // Without usage access, fall back to a double press within 2.5 s.
        boolean androidFirst = Prefs.getInt(this, Prefs.HOME_MODE, 0) == 1;
        String primary = androidFirst ? LAUNCHER.getPackageName() : GTV;
        boolean primaryInFront = (fg != null) ? primary.equals(fg) : (now - lastPress) < 2500;
        boolean toLauncher = androidFirst != primaryInFront;   // the chosen home, or the other one if it is already in front
        lastPress = now;
        Intent i = null;
        if (!toLauncher) i = getPackageManager().getLaunchIntentForPackage(GTV);
        if (i == null) i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(LAUNCHER);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); }
        catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception ignored) { }
        }
    }

    private String lastForeground() {
        try {
            UsageStatsManager u = getSystemService(UsageStatsManager.class);
            long end = System.currentTimeMillis();
            UsageEvents ev = u.queryEvents(end - 6L * 3600 * 1000, end);
            UsageEvents.Event e = new UsageEvents.Event();
            String last = null;
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() != UsageEvents.Event.ACTIVITY_RESUMED) continue;
                String pkg = e.getPackageName();
                if (getPackageName().equals(pkg)) continue;
                // launcher3 also runs a placeholder on the glasses' HDMI display: only its real launcher counts
                if ("com.android.launcher3".equals(pkg) && !LAUNCHER.getClassName().equals(e.getClassName())) continue;
                    last = e.getPackageName();
            }
            return last;
        } catch (Exception ex) { return null; }
    }
}
