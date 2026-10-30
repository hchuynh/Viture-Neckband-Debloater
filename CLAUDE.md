# Operating rules for AI agents using VND

You are helping someone tune a VITURE Neckband with `vnd.sh`. The person may not be technical. Their device must keep working; battery gains come second.

## Before any change

1. `./vnd.sh pair <ip:port> <code>` (once) and `./vnd.sh connect <ip:port>`. The pairing port/code come from the "Pair device with pairing code" popup; the connection port is the "IP address & Port" line on the Wireless debugging screen. They are different ports.
2. `./vnd.sh backup --no-media`, then `./vnd.sh analyze`. Summarize the top consumers in plain words.
3. `./vnd.sh apply recommended --dry-run`. Show the result and wait for an explicit OK.

## Rules

- Use only `vnd.sh` commands and read-only `adb shell` inspection (`dumpsys`, `settings get`, `pm list`, `getprop`). Do not run `pm uninstall`, `pm clear`, `settings put` outside VND, `reboot bootloader`, `fastboot` or anything requiring root.
- Never edit `lists/never_touch.txt` or move a package out of it. If the person wants something on that list disabled, explain the risk and decline.
- Apply one module at a time when the person is new to this, and report what each one changed.
- `experimental` and `background` need the person's explicit consent after you explain the trade-off (Bluetooth controllers; late notifications).
- After `gestures` or `tvhome install`, ask the person to put on the glasses and confirm: the head-gaze cursor follows, neckband buttons click, Home behaves as described. If anything fails, roll back that journal immediately.
- For per-item choices, do not edit the lists. Run `./vnd.sh configure --export vnd.conf`, change the `T`/`S` letters the person asked for, show the diff with `configure --import vnd.conf --dry-run`, then import with `--yes` after their OK. The interactive `configure` screen is for humans at a terminal.
- Every apply prints a journal path. Mention it; `./vnd.sh rollback --last` undoes the latest run, `restore-stock` undoes everything.

## Security, privacy and legal

- Tell the person, once, that VND is unofficial and point them to DISCLAIMER.md before the first change. VND asks for this acknowledgement itself on the first change; do not pass `--yes` to a change until the person has accepted it.
- Never upload, paste or summarize the contents of `state/` (snapshots, backups, journals) anywhere outside the person's machine. It contains their device data.
- When the session ends, remind the person to switch Wireless debugging off.
- Do not modify VND to bypass licensing, DRM, region locks or any protection measure, and do not add VITURE firmware, APKs or copied documentation to the repository.

## Connection drops

The wireless debugging port changes after sleep, reboot, Wi-Fi reconnects or toggling. When commands fail with `unreachable` or `Connection refused`, do not scan ports or retry in a loop: ask the person for the new "IP address & Port" and run `connect` again. Suggest raising the screen timeout while working. If TV Home is installed, the person can open ☰ → Settings to see the current address and port, or, before a reboot, run `adb shell settings put global vnd_adb_next_boot 1` so the new address appears on screen after it.

If the local adb crashes with `fdsan` or `daemon didn't ACK` (seen in some VMs), prefix commands with `VND_NO_MDNS=1`.

## Explaining results

- "Android System" in the battery screen is UID 1000: Viture's XR layer, Viture's 3D home, the AI service and vendor services together. `analyze` breaks it down.
- The battery estimate lags after big changes. `reset-stats` and 30 to 60 minutes of use give a clean reading; discharge steps in `dumpsys batterystats` are the ground truth.
- Head gaze disables volume keys (Viture behavior). The TV Home quick-settings tile toggles it.
