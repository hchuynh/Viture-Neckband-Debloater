#!/usr/bin/env bash
# Device smoke test for TV Home: install state, permissions, service health and idle cost.
# It only reads the device (dumpsys, settings get, /proc). With --ui it also presses Home once and saves
# a screenshot for review; nothing else is changed.
#
# Usage: ADB=/path/to/adb tvhome/test/device-smoke.sh <ip:port> [--ui]
# Exit code: 0 if every check passed.
set -u
T="${1:?usage: device-smoke.sh <ip:port> [--ui]}"; UI="${2:-}"
ADB="${ADB:-adb}"; A() { "$ADB" -s "$T" "$@"; }; S() { A shell "$@" 2>/dev/null | tr -d '\r'; }
PKG=com.hoang.vhome; pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  ok    $1"; }
bad() { fail=$((fail+1)); echo "  FAIL  $1"; }
chk() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }

"$ADB" connect "$T" >/dev/null 2>&1
echo ":: $(S getprop ro.product.model) on $T"
want=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$(dirname "$0")/../AndroidManifest.xml")
pkg=$(S dumpsys package $PKG)
chk "installed version is $want"            '[[ "$pkg" == *"versionName=$want"* ]]'
chk "targetSdk 28 (Wi-Fi and Bluetooth switches)" '[[ "$pkg" == *"targetSdk=28"* ]]'
chk "WRITE_SECURE_SETTINGS granted"         '[[ "$pkg" == *"android.permission.WRITE_SECURE_SETTINGS: granted=true"* ]]'
for op in GET_USAGE_STATS SYSTEM_ALERT_WINDOW REQUEST_INSTALL_PACKAGES; do
  chk "appop $op allowed" '[[ "$(S appops get $PKG $op)" == *"allow"* ]]'
done
chk "TV Home is the default home" '[[ "$(S cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)" == "$PKG/"* ]]'

acc=$(S dumpsys accessibility)
chk "control service enabled"  '[[ "$(S settings get secure enabled_accessibility_services)" == *"$PKG/$PKG.ControlService"* ]]'
chk "control service bound"    '[[ "$acc" == *"Bound services:{Service[label=TV Home controls"* ]]'
chk "control service not crashed" '[[ "$acc" != *"Crashed services:{Service[label=TV Home"* ]]'
chk "no TV Home crash in the log" '[[ -z "$(S logcat -d -b crash | grep -i $PKG)" ]]'
wins=$(S dumpsys window windows | grep -c "Window{.* u0 $PKG}")
chk "overlays present: button and two edge strips ($wins windows)" '[ "$wins" -ge 3 ]'

pid=$(S pidof $PKG)
if [ -n "$pid" ]; then
  pss=$(S dumpsys meminfo $PKG | awk '/TOTAL PSS:/{print $3; exit}')
  chk "memory under 45 MB PSS (${pss} KB)" '[ "${pss:-999999}" -lt 46080 ]'
  t1=$(S cat /proc/$pid/stat | awk '{print $14+$15}'); sleep 60
  t2=$(S cat /proc/$pid/stat | awk '{print $14+$15}')
  chk "idle CPU under 6 ticks per minute ($((t2-t1)) measured; leave the device untouched)" '[ $((t2-t1)) -le 6 ]'
else bad "TV Home process running"; fi
chk "no wakelocks held"        '[ "$(S dumpsys power | grep -ci $PKG)" -eq 0 ]'
chk "no repeating alarms"      '[[ "$(S dumpsys alarm | grep -A3 $PKG | grep -c "repeatInterval=[1-9]")" -eq 0 ]]'

if [ "$UI" = "--ui" ]; then
  echo ":: UI: pressing Home once (the volume-up hold path); the screenshot is saved for review"
  S input keyevent KEYCODE_WAKEUP; sleep 1; S input keyevent KEYCODE_HOME; sleep 2
  A exec-out screencap -p > tvhome-smoke-home.png && ok "screenshot saved: tvhome-smoke-home.png (check it, then delete it)"
fi
echo ":: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
