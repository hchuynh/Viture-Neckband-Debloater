# VND · Viture Neckband Debloater

**Longer battery life, a quieter system and a microphone that only listens when you ask. No root, no uninstalls, and every change has an undo.**

The VITURE Neckband is a capable Android 13 computer with a Snapdragon 888 and active cooling. Out of the box it also runs a hand-tracking camera, two AI assistants, a hotword listener, telemetry from three different vendors and a 3D home environment that keeps a full CPU core busy while it is on screen. VND switches that off in small, documented modules, records every change in a rollback journal and adds a tiny companion app that makes Google TV your home screen with a quick-settings toggle for head tracking.

It was built by measuring one device for two days with Android's own battery statistics and per-process CPU sampling, then removing only what the measurements justified. The numbers are in [docs/FINDINGS.md](docs/FINDINGS.md).

---

## What it changes

| Module | What it does | Risk |
|---|---|---|
| `radios` | Stops background Wi-Fi/Bluetooth scanning, mobile data (no SIM), location; enables sleep after 10 min | Safe |
| `ui` | Animations at half length, internal display fixed at 60 Hz | Safe |
| `bloat` | Disables telemetry (MeiG, Eden, Google feedback), screensavers, print services, Meet, Messages, Wellbeing | Safe |
| `ai` | Disables Viture AI, the Google app (Hey Google, Assistant, Discover), Android System Intelligence; revokes their microphone access | Moderate: voice search stops |
| `startup` | Disables boot-time services with no use on a neckband: setup restore, tracing, dynamic system updates, carrier IMS, Qualcomm metrics | Safe |
| `gestures` | Hand gestures off (stops the tracking camera), **Headgaze Navigation** on | Safe, see note on volume |
| `background` | Blocks Teams, Outlook and streaming apps from running in the background | Trade-off: late notifications |
| `experimental` | Disables Eden's assistant + gamepad service | Bluetooth controllers may need it |

`recommended` = `radios ui bloat ai startup gestures`. Run `./vnd.sh list` to see every single setting with its stock value and every package with its risk rating and a one-line explanation.

**TV Home** (optional companion app, 12 KB): Home opens Google TV, Home again opens the app list, the device boots into Google TV, and a **Head gaze** tile in quick settings switches head tracking on and off.

---

## The configurator

Modules are the fast path. The configurator is the precise one: every service and setting VND manages, one per line, with its live state on your device.

```bash
./vnd.sh configure
```

```
  #   now      risk        item                                         what it does
  bloat  Telemetry, feedback, screensavers, print, Meet, Messages, Wellbeing
  10  tuned    safe        com.meig.logger                              Diagnostic logger from the modem vendor (MeiG)
  11 *stock    safe        com.eden.report                              Eden (Viture platform) usage reporting
  ...
  1 pending change(s), marked *
```

Type item numbers or ranges (`3 7 12`, `4-9`) to switch them between **tuned** and **stock**, `tune <module>` or `stock <module>` for a whole module, `rec` for the recommended set, then `apply`. VND lists the pending changes, asks for confirmation and writes one rollback journal for the session. Apps on the never-touch list never appear, so they cannot be selected.

**Configuration files** make a setup portable and reviewable:

```bash
./vnd.sh configure --export my.conf             # the device's current choices, one editable line per item
./vnd.sh configure --import my.conf --dry-run   # preview what a file would change
./vnd.sh configure --import my.conf             # make the device match it
```

Each line reads `T` (tuned) or `S` (stock), the module, the item and a comment with its risk and purpose. Change the letter, save, import. Share your file with other owners, keep one per use case, or let Claude edit and import it for you.

---

## The safety model

VND was designed so that the worst outcome of any command is a setting you have to switch back.

- **Nothing is uninstalled.** Apps are disabled with Android's own `pm disable-user`, which one command reverses.
- **No root, no bootloader, no flashing.** Everything goes through standard `adb` over wireless debugging.
- **A never-touch list** ([lists/never_touch.txt](lists/never_touch.txt)) protects the XR layer, Viture's launcher, system UI, Play services, the Play Store, the keyboard, updates and licensing. VND refuses to disable those, `--force` included.
- **Every run writes a journal** in `state/journals/` with the previous value of everything it touched. `rollback --last` undoes the latest run; `rollback --all` undoes everything, newest first.
- **`restore-stock`** puts every setting VND knows back to the value Viture ships with and re-enables every listed app, even without journals.
- **`--dry-run`** shows exactly what would change and changes nothing.
- **A model check** stops VND on anything that is not a VITURE Neckband (`V1231` / `Neckband_Pro`).

If the glasses ever stop responding, your phone or laptop still reaches the neckband over wireless debugging, so `./vnd.sh restore-stock` remains available.

---

## Quick start

**0. Get VND**

```bash
git clone https://github.com/hchuynh/Viture-Neckband-Debloater.git
cd Viture-Neckband-Debloater
```

**1. Install adb** (Android platform-tools)

