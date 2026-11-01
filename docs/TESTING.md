# Testing TV Home

Every release goes through three layers, cheapest first. A release is ready when all three pass on a real neckband.

| Layer | Where | Time | What it catches |
|---|---|---|---|
| 1. Unit tests | Computer, no device | Seconds | Logic regressions: version comparison, update file name, profile presets, button remapping, battery estimate, volume-up hold detection, colours, address parsing, tour content |
| 2. Device smoke test | Neckband over adb, read-only | About 2 minutes | Broken install or permissions, the service not running or crashed, missing overlays, memory and idle CPU regressions, wakelocks, repeating alarms |
| 3. Manual regression | Neckband with the glasses on | About 20 minutes | Everything a person sees and touches: head gaze, buttons, overlays, timing |

## 1. Unit tests

```bash
ANDROID_JAR=/path/to/platforms/android-33/android.jar tvhome/test/run-unit-tests.sh
# prints "N checks, 0 failed" and exits 0
```

The tests live in `tvhome/test/com/hoang/vhome/UnitTests.java` and cover only pure functions, so they need no emulator. When you change one of these, add a check next to the existing ones:

| Function | Guards against |
|---|---|
| `Updater.newer` | Offering an older or equal version as an update (1.10 vs 1.9, the renumbered 0.x releases) |
| `Updater.ASSET` | Downloading a renamed upload or a test build instead of the release APK |
| `Profiles.PRESETS` | A preset that no longer matches the README table (gaze always on, gestures always off, radios off only in Airplane) |
| `Prefs.parseKeymap` | Remapping power, the mode button or Back; crashes on malformed stored values |
| `Radio.estimate` | Nonsense battery estimates (zero drain, over 48 hours) |
| `ControlService.isNeckbandHold` | The taskbar Home button opening the controls, or the volume-up hold going home |
| `ControlService.mix` | Hover and press colours |
| `Net.ADDRESS` | Reading a wrong debugging address from the system page |

## 2. Device smoke test

```bash
ADB=/path/to/adb tvhome/test/device-smoke.sh <ip:port>        # read-only
ADB=/path/to/adb tvhome/test/device-smoke.sh <ip:port> --ui   # also presses Home once and saves a screenshot
```

Leave the neckband untouched during the one-minute CPU measurement. The thresholds come from measured 1.1.0 values, with headroom:

| Check | Threshold | Measured on 1.1.0 |
|---|---|---|
| Memory (PSS) | Under 45 MB | 19 MB |
| Idle CPU | At most 6 ticks per minute | 0 |
| Wakelocks | None | None |
| Repeating alarms | None | None (the three boot re-checks are one-shot) |
| Overlay windows | At least 3 (button, two edge strips) | 4 |

The screenshot from `--ui` may show the apps and content on the device: review it locally and delete it, never attach it to an issue.

## 3. Manual regression checklist

Run on a neckband with the glasses on, head gaze on and the Standard profile, unless a step says otherwise. Mark each line; any failure blocks the release.

**Install and first run**
- [ ] `./vnd.sh tvhome install` from the previous release succeeds, keeps TV Home's settings and prints a journal path.
- [ ] After a reboot, head gaze comes back on, the ☰ button appears on hover, and the control service is enabled (Viture clears it at boot; TV Home restores it).
- [ ] On a fresh install, the welcome tour opens after about 4 seconds; arrows point at the ☰ button and both edges; Skip and Done work; the last page's update switch is saved.

**Home**
- [ ] Home opens the chosen home; Home again opens its partner (each of Google TV, Android home, Viture 3D).
- [ ] The taskbar Home circle always goes home, whatever "Hold volume up" is set to.

**Volume-up hold** (Settings → Hold volume up)
- [ ] Scroll and media, in YouTube while a video plays: YouTube comes back, the video keeps playing, the media bar with ▲ and ▼ opens in the centre plus the right scrollbar.
- [ ] Scroll and media, nothing playing: only ▲ and ▼ in the centre plus the right scrollbar.
- [ ] Quick menu: the panel opens in the centre over the current app.
- [ ] A second hold closes what the first opened; the controls also close about 2 seconds after the pointer leaves them.
- [ ] Home: the hold goes home, as on stock Viture.
- [ ] Short volume up is still Back; volume down still clicks, and holding it still drags.

**Panel**
- [ ] The ☰ button fades in on hover and fades out about 2.5 seconds after the pointer stops, also when it stops on the button or during a full-screen video.
- [ ] Every row highlights on hover and flashes on press.
- [ ] Close app, Close all apps, Notifications, Vol − / Vol +, Head gaze switch, Settings, Profile work as labelled.
- [ ] Header: the Wi-Fi and Bluetooth switches change the radio both ways; the battery shows "charging" when plugged in and "xx% · x h xx min" on battery.

**Profiles**
- [ ] Choosing each profile closes the popup and sets head gaze, gestures, Wi-Fi and Bluetooth as in the README table; the Profile row shows its name and icons.
- [ ] Cinema: a full-screen video switches head gaze off within about 2 seconds; a volume button brings it back; it steps aside again once the pointer rests 3 seconds; leaving full screen gives head gaze back.
- [ ] Cinema: switching head gaze on any other way (panel, tile, mode button) gets the same 3-second grace.
- [ ] Work: connecting a Bluetooth mouse or keyboard switches head gaze off; disconnecting brings it back. The glasses' camera alone does not trigger it.
- [ ] Custom: edit, Save and activate; the result survives a reboot.
- [ ] Changing head gaze or gestures in Settings turns the profile into Custom.

**Edge controls**
- [ ] Resting the pointer on either edge opens the scrollbar on that side; it closes about 2 seconds after the pointer leaves.
- [ ] YouTube home, Settings and Chrome: a click scrolls one page, a double-click reaches the top and the bottom, dragging scrolls continuously without bursts.
- [ ] The media bar appears only while something plays; YouTube's skip buttons move 10 seconds.

**Cursor and settings**
- [ ] Hide when still: the cursor hides after 5 seconds without moving, ignores head tremor, and a click while hidden reaches the app.
- [ ] Sleep timer: pauses playback and locks the screen when time is up.
- [ ] Remap buttons: a new mapping works with head gaze off; power and mode cannot be chosen.
- [ ] `</>` shows Wireless debugging (with the address) and, when Tailscale is installed, the Tailscale switch.

**Updates**
- [ ] About → Check now with an older test build installed: the popup offers the release; with Play Protect on, Install here is greyed out and Download from GitHub opens the release page.
- [ ] Skip this version: not offered again for that version.

**Rollback**
- [ ] `./vnd.sh rollback --last` after `tvhome install` restores the previous home app and settings; `./vnd.sh tvhome uninstall` removes the app and every `vnd_*` setting it created.

## Before tagging a release

1. Unit tests pass; the device smoke test passes on the release build.
2. The manual checklist is complete on a neckband.
3. `apksigner verify --print-certs tvhome/TVHome.apk` prints the published certificate fingerprint.
4. The version in `tvhome/AndroidManifest.xml`, `vnd.sh` and CHANGELOG agree.
5. The APK attached to the GitHub release is byte-for-byte `tvhome/TVHome.apk` at the tagged commit (compare SHA-256 after downloading it back).
