# Changelog

## 1.8.0 (unreleased)

- TV Home 1.8.0: hide the head-gaze cursor after 5 seconds without moving (Settings, Cursor, on by default). Head tremor is tolerated: positions are smoothed and only a move beyond about 28 dp, or a quick turn, brings the cursor back. A click while hidden reveals it and is passed on to the app.
- Media bar: pressing a button no longer closes it; the edge controls close 2 seconds after the cursor leaves them.
- Settings: new full-width **Home button opens** row on top (Google TV or Android home; Home again opens the other); new Cursor section; Wireless debugging is a full-width row under the two columns, shown only after pressing `</>` in the header (hidden by default; this does not change Android's Developer options); small version label at the bottom.

## 1.7.0 (2026-10-30)

- TV Home 1.7.0, edge controls: rest the cursor on the left or right edge to get a thin scrollbar on that side (click a half for one page, double-click for top or bottom, drag for continuous scrolling that speeds up with distance), plus a centred media bar while something plays (in YouTube the skip buttons double-tap the player, since it ignores the media skip keys). They close on a click elsewhere or 3 seconds after the cursor was last on them. On by default, switchable in Settings.
- Sleep timer (off by default): 15, 30, 60 or 90 minutes, then playback pauses and the screen locks. Time left and a cancel button appear in the panel.
- Settings popup: "Hand gestures" is now a plain switch (on is Viture's default; off turns the camera off at once and after every restart); the debugging port is read automatically, with the system page opening behind the popup and closing again in exactly two steps; new layout with At startup and Wireless debugging on the left, Controls (edge controls, sleep timer, buttons) and About on the right; the one-shot "Wireless debugging at next startup" switch moved to an adb setting (`vnd_adb_next_boot`).
- Back closes the panel and the popup; both also close when the app behind them changes screen.
- Efficiency: TV Home measured idle at 0 CPU ticks per minute, no wakelocks, no jobs, about 9.5 MB of memory. Builds now strip debug information (smaller APK and code in memory), and app updates no longer schedule the three boot-time re-checks.
- Finding: with head gaze on, Viture handles the neckband volume buttons itself, so apps cannot see short or long presses.

## 1.6.0 (2026-10-30)

- TV Home 1.6.0, panel: Notifications and Quick settings merged into one Notifications button (both open the same shade on this device); Vol − and Vol + side by side; Head gaze as a row with an on/off switch; new Settings button. Back closes the panel and popups.
- Settings popup: head gaze at startup (on by default), hand gestures off at startup, Wireless debugging now or at the next startup only, the neckband's Wi-Fi and VPN addresses with the debugging port, button remapping, and About with the repository link.
- Wireless debugging at next startup: turned on once after the restart, then a popup shows the address and port to connect to.
- Button remapping for the neckband volume buttons (while head gaze is off) and any controller button; replaces `vnd_menu_key`, which is still honoured if no mapping was saved.
- New permissions: display over other apps (granted by `tvhome install`, needed to open the Wireless debugging page and the About link from the background) and network state (addresses only, no internet access).
- `tvhome install` journals and `tvhome uninstall` removes TV Home's own settings.
- FINDINGS: why a centred head-gaze pointer is not possible without a Viture firmware change, and which buttons can be remapped.

## 1.5.1 (2026-10-30)

- TV Home 1.5.1: switches its control service back on at every boot. Viture's firmware clears the list of enabled accessibility services at startup, which made the ☰ button disappear after a reboot. Other enabled services are kept; only active while the flag `vnd_tvhome_controls` is set.
- `tvhome install` sets `vnd_tvhome_controls`; `tvhome uninstall` and the rollback remove it, so switching the service off for good means uninstalling TV Home or rolling back.
- Upgrading from 1.5.0: run `./vnd.sh tvhome install` again.

## 1.5.0 (2026-10-29)

- TV Home 1.5.0: also switches hand gestures off at every boot (Viture's firmware turns them back on), only while the flag `vnd_gestures_off` is set.
- `gestures` module sets `vnd_gestures_off`; its rollback and `restore-stock` remove it, so other users' gesture choice is never overridden.

## 1.4.0 (2026-10-29)

- README and FINDINGS: final battery results of a full `battery-test` run (stock about 1 h; tuned 2 h 22 min from 100% to shutdown, 34.9% per hour while streaming), with a level and voltage chart showing the worn-cell collapse below 40%.
- TV Home 1.4.2: the Menu key of an external controller (for example the CheerTouch touchpad) opens and closes the panel; configurable with `vnd_menu_key`, `0` disables it.
- Wider gap between icons and labels in the panel (lost in 1.3.1).
- Fix: the control button now fades out after the cursor leaves (Android reports the exit at the last position inside the button) and after closing the panel with the button itself.
- README: screenshots of the status, list and configurator output, the hidden/revealed button, the panel and the Head gaze tile; screenshot policy for contributors.

## 1.3.0 (2026-10-29)

- README: the helper app explained (its four parts, every permission it uses and how to revoke it, why it is signed and how to verify the certificate fingerprint); using Claude by pointing it at the repository URL, and where Claude must run to reach the neckband.
- TV Home 1.3.1: the control button is invisible until the cursor hovers over its spot in the taskbar row and fades out 1.5 s after the cursor leaves, so it never covers full-screen apps. Clicking the spot also reveals it.
- The panel stays open until a click outside it or a second click on the button. Volume down/up and Head gaze keep it open (Head gaze updates its label in place); Close app, Close all, Notifications and Quick settings close it.

## 1.2.1 (2026-10-29)

- TV Home 1.2.1: the control button sits in the taskbar row next to Back/Home/Recents, matching its color; the panel opens upward above it.
- Original line icons for every control (close, close all as stacked windows, bell, toggles, speaker, eye) replace text symbols.

## 1.2.0 (2026-10-29)

- TV Home 1.2.0: floating ☰ control button over every app, with Close app, Close all apps, Notifications, Quick settings, Volume down/up and Head gaze, for head-gaze navigation where Android expects swipes and drags.
- `tvhome install` enables the control service alongside existing accessibility services (journaled); `tvhome uninstall` removes only it.
- Build script: resources and lambda support.

## 1.1.0 (2026-10-29)

- `battery-test start | status | stop | report`: on-device logger (one line per minute, survives disconnects) and a report with runtime from 100% to shutdown, drain per hour, 10% milestones and test conditions.
- TV Home 1.1.0: switches Headgaze Navigation on at every boot (Viture resets it), re-checked after 20 s, 1 min and 3 min.

## 1.0.1 (2026-10-29)

- DISCLAIMER.md: unofficial status, no warranty, warranty and terms of service, security of wireless debugging, privacy, physical safety, AI agents, trademarks, copyright.
- One-time disclaimer acknowledgement before the first change.
- README rewritten as a complete step-by-step guide with troubleshooting, FAQ and contribution rules.
- CLAUDE.md: security, privacy and legal rules for agents.

## 1.0.0 (2026-10-29)

- First release: `vnd.sh` with modules `radios`, `ui`, `bloat`, `ai`, `startup`, `gestures`, `background`, `experimental`.
- Configurator: `configure` (interactive, per item, with live device state), `configure --export` / `--import` for portable text configurations.
- Journaled rollback (`rollback --last | --all | <journal>`) and `restore-stock`.
- `analyze`: snapshot, CPU ranking that breaks down "Android System", live CPU sample.
- `backup`: settings, package list, user APKs, shared storage.
- TV Home 1.0.1: Google TV as home with a Home toggle to the app list, Head gaze quick-settings tile.
