package com.hoang.vhome;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Over-the-air updates from the project's GitHub releases. Nothing else is contacted.
 *
 * Checking reads one public JSON document (the latest release); nothing about the device is sent.
 * Installing downloads the release's TVHome-x.y.z.apk and hands it to Android's own installer, which
 * shows its confirmation and refuses any file not signed with the same key as the installed app.
 * Automatic checks run at most once a day, and only when the person switched them on.
 */
final class Updater {
    static final String API = "https://api.github.com/repos/hchuynh/Viture-Neckband-Debloater/releases/latest";
    static final long DAY = 24L * 3600 * 1000;

    interface Result { void done(String latest, String apkUrl, String error); }
    interface Progress { void step(String message, boolean failed); }

    private final Context c;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences sp;

    Updater(Context c) { this.c = c.getApplicationContext(); sp = c.getSharedPreferences("updates", Context.MODE_PRIVATE); }

    boolean autoCheck() { return Prefs.getInt(c, Prefs.UPDATE_CHECK, 0) == 1; }
    String skipped() { return sp.getString("skip", ""); }
    void skip(String version) { sp.edit().putString("skip", version).apply(); }
    String latestKnown() { return sp.getString("latest", ""); }
    String apkKnown() { return sp.getString("apk", ""); }
    boolean dueForCheck() { return System.currentTimeMillis() - sp.getLong("checked", 0) > DAY; }

    static String installed(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception e) { return "0"; }
    }

    /** True if a is a newer version than b ("1.10.0" > "1.9.2"). */
    static boolean newer(String a, String b) {
        String[] x = a.replaceAll("[^0-9.]", "").split("\\."), y = b.replaceAll("[^0-9.]", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    /** A newer version is known and was not skipped. */
    boolean updateAvailable() {
        String l = latestKnown();
        return !l.isEmpty() && newer(l, installed(c)) && !apkKnown().isEmpty();
    }

    boolean shouldPrompt() { return updateAvailable() && !latestKnown().equals(skipped()); }

    /** Reads the latest release on a background thread; the result arrives on the main thread. */
    void check(Result r) {
        new Thread(() -> {
            String latest = null, apk = null, error = null;
            HttpURLConnection h = null;
            try {
                h = (HttpURLConnection) new URL(API).openConnection();
                h.setConnectTimeout(10_000); h.setReadTimeout(15_000);
                h.setRequestProperty("Accept", "application/vnd.github+json");
                h.setRequestProperty("User-Agent", "TVHome-updater");
                if (h.getResponseCode() != 200) throw new Exception("GitHub answered " + h.getResponseCode());
                JSONObject j = new JSONObject(read(h.getInputStream()));
                latest = j.getString("tag_name").replaceFirst("^v", "");
                JSONArray assets = j.optJSONArray("assets");
                if (assets != null) for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.getString("name").matches("TVHome-.*\\.apk")) apk = a.getString("browser_download_url");
                }
                sp.edit().putLong("checked", System.currentTimeMillis()).putString("latest", latest)
                    .putString("apk", apk == null ? "" : apk).apply();
            } catch (Exception e) {
                error = "Could not reach GitHub";
            } finally { if (h != null) h.disconnect(); }
            final String fl = latest, fa = apk, fe = error;
            main.post(() -> r.done(fl, fa, fe));
        }).start();
    }

    /** Downloads the APK and asks Android to install it; Android shows its own confirmation. */
    void install(String apkUrl, Progress p) {
        new Thread(() -> {
            try {
                post(p, "Downloading…", false);
                HttpURLConnection h = (HttpURLConnection) new URL(apkUrl).openConnection();
                h.setInstanceFollowRedirects(true);
                h.setConnectTimeout(15_000); h.setReadTimeout(60_000);
                h.setRequestProperty("User-Agent", "TVHome-updater");
                int code = h.getResponseCode();
                if (code / 100 == 3) {   // GitHub redirects to its file storage on another host
                    String loc = h.getHeaderField("Location");
                    h.disconnect();
                    h = (HttpURLConnection) new URL(loc).openConnection();
                    h.setRequestProperty("User-Agent", "TVHome-updater");
                    code = h.getResponseCode();
                }
                if (code != 200) throw new Exception("download failed");
                PackageInstaller pi = c.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(c.getPackageName());
                int id = pi.createSession(params);
                try (PackageInstaller.Session s = pi.openSession(id)) {
                    try (InputStream in = h.getInputStream(); OutputStream out = s.openWrite("TVHome.apk", 0, -1)) {
                        byte[] buf = new byte[16384]; int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        s.fsync(out);
                    }
                    h.disconnect();
                    post(p, "Waiting for your confirmation…", false);
                    Intent done = new Intent(c, InstallReceiver.class);
                    PendingIntent pe = PendingIntent.getBroadcast(c, 0, done,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                    s.commit(pe.getIntentSender());
                }
            } catch (Exception e) {
                post(p, "Update failed. Try again later, or update with ./vnd.sh tvhome install.", true);
            }
        }).start();
    }

    private void post(Progress p, String m, boolean failed) { main.post(() -> p.step(m, failed)); }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }
}
