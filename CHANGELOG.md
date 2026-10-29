# Changelog

## 1.3.0 (unreleased, pending hover test on device)

- TV Home 1.3.0: the control button is invisible until the cursor hovers over its spot in the taskbar row, fades out 1.5 s after the cursor leaves; the panel stays open while hovered and closes 2 s after the cursor leaves (8 s if never hovered). Clicking the spot still opens it if hover is unavailable.

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
