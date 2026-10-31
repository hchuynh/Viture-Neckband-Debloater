package com.hoang.vhome;
import android.content.Context;

/**
 * Profiles are named combinations of six switches. Standard, Cinema, Work and Airplane are fixed
 * presets; Custom keeps the owner's own combination. Applying a profile sets head gaze and hand
 * gestures now and at every startup, Wi-Fi and Bluetooth now, and turns the automatic head-gaze
 * rules (watch mode, keyboard or mouse) on or off.
 */
final class Profiles {
    static final int GAZE = 1, GESTURES = 2, WATCH = 4, PERIPHERAL = 8, WIFI = 16, BT = 32;
    static final int[] ITEMS = {GAZE, GESTURES, WATCH, PERIPHERAL, WIFI, BT};
    static final int[] ICONS = {Icon.GAZE_ON, Icon.HAND, Icon.FILM, Icon.MOUSE, Icon.WIFI, Icon.BLUETOOTH};
    static final String[] ITEM_NAMES = {"Head gaze", "Hand gestures", "Watch mode", "Keyboard or mouse", "Wi-Fi", "Bluetooth"};
    static final String[] ITEM_SUBS = {
        "Cursor follows your head, also after a restart",
        "Gesture camera; off saves the most battery",
        "Full-screen video turns head gaze off; a volume button brings it back",
        "A connected keyboard or mouse turns head gaze off until it disconnects",
        "Switched on or off when the profile is chosen",
        "Switched on or off when the profile is chosen"};

    static final int STANDARD = 0, CINEMA = 1, WORK = 2, AIRPLANE = 3, CUSTOM = 4;
    static final String[] NAMES = {"Standard", "Cinema", "Work", "Airplane", "Custom"};
    static final String[] DESCRIPTIONS = {
        "Head gaze on, as at every startup",
        "Head gaze steps aside during full-screen video",
        "Keyboard or mouse takes over from head gaze",
        "Head gaze only, radios off",
        "Your own combination"};
    static final int[] PRESETS = {
        GAZE | WIFI | BT,
        GAZE | WATCH | WIFI | BT,
        GAZE | PERIPHERAL | WIFI | BT,
        GAZE};

    static int current(Context c) {
        int p = Prefs.getInt(c, Prefs.PROFILE, STANDARD);
        return p < 0 || p > CUSTOM ? STANDARD : p;
    }

    static int bits(Context c, int profile) {
        return profile == CUSTOM ? Prefs.getInt(c, Prefs.PROFILE_CUSTOM, PRESETS[STANDARD]) : PRESETS[profile];
    }

    static boolean has(int bits, int item) { return (bits & item) != 0; }

    /** Makes the device match the profile. Wi-Fi and Bluetooth are set once; the quick switches can change them after. */
    static void apply(Context c, int profile) {
        Prefs.putInt(c, Prefs.PROFILE, profile);
        int b = bits(c, profile);
        Prefs.putInt(c, Prefs.BOOT_GAZE, has(b, GAZE) ? 1 : 0);
        Prefs.putInt(c, HeadGazeTile.KEY, has(b, GAZE) ? 1 : 0);
        Prefs.putInt(c, BootReceiver.GESTURES_OFF_FLAG, has(b, GESTURES) ? 0 : 1);
        Prefs.putInt(c, BootReceiver.GESTURES, has(b, GESTURES) ? 1 : 0);
        Radio.setWifi(c, has(b, WIFI));
        Radio.setBluetooth(has(b, BT));
    }

    static void saveCustom(Context c, int bits) { Prefs.putInt(c, Prefs.PROFILE_CUSTOM, bits); }

    /**
     * Head gaze or gestures changed in Settings: if that no longer matches the active profile, the
     * owner now has their own combination, so it becomes the Custom profile.
     */
    static void startupChanged(Context c) {
        int p = current(c), b = bits(c, p);
        int nb = b & ~(GAZE | GESTURES);
        if (Prefs.getInt(c, Prefs.BOOT_GAZE, 1) == 1) nb |= GAZE;
        if (Prefs.getInt(c, BootReceiver.GESTURES_OFF_FLAG, 0) == 0) nb |= GESTURES;
        if (nb == b) return;
        saveCustom(c, nb);
        Prefs.putInt(c, Prefs.PROFILE, CUSTOM);
    }
}
