# Disclaimer

Please read this before using the Viture Neckband Debloater ("VND", "this project"). By downloading, running or distributing VND you accept the terms below.

## 1. Unofficial project

VND is an independent, community project. It is **not affiliated with, endorsed by, sponsored by or supported by VITURE**, its parent companies, its platform partners, Google, Qualcomm or any other company whose products are mentioned in this repository. VITURE support teams cannot help with problems caused by VND; please do not contact them about it.

## 2. No warranty, use at your own risk

VND is provided "as is", without warranty of any kind, as stated in the [MIT License](LICENSE). The authors and contributors are not liable for any damage, data loss, malfunction, loss of features, loss of warranty coverage, or any other consequence of using it.

VND was designed to be reversible and was tested on one device. That reduces risk; it does not remove it. Firmware versions differ, Viture can change its software at any time, and a change that is harmless on one unit can behave differently on another.

## 3. Warranty and terms of service

Using Android developer options and `adb` is a standard Android feature available to every user, and VND does not root, unlock, flash or modify system partitions. Whether using it affects your warranty or your agreement with VITURE depends on VITURE's terms and on the consumer law where you live. **Check them yourself before you start.** Nothing in this repository is legal advice.

You are responsible for complying with the terms of service of every app and service on your device. VND only switches features off or on; it does not bypass licensing, DRM, region locks, subscriptions or any technical protection measure, and it must not be modified to do so.

## 4. Security

Wireless debugging gives a full command shell over your network to any computer you pair.

- Pair only computers you own and trust.
- Do not enable wireless debugging on public or shared Wi-Fi.
- Turn wireless debugging off when you are done (Developer options → Wireless debugging).
- Revoke old pairings from time to time (Developer options → Revoke USB debugging authorizations).

## 5. Privacy

VND runs entirely on your computer and talks only to your neckband. It sends nothing to the authors or to any server, and contains no analytics.

The TV Home floating controls run as an Android accessibility service. Accessibility services can, by design, read what is on screen. TV Home uses that ability only to find the Recents screen and its Clear all button when you press a Close button, and to read the "IP address & Port" line of the system Wireless debugging page so it can show you the address. It has no internet permission, keeps no logs and sends nothing. The address stays on the device and is shown only on your screen. The source is in `tvhome/` for anyone to verify. If you prefer not to run an accessibility service, switch it off in Settings → Accessibility; everything else keeps working.

The `state/` folder it creates holds your device's settings, app list, battery statistics and, if you run `backup`, copies of your apps and personal files. Treat it as private: it is excluded from git by `.gitignore`. Never attach it to a public issue or post.

## 6. Physical safety

Do not configure, test or troubleshoot the glasses while walking, cycling, driving or operating machinery. Some changes affect what you see and how navigation works. Sit down, and keep the glasses off until a command has finished.

## 7. AI agents

VND includes rules for AI assistants ([CLAUDE.md](CLAUDE.md)) so you can let an agent operate it. An agent can still make mistakes. Read what it proposes, especially the `--dry-run` output, before you approve a change. You remain responsible for what is executed on your device.

## 8. Measurements

Figures in [docs/FINDINGS.md](docs/FINDINGS.md) come from one device, one firmware build and two days of use. They illustrate the method; they are not a guarantee of battery life or performance on your unit.

## 9. Trademarks

VITURE and related product names are trademarks of their respective owner. Android, Google, Google TV, Google Play, Chromecast and YouTube are trademarks of Google LLC. Qualcomm and Snapdragon are trademarks of Qualcomm Incorporated. Netflix, Disney+, Prime Video, Apple TV, Crunchyroll, Microsoft, Teams, Outlook, Tailscale and every other product name mentioned are trademarks of their respective owners.

These names are used only to identify the products VND works with or refers to (nominative use). Their use does not imply any affiliation or endorsement. The project name describes compatibility with the VITURE Neckband and is not an official product name.

## 10. Copyright

All code, lists and documentation in this repository are original work, licensed under the MIT License. The repository contains no VITURE firmware, code, images, logos, fonts or documentation, and no third-party source code.

- **Package names and setting keys** listed in `lists/` (for example `com.viture.xr`) are technical identifiers observed on the device, included only for interoperability.
- **The TV Home app** contains only original code. Its icon is a standard Android system icon referenced at runtime (`@android:drawable/ic_menu_view`) and not bundled. It is built with the Android SDK, which is subject to its own license terms.
- **Statements about VITURE's behavior** (for example, that volume keys are disabled while Headgaze Navigation is on) are paraphrased facts with a link to the public source; no VITURE text is reproduced.

If you are a rights holder and believe something in this repository infringes your rights, open an issue or contact the maintainer, and it will be reviewed and removed promptly if appropriate.