| System | Command |
|---|---|
| macOS | `brew install android-platform-tools` |
| Windows | `winget install Google.PlatformTools` (run VND in Git Bash or WSL) |
| Linux | `sudo apt install adb` or the [official platform-tools](https://developer.android.com/tools/releases/platform-tools) |

**2. Enable wireless debugging on the neckband.** Settings → About → tap **Build number** seven times to unlock Developer options, then Settings → System → Developer options → **Wireless debugging** on. Your computer and the neckband must be on the same network (Tailscale works too).

**3. Pair once, then connect**

```bash
./vnd.sh pair 192.168.1.40:41445 123456     # "Pair device with pairing code" popup: its port and code
./vnd.sh connect 192.168.1.40:44079         # the "IP address & Port" line on the Wireless debugging screen
```

The connection port changes whenever the neckband sleeps, reboots or toggles wireless debugging. Pairing survives; just run `connect` again with the new port.

**4. Look before you change**

```bash
./vnd.sh backup --no-media      # settings, app list and your apps' install files
./vnd.sh analyze                # what is draining the battery right now
./vnd.sh apply recommended --dry-run
```

**5. Apply**

```bash
./vnd.sh apply recommended
./vnd.sh tvhome install         # optional
./vnd.sh status
```

Put the glasses on after the `gestures` module and check that the head-gaze cursor follows you and the neckband buttons click.

---

## Commands

```
pair <ip:port> <code>       pair once
connect <ip:port>           connect and remember the target
status                      what is applied, module by module
list [module]               every change, stock value and risk
analyze                     snapshot + battery and CPU ranking + live CPU sample
reset-stats                 reset battery statistics for a clean measurement
configure                   interactive per-item configurator
configure --export <f>      save current choices to an editable file
configure --import <f>      make the device match a file (--dry-run to preview)
apply <module...>           radios ui bloat ai startup gestures background experimental
apply recommended | all
tvhome install | uninstall  Google TV home + Head gaze tile
journals                    list rollback journals
rollback --last | --all | <journal>
restore-stock               everything back to Viture's stock values
backup [--no-media]         settings, app list, apks, shared storage

flags: --dry-run  --yes  --force
```

---

## Use it with Claude

VND was built with Claude, and it is designed so anyone can let Claude drive it. Put this folder somewhere Claude can reach (a Claude desktop folder, Claude Code, or any agent with a shell), turn on wireless debugging, and paste:

> Use the VND toolkit in this folder to tune my VITURE Neckband. Read CLAUDE.md first and follow it. Pair with `<ip:pairing-port>` code `<code>`, connect to `<ip:port>`, take a backup and an analysis, show me the dry run of `recommended` and wait for my OK before applying anything.

[CLAUDE.md](CLAUDE.md) gives the agent the operating rules: back up first, dry-run before applying, never edit the never-touch list, ask before `experimental`, re-ask for the port when the connection drops, and test with the user wearing the glasses after `gestures`.

---

## TV Home

A home screen with no interface of its own, so it costs practically nothing.

- **Home** from any app or from the app list opens **Google TV**.
- **Home** while Google TV is in front opens the **stock app list**. Back from Google TV also lands there.
- After a reboot the neckband starts in Google TV.
- The neckband **Settings key** keeps its Viture functions: single press opens Viture's 3D home, long press recenters.
- **Head gaze tile**: pull down quick settings and tap **Head gaze** to switch head tracking on or off. Viture disables volume keys while head gaze is on, so this is the quick way to change volume.

It needs two permissions that `tvhome install` grants over adb: usage access (to know whether Google TV is in front) and secure-settings write (for the tile). Source and a build script are in [tvhome/](tvhome/). Without VND, Settings → Apps → Default apps → Home app switches back to the stock launcher.

**Why head gaze matters:** the stock Android interface has no pointer for the neckband buttons, which is why most owners live in Viture's 3D home or keep the phone remote app open. Headgaze Navigation gives you a motion-sensor cursor in the plain Android interface, so you can leave the 3D home, which is the most expensive thing the device does, and still navigate with your head.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `device unreachable` / `Connection refused` | The port changed. Open Wireless debugging and `connect` to the new IP address & Port. Raise the screen timeout while you work. |
| `failed to authenticate` / `unauthorized` | Pair again; accept the prompt on the neckband if one appears. |
| adb crashes with `fdsan` / `daemon didn't ACK` | Some adb builds crash on mDNS in VMs. Run with `VND_NO_MDNS=1 ./vnd.sh ...` |
| Volume buttons do nothing | Head gaze is on (Viture behavior). Use the Head gaze tile, a Bluetooth headset or the app's own volume. |
| Hand gestures came back | A double press on the Settings key toggles gestures. Double press again or `apply gestures`. |
| A Bluetooth controller stopped working | `rollback` the `experimental` journal, or `adb shell pm enable com.eden.vaservice`. |
| After a Viture system update something is back on | Updates can re-enable system apps. Run `status`, then `apply recommended` again. |
| Battery estimate still looks low | Android averages recent history. `reset-stats`, use the device for 30 to 60 minutes, then `analyze`. |

---

## FAQ

**Can this brick the device?** The design makes that very unlikely: no flashing, no uninstalling, no root, and the components the device needs to start and display are on the never-touch list. A disabled app can always be re-enabled over adb, and a factory reset restores everything in the worst case.

**Does it void the warranty?** VND uses developer options Android exposes to every user. It changes settings and disables apps for your user profile only.

**Will I lose data?** No app data is deleted. Disabled apps keep their data and come back as they were when re-enabled.

**Why is the Chromecast so frugal compared to this?** A Chromecast runs on wall power, sends decoded video straight to HDMI and has no displays of its own to feed. The neckband powers the glasses, composes every frame through the XR layer for a virtual screen, and runs a flagship phone chip with phone Android. The details are in [docs/FINDINGS.md](docs/FINDINGS.md).

**Other Viture models?** VND targets the VITURE Neckband (model `V1231`). Package names may match on other Viture Android devices, but the model check refuses them unless you pass `--force`, and you would be testing on your own.

---

## Project layout

```
vnd.sh                 the tool
lists/                  every setting and package, with stock values and risk ratings (plain text, easy to review)
tvhome/                 TV Home app: TVHome.apk, source, build.sh
docs/FINDINGS.md        measurements and method
CLAUDE.md               operating rules for AI agents
state/                  created at runtime: target, journals, snapshots, backups
```

---

Not affiliated with or endorsed by VITURE. Use at your own risk. MIT licensed, see [LICENSE](LICENSE).
