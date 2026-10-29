# Findings

Measurements taken on one VITURE Neckband (`V1231`, Android 13, build `TKQ1.240621.001`, Snapdragon 888 "lahaina", 8 GB RAM) paired with VITURE Beast glasses, on 28 and 29 October 2026. They describe that device on that firmware. Re-run `./vnd.sh analyze` on yours: your numbers will differ, and newer firmware may change the picture.

## Method

- **Battery statistics** (`dumpsys batterystats`): Android's energy model per hardware component and per UID, plus CPU time per process.
- **Per-process CPU sampling**: two reads of `/proc/<pid>/stat` 20 to 30 seconds apart, converted to percent of one core.
- **Discharge steps**: the time Android logs for each 1% of battery, the most direct view of real drain.
- **Camera log** (`dumpsys media.camera`): which package opened the camera and when.

## 1. The hand-tracking camera was the largest single drain

In the first battery cycle the camera accounted for about 546 mAh out of roughly 2,800 mAh used, close to 20% of the battery, over almost 90 minutes. The camera log attributes it to `com.viture.xr`, and the matching Viture setting was `eden_gesture_mode=1`. The camera hardware service alone accumulated about 46 minutes of CPU time.

**Action:** `gestures` module (gestures off). Head tracking uses the motion sensors, and the camera stayed closed throughout head-gaze use.

## 2. "Android System" hides about 40 processes

The battery screen shows one "Android System" entry for UID 1000. On this device that UID is shared by every Viture and Eden package (all signed with the same key) plus vendor services. Ranked by CPU time in one cycle:

| Process | CPU time | Notes |
|---|---|---|
| `com.viture.launcher` (Viture 3D home) | ~74 min | ~1 core fully busy while on screen (1 h 11 min in foreground) |
| `com.viture.xr` | ~52 min | the XR compositor, unavoidable |
| camera hardware service | ~46 min | gesture tracking |
| `com.viture.aiservice` | ~15 min | idle AI service, restarted 5 times |
| `com.eden.settings:drmService` | ~1 min | |

Disney+ and YouTube, while actually playing video, used less CPU than the 3D home.

**Action:** `ai` module disables the AI service; head gaze plus TV Home let you leave the 3D home.

## 3. Runtime: before and after

**Before.** With the stock setup (3D home, hand gestures, AI services), the owner's real-world runtime was about 1 hour. Android's average across the first battery cycle (about 1.5 A) understated the worst case, because it mixed heavy stretches with lighter ones; with the camera, the 3D home and the AI service active together the draw is far higher.

**After (interim, 29 October 2026).** With VND `recommended` and TV Home, a `battery-test` run from 100% dropped to 79% in 42 minutes: 29.8% per hour, which projects to 3 h 21 min from 100% to 0%, or 2 h 41 min if the device shuts down at 20%. The full run to shutdown is in progress.

**What this says about the glasses.** At that rate the system draws roughly 4.5 W in total. Most of it is the glasses' displays and the XR composition, which no setting removes; brightness is the main lever left.

**Android's on-screen estimate lags.** After large changes it still weights the old, heavier history. `reset-stats` and 30 to 60 minutes of use give a clean reading; the per-percent discharge steps in `dumpsys batterystats` and the `battery-test` log are the ground truth.

## 4. Where the remaining power goes

- **The glasses.** The neckband powers both micro-OLED displays, dimming, speakers and sensors over the cable. Brightness is the biggest lever left.
- **Two displays.** Android keeps an internal 1080p display (where apps run) and an HDMI display for the glasses; the XR layer places the first onto the second. Both are composed continuously.
- **The XR service** at rest: ~9% of one core; ~17% while moving the head-gaze cursor.

## 5. Small consumers, kept for privacy and memory

- The Google app held ~330 MB across three processes; its energy share was well under 1%.
- Android System Intelligence did not register as a measurable consumer.
- Eden's assistant + gamepad service (`com.eden.vaservice`): zero CPU in a 20-second sample, ~100 MB of RAM, never used the microphone according to app-ops.
- Radios: Wi-Fi/Bluetooth "always scanning" and "mobile data always on" were enabled with no SIM installed.

## 6. Why a Chromecast seems more efficient

A Chromecast draws wall power, its TV powers the panel, and decoded video goes from the decoder straight to HDMI without waking the CPU. The neckband powers the glasses from its battery, routes every frame through the XR compositor for a virtual screen with head-tracked reprojection, composes two displays, and runs full phone Android on a flagship phone chip whose idle floor is higher than a small TV chip's. Per watt delivered to the eyes it is reasonable; the waste was in the optional extras.

## Things deliberately left alone

`com.viture.xr`, `com.viture.launcher`, `com.eden.settings`, `com.eden.systemupgrade`, `com.eden.license.service`, Play services and the system UI. Disabling them risks the glasses' interface, updates or licensing, which no battery gain justifies.
