package com.hoang.vhome;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.content.ComponentName;
import android.content.Intent;
import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.List;

/**
 * Wireless debugging state and address, read locally. TV Home has no internet permission.
 * Android does not let apps read the debugging port, so the control service reads it from the
 * system's own Wireless debugging page (the "IP address & Port" line) and keeps it here.
 */
final class Net {
    static volatile int port = 0;
    static final java.util.regex.Pattern ADDRESS = java.util.regex.Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}:(\\d{2,5})$");

    static boolean adbWifiOn(Context c) { return Prefs.getInt(c, Prefs.ADB_WIFI, 0) == 1; }

    static void setAdbWifi(Context c, boolean on) {
        Prefs.putInt(c, Prefs.ADB_WIFI, on ? 1 : 0);
        port = 0;   // a new session gets a new port
    }

    static int adbPort() { return port; }

    /** The system page that shows the address: Settings, Developer options, Wireless debugging. */
    static Intent pageIntent() {
        return new Intent("android.service.quicksettings.action.QS_TILE_PREFERENCES")
            .setClassName("com.android.settings", "com.android.settings.Settings$DevelopmentSettingsDashboardActivity")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, new ComponentName("com.android.settings",
                "com.android.settings.development.qstile.DevelopmentTiles$WirelessDebugging"))
            // its own task, away from any Settings screens the person left open, and out of Recents
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    /** IPv4 addresses with a label: Wi-Fi first, then VPNs such as Tailscale. */
    static List<String[]> addresses(Context c) {
        List<String[]> out = new ArrayList<>();
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        if (cm == null) return out;
        for (Network n : cm.getAllNetworks()) {
            LinkProperties lp = cm.getLinkProperties(n);
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (lp == null || nc == null) continue;
            // a VPN also reports the transport it runs over, so test VPN first
            String label = nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "VPN"
                : nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi" : null;
            if (label == null) continue;
            for (LinkAddress a : lp.getLinkAddresses()) {
                if (!(a.getAddress() instanceof Inet4Address)) continue;
                String[] row = {label, a.getAddress().getHostAddress()};
                if (label.equals("Wi-Fi")) out.add(0, row); else out.add(row);
            }
        }
        return out;
    }
}
