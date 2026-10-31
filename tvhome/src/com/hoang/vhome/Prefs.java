package com.hoang.vhome;
import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.view.KeyEvent;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TV Home preferences. They live in Settings.Global with a vnd_ prefix, so `vnd.sh` can read,
 * journal and remove them, and they survive app updates. `tvhome uninstall` deletes them.
 */
final class Prefs {
    static final String BOOT_GAZE = "vnd_boot_head_gaze";        // 1 (default): head gaze on at every startup
    static final String ADB_NEXT_BOOT = "vnd_adb_next_boot";     // 1: turn Wireless debugging on at the next startup, once
    static final String ADB_POPUP = "vnd_adb_popup";             // 1: show the debugging address as soon as it is known
    static final String KEYMAP = "vnd_keymap";                   // "keycode=action,..."; unset = Menu opens the panel
    static final String LEGACY_MENU_KEY = "vnd_menu_key";        // 1.4/1.5 setting, still honoured if KEYMAP is unset
    static final String ADB_WIFI = "adb_wifi_enabled";
    static final String EDGE = "vnd_edge_controls";              // 1 (default): edge scrollbar and media bar
    static final String HOME_MODE = "vnd_home_mode";             // 0 (default) Google TV, 1 Android home, 2 Viture 3D home
    static final String DEV_MODE = "vnd_dev_mode";               // 1: show developer rows (Wireless debugging); 0 by default
    static final String HIDE_CURSOR = "vnd_hide_cursor";         // 1 (default): hide the cursor after 5 s without moving

    // Button actions
    static final String A_DEFAULT = "default", A_PANEL = "panel", A_BACK = "back", A_HOME = "home",
        A_RECENTS = "recents", A_NOTIFICATIONS = "notifications", A_CLOSE_APP = "close_app",
        A_PLAY_PAUSE = "play_pause", A_GAZE = "gaze", A_VOL_UP = "vol_up", A_VOL_DOWN = "vol_down", A_NONE = "none";
    static final String[] ACTIONS = {A_DEFAULT, A_PANEL, A_BACK, A_HOME, A_RECENTS, A_NOTIFICATIONS,
        A_CLOSE_APP, A_PLAY_PAUSE, A_GAZE, A_VOL_UP, A_VOL_DOWN, A_NONE};

    static String actionName(String a) {
        switch (a) {
            case A_PANEL: return "Open TV Home controls";
            case A_BACK: return "Back";
            case A_HOME: return "Home";
            case A_RECENTS: return "Recent apps";
            case A_NOTIFICATIONS: return "Notifications";
            case A_CLOSE_APP: return "Close app";
            case A_PLAY_PAUSE: return "Play / pause";
            case A_GAZE: return "Head gaze on/off";
            case A_VOL_UP: return "Volume up";
            case A_VOL_DOWN: return "Volume down";
            case A_NONE: return "Do nothing";
            default: return "Original function";
        }
    }

    /**
     * Buttons offered in the list even before they are mapped. Power is never remappable, and the
     * neckband mode button (F4) is handled by Viture's system before any app sees it.
     */
    static final int[] KNOWN_KEYS = {KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_MENU};

    static String keyName(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_VOLUME_UP: return "Neckband volume up";
            case KeyEvent.KEYCODE_VOLUME_DOWN: return "Neckband volume down";
            case KeyEvent.KEYCODE_MENU: return "Controller Menu key";
            default:
                String s = KeyEvent.keyCodeToString(k).replace("KEYCODE_", "").replace('_', ' ').toLowerCase();
                return "Button: " + Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
    }

    static boolean remappable(int k) {
        return k != KeyEvent.KEYCODE_POWER && k != KeyEvent.KEYCODE_SLEEP && k != KeyEvent.KEYCODE_WAKEUP
            && k != KeyEvent.KEYCODE_UNKNOWN && k != KeyEvent.KEYCODE_F4 && k != KeyEvent.KEYCODE_BACK;
    }

    static int getInt(Context c, String key, int def) {
        return Settings.Global.getInt(c.getContentResolver(), key, def);
    }

    static void putInt(Context c, String key, int v) {
        try { Settings.Global.putInt(c.getContentResolver(), key, v); } catch (SecurityException ignored) { }
    }

    static Map<Integer, String> keymap(Context c) {
        ContentResolver r = c.getContentResolver();
        Map<Integer, String> m = new LinkedHashMap<>();
        String raw = Settings.Global.getString(r, KEYMAP);
        if (raw == null || raw.equals("null")) {
            int legacy = Settings.Global.getInt(r, LEGACY_MENU_KEY, KeyEvent.KEYCODE_MENU);
            if (legacy > 0) m.put(legacy, A_PANEL);
            return m;
        }
        for (String item : raw.split(",")) {
            int eq = item.indexOf('=');
            if (eq <= 0) continue;
            try {
                int k = Integer.parseInt(item.substring(0, eq).trim());
                String a = item.substring(eq + 1).trim();
                if (remappable(k) && !A_DEFAULT.equals(a)) m.put(k, a);
            } catch (NumberFormatException ignored) { }
        }
        return m;
    }

    static void saveKeymap(Context c, Map<Integer, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, String> e : m.entrySet()) {
            if (A_DEFAULT.equals(e.getValue())) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        // an empty string (not "unset") means: no mappings at all, not even the default Menu key
        try { Settings.Global.putString(c.getContentResolver(), KEYMAP, sb.toString()); } catch (SecurityException ignored) { }
    }
}
