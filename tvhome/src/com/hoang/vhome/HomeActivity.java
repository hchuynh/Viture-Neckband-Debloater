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
 * Home toggle. Home opens the chosen home (Google TV by default, the Android home, or Viture's 3D
 * home); Home while that one is already in front opens its partner. The choice is set in TV Home settings.
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

    static final String VITURE = "com.viture.launcher";

    /**
     * Home opens the chosen home; Home again while it is in front opens its partner:
     * Google TV and the Android home swap with each other, Viture 3D swaps with the Android home.
     */
    private void route() {
        long now = SystemClock.elapsedRealtime();
        String fg = lastForeground();
        int mode = Prefs.getInt(this, Prefs.HOME_MODE, 0);   // 0 Google TV, 1 Android home, 2 Viture 3D
        String primary = mode == 1 ? LAUNCHER.getPackageName() : mode == 2 ? VITURE : GTV;
        String partner = mode == 0 ? LAUNCHER.getPackageName() : mode == 1 ? GTV : LAUNCHER.getPackageName();
        // Without usage access, fall back to a double press within 2.5 s.
        boolean primaryInFront = (fg != null) ? primary.equals(fg) : (now - lastPress) < 2500;
        lastPress = now;
        String target = primaryInFront ? partner : primary;
        Intent i = null;
        if (GTV.equals(target)) i = getPackageManager().getLaunchIntentForPackage(GTV);
        else if (VITURE.equals(target)) i = new Intent("com.viture.action.VITURE_LAUNCHER").setPackage(VITURE);
        if (i == null) i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(LAUNCHER);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); }
        catch (Exception e) {
            try { startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception e2) {
                try { startActivity(new Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception ignored) { }
            }
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
