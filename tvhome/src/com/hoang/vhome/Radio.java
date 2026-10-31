package com.hoang.vhome;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;

/**
 * Wi-Fi, Bluetooth and a battery estimate for the panel header.
 * Android lets an app switch Wi-Fi and Bluetooth directly only while it targets Android 9 (API 28),
 * which is why TV Home targets API 28; on newer targets these calls silently do nothing.
 */
final class Radio {
    static boolean wifiOn(Context c) {
        WifiManager w = c.getApplicationContext().getSystemService(WifiManager.class);
        return w != null && w.isWifiEnabled();
    }

    @SuppressWarnings("deprecation")
    static void setWifi(Context c, boolean on) {
        try {
            WifiManager w = c.getApplicationContext().getSystemService(WifiManager.class);
            if (w != null && w.isWifiEnabled() != on) w.setWifiEnabled(on);
        } catch (Exception ignored) { }
    }

    static boolean bluetoothOn() {
        try { BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter(); return a != null && a.isEnabled(); }
        catch (Exception e) { return false; }
    }

    @SuppressWarnings("deprecation")
    static void setBluetooth(boolean on) {
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null || a.isEnabled() == on) return;
            if (on) a.enable(); else a.disable();
        } catch (Exception ignored) { }
    }

    private static double smoothedMicroAmps = 0;

    /**
     * "72% · 2 h 40 min" while on battery: the remaining charge divided by the average current,
     * smoothed between readings. Android gives apps no estimate of its own.
     */
    static String battery(Context c) {
        BatteryManager bm = c.getSystemService(BatteryManager.class);
        if (bm == null) return "";
        int pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        if (bm.isCharging()) { smoothedMicroAmps = 0; return pct + "% · charging"; }
        long charge = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);   // µAh
        long cur = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE);     // µA
        if (cur == 0 || cur == Long.MIN_VALUE) cur = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        double a = Math.abs((double) cur);
        if (a < 1 || charge <= 0 || charge == Long.MIN_VALUE) return pct + "%";
        smoothedMicroAmps = smoothedMicroAmps == 0 ? a : smoothedMicroAmps * 0.7 + a * 0.3;
        double hours = charge / smoothedMicroAmps;
        if (hours < 0.05 || hours > 48) return pct + "%";
        int mins = (int) Math.round(hours * 60);
        return pct + "% · " + (mins >= 60 ? (mins / 60) + " h " + (mins % 60) + " min" : mins + " min");
    }
}
