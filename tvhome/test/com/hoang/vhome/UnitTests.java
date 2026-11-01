package com.hoang.vhome;

import java.util.Map;

/**
 * Unit tests for TV Home's pure logic: no device, no Android runtime (android.jar is on the class path
 * only so the classes load). Run with tvhome/test/run-unit-tests.sh; it exits non-zero on any failure.
 */
public final class UnitTests {
    private static int run = 0, failed = 0;

    private static void check(String name, boolean ok) {
        run++;
        if (!ok) { failed++; System.out.println("FAIL  " + name); }
    }

    private static void eq(String name, Object want, Object got) {
        check(name + " (want " + want + ", got " + got + ")", want == null ? got == null : want.equals(got));
    }

    public static void main(String[] a) {
        versions();
        updateAsset();
        profiles();
        keymap();
        actions();
        battery();
        homeHold();
        colours();
        address();
        tour();
        System.out.println(run + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    /** The updater offers a release only when it is really newer. */
    static void versions() {
        check("1.1.0 > 1.0.0", Updater.newer("1.1.0", "1.0.0"));
        check("1.0.0 = 1.0.0 is not newer", !Updater.newer("1.0.0", "1.0.0"));
        check("1.10.0 > 1.9.2 (numeric, not text)", Updater.newer("1.10.0", "1.9.2"));
        check("v1.1.0 > 1.0.9 (tag prefix)", Updater.newer("v1.1.0", "1.0.9"));
        check("1.0 < 1.0.1", !Updater.newer("1.0", "1.0.1"));
        check("0.9.9 < 1.0.0 (renumbered releases)", !Updater.newer("0.9.9", "1.0.0"));
        check("2.0.0 > 1.99.99", Updater.newer("2.0.0", "1.99.99"));
    }

    /** Only the real release file is downloaded, not a renamed or unrelated one. */
    static void updateAsset() {
        check("TVHome-1.1.0.apk matches", "TVHome-1.1.0.apk".matches(Updater.ASSET));
        check("prefixed upload name rejected", !"70d996d9-TVHome-1.0.0.apk".matches(Updater.ASSET));
        check("test build rejected", !"TVHome-ota-test-0.9.9.apk".matches(Updater.ASSET));
        check("source archive rejected", !"Source code.zip".matches(Updater.ASSET));
    }

    /** Presets match what the README and the profile screen promise. */
    static void profiles() {
        int n = Profiles.ITEMS.length;
        eq("one icon per switch", n, Profiles.ICONS.length);
        eq("one name per switch", n, Profiles.ITEM_NAMES.length);
        eq("one subtitle per switch", n, Profiles.ITEM_SUBS.length);
        eq("presets exclude Custom", Profiles.NAMES.length - 1, Profiles.PRESETS.length);
        eq("one description per profile", Profiles.NAMES.length, Profiles.DESCRIPTIONS.length);
        int all = 0;
        for (int i : Profiles.ITEMS) { check("switch bits are distinct", (all & i) == 0); all |= i; }
        for (int p = 0; p < Profiles.PRESETS.length; p++) {
            check(Profiles.NAMES[p] + " keeps head gaze on", Profiles.has(Profiles.PRESETS[p], Profiles.GAZE));
            check(Profiles.NAMES[p] + " keeps the gesture camera off", !Profiles.has(Profiles.PRESETS[p], Profiles.GESTURES));
        }
        int std = Profiles.PRESETS[Profiles.STANDARD], cin = Profiles.PRESETS[Profiles.CINEMA],
            work = Profiles.PRESETS[Profiles.WORK], air = Profiles.PRESETS[Profiles.AIRPLANE];
        check("Standard has no automatic rules", !Profiles.has(std, Profiles.WATCH) && !Profiles.has(std, Profiles.PERIPHERAL));
        check("Cinema has watch mode only", Profiles.has(cin, Profiles.WATCH) && !Profiles.has(cin, Profiles.PERIPHERAL));
        check("Work has the keyboard or mouse rule only", Profiles.has(work, Profiles.PERIPHERAL) && !Profiles.has(work, Profiles.WATCH));
        check("Airplane has the radios off", !Profiles.has(air, Profiles.WIFI) && !Profiles.has(air, Profiles.BT));
        check("Standard, Cinema, Work keep the radios on", Profiles.has(std, Profiles.WIFI | Profiles.BT) && Profiles.has(cin, Profiles.BT) && Profiles.has(work, Profiles.WIFI));
    }

    /** Button remapping survives odd stored values and never captures system buttons. */
    static void keymap() {
        Map<Integer, String> m = Prefs.parseKeymap(null, 82);
        eq("unset keymap: legacy Menu key opens the panel", Prefs.A_PANEL, m.get(82));
        check("unset keymap without legacy key: empty", Prefs.parseKeymap(null, 0).isEmpty());
        check("empty keymap: no mappings at all", Prefs.parseKeymap("", 82).isEmpty());
        m = Prefs.parseKeymap("24=back,82=home", 0);
        eq("two mappings", 2, m.size());
        eq("volume up mapped", Prefs.A_BACK, m.get(24));
        check("power is never remappable", Prefs.parseKeymap("26=back", 0).isEmpty());
        check("mode button (F4) is never remappable", Prefs.parseKeymap("134=back", 0).isEmpty());
        check("Back is never remappable", Prefs.parseKeymap("4=home", 0).isEmpty());
        check("default action is not stored", Prefs.parseKeymap("24=default", 0).isEmpty());
        check("garbage is ignored", Prefs.parseKeymap("x=1,=2,25,,  ", 0).isEmpty());
        eq("spaces are trimmed", Prefs.A_HOME, Prefs.parseKeymap(" 25 = home ", 0).get(25));
    }

    static void actions() {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (String act : Prefs.ACTIONS) {
            String n = Prefs.actionName(act);
            check("action " + act + " has a name", n != null && !n.isEmpty());
            names.add(n);
        }
        eq("action names are distinct", Prefs.ACTIONS.length, names.size());
    }

    /** The panel's battery estimate. */
    static void battery() {
        eq("3 h at 1 A from 3 Ah", "72% · 3 h 0 min", Radio.estimate(72, 3_000_000, 1_000_000));
        eq("minutes only", "10% · 30 min", Radio.estimate(10, 500_000, 1_000_000));
        eq("no drain: percentage only", "72%", Radio.estimate(72, 3_000_000, 0));
        eq("implausible (over 48 h): percentage only", "72%", Radio.estimate(72, 3_000_000, 10_000));
        eq("no charge reading: percentage only", "72%", Radio.estimate(72, 0, 1_000_000));
    }

    /** Volume-up hold versus the taskbar Home button and TV Home's own Home actions. */
    static void homeHold() {
        long t = 100_000;
        check("a plain Home key press is the hold", ControlService.isNeckbandHold(t, 0, 0));
        check("taskbar Home click 0.5 s before: real Home", !ControlService.isNeckbandHold(t, t - 500, 0));
        check("taskbar Home click 2 s before: the hold again", ControlService.isNeckbandHold(t, t - 2000, 0));
        check("TV Home's own Home 1 s before: real Home", !ControlService.isNeckbandHold(t, 0, t - 1000));
    }

    /** Hover and press colours. */
    static void colours() {
        eq("halfway black to white", 0xFF808080, ControlService.mix(0xFF000000, 0xFFFFFFFF, 0.5f));
        eq("t = 0 keeps the colour", 0xFF263054, ControlService.mix(0xFF263054, 0xFFFFFFFF, 0f));
        eq("transparent base gains alpha", 0x80, ControlService.mix(0x00000000, 0xFFFFFFFF, 0.5f) >>> 24);
    }

    /** The debugging address read from the system page. */
    static void address() {
        check("IPv4 with port", Net.ADDRESS.matcher("192.168.1.40:38051").matches());
        check("missing octet rejected", !Net.ADDRESS.matcher("192.168.1:38051").matches());
        check("no port rejected", !Net.ADDRESS.matcher("192.168.1.40").matches());
    }

    static void tour() {
        eq("tour pages", 6, ControlService.TOUR.length);
        for (String[] page : ControlService.TOUR)
            check("tour page has a title and text", page.length == 2 && !page[0].isEmpty() && page[1].length() > 20);
    }
}
