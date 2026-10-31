#!/usr/bin/env bash
# =============================================================================
#  VND - Viture Neckband Debloater
#  Safe, journaled, fully reversible tuning for the VITURE Neckband.
#  No root. No uninstalls. Every change can be rolled back.
#  Works with bash 3.2+ (macOS default) and Linux.
# =============================================================================
set -u
VERSION="1.8.0"
ROOT="$(cd "$(dirname "$0")" && pwd)"
LISTS="$ROOT/lists"
STATE="$ROOT/state"
JOURNALS="$STATE/journals"
SNAPS="$STATE/snapshots"
BACKUPS="$STATE/backups"
TARGET_FILE="$STATE/target"
TVHOME_APK="$ROOT/tvhome/TVHome.apk"
TVHOME_PKG="com.hoang.vhome"
TVHOME_HOME="$TVHOME_PKG/.HomeActivity"
TVHOME_TILE="$TVHOME_PKG/.HeadGazeTile"
TVHOME_A11Y="$TVHOME_PKG/$TVHOME_PKG.ControlService"
TVHOME_PREFS="vnd_boot_head_gaze vnd_adb_next_boot vnd_adb_popup vnd_keymap vnd_menu_key vnd_edge_controls vnd_hide_cursor"   # TV Home settings, removed with it
STOCK_HOME="com.android.launcher3/com.android.searchlauncher.SearchLauncher"
mkdir -p "$JOURNALS" "$SNAPS" "$BACKUPS"

ADB="${ADB:-$(command -v adb 2>/dev/null || true)}"
# Some adb builds crash on mDNS inside VMs/containers. VND_NO_MDNS=1 turns it off.
if [ "${VND_NO_MDNS:-0}" = "1" ]; then export ADB_MDNS=0 ADB_LIBUSB=0 ADB_MDNS_AUTO_CONNECT=0; fi
DRY=0; FORCE=0; YES=0; J=""; JNAME=""

# ---------- output ----------
if [ -t 1 ]; then B=$'\e[1m'; G=$'\e[32m'; Y=$'\e[33m'; R=$'\e[31m'; C=$'\e[36m'; N=$'\e[0m'; else B=; G=; Y=; R=; C=; N=; fi
info(){ printf '%s\n' "${C}::${N} $*"; }
ok(){   printf '%s\n' "  ${G}✓${N} $*"; }
warn(){ printf '%s\n' "  ${Y}!${N} $*"; }
die(){  printf '%s\n' "${R}error:${N} $*" >&2; exit 1; }
confirm(){ [ "$YES" = 1 ] && return 0; printf '%s [y/N] ' "$1"; read -r a; [ "$a" = y ] || [ "$a" = Y ]; }
rows(){ grep -v '^[[:space:]]*#' "$1" | grep -v '^[[:space:]]*$'; }

# ---------- adb plumbing ----------
target(){ if [ -n "${VITURE_TARGET:-}" ]; then echo "$VITURE_TARGET"; elif [ -f "$TARGET_FILE" ]; then cat "$TARGET_FILE"; fi; }
need_adb(){ [ -n "$ADB" ] && [ -x "$ADB" ] || die "adb not found. Install Android platform-tools (see README) or set ADB=/path/to/adb"; }
a(){ # adb against the saved target, reconnecting when needed
  need_adb; local t; t="$(target)"; [ -n "$t" ] || die "no device saved. Run: ./vnd.sh connect <ip:port>"
  "$ADB" start-server >/dev/null 2>&1
  "$ADB" devices | grep -q "^${t}[[:space:]]*device" || "$ADB" connect "$t" >/dev/null 2>&1
  "$ADB" -s "$t" "$@" </dev/null; }
sh_(){ a shell "$@" | tr -d '\r'; }

preflight(){
  local dev model
  dev="$(sh_ getprop ro.product.device)" || die "device unreachable"
  [ -n "$dev" ] || die "device unreachable at $(target). Wireless debugging port changes after sleep or reboot: run connect again."
  model="$(sh_ getprop ro.product.model)"
  if [ "$dev" != "Neckband_Pro" ] && [ "$model" != "V1231" ]; then
    [ "$FORCE" = 1 ] || die "this is '$model/$dev', not a VITURE Neckband. Refusing (use --force at your own risk)."
    warn "unsupported device $model/$dev, continuing because of --force"
  fi; }

acknowledge(){ # one-time disclaimer acknowledgement before the first change
  [ "$DRY" = 1 ] && return; [ -f "$STATE/.disclaimer-accepted" ] && return
  echo; info "${B}Before the first change${N}"
  echo "  VND is an unofficial community tool, not affiliated with or endorsed by VITURE."
  echo "  It is provided as is, without warranty. Changes are reversible by design, but you use it at your own risk."
  echo "  Check VITURE's terms and your local law regarding warranty. Full text: DISCLAIMER.md"
  if [ "$YES" = 1 ]; then warn "accepted via --yes"; else confirm "I have read DISCLAIMER.md and accept it" || die "not accepted, nothing changed"; fi
  date > "$STATE/.disclaimer-accepted"; }

protected(){ rows "$LISTS/never_touch.txt" | grep -qx "$1"; }
installed(){ sh_ pm list packages "$1" | grep -qx "package:$1"; }
enabled(){ sh_ pm list packages -e "$1" | grep -qx "package:$1"; }

# ---------- journal (data, replayed in reverse on rollback) ----------
jopen(){ JNAME="$1"; J="$JOURNALS/$(date +%Y%m%d-%H%M%S)_$1.journal"; }   # file created on the first change
jw(){ [ "$DRY" = 1 ] && return
  [ -f "$J" ] || { echo "# VND $VERSION journal: $JNAME"; echo "# target=$(target) date=$(date)"; } > "$J"
  echo "$*" >> "$J"; }

# ---------- primitives ----------
set_setting(){ # ns key value desc
  local ns="$1" k="$2" v="$3" d="$4" old; old="$(sh_ settings get "$ns" "$k")"
  if [ "$old" = "$v" ] || { [ "$v" = "-" ] && { [ "$old" = null ] || [ -z "$old" ]; }; }; then ok "$ns/$k already set ($d)"; return; fi
  if [ "$DRY" = 1 ]; then echo "  would set $ns/$k: $old -> $v   ($d)"; return; fi
  jw "setting|$ns|$k|$old"
  if [ "$v" = "-" ]; then sh_ settings delete "$ns" "$k" >/dev/null; else sh_ settings put "$ns" "$k" "$v"; fi
  ok "$ns/$k: $old -> $v   ($d)"; }

disable_pkg(){ # pkg risk desc
  local p="$1" r="$2" d="$3"
  if protected "$p"; then warn "$p is on the never-touch list, skipped"; return; fi
  if ! installed "$p"; then ok "$p not installed, nothing to do"; return; fi
  if ! enabled "$p"; then ok "$p already disabled"; return; fi
  if [ "$DRY" = 1 ]; then echo "  would disable [$r] $p   ($d)"; return; fi
  if sh_ pm disable-user --user 0 "$p" | grep -q disabled; then jw "pkg|$p"; ok "disabled [$r] $p   ($d)"; else warn "could not disable $p"; fi; }

do_settings(){ rows "$LISTS/$1" | while IFS='|' read -r ns k v st d; do set_setting "$ns" "$k" "$v" "$d"; done; }
do_pkgs(){ rows "$LISTS/$1" | while IFS='|' read -r p r d; do disable_pkg "$p" "$r" "$d"; done; }

# ---------- modules ----------
MODULES="radios ui bloat ai startup gestures background experimental"
RECOMMENDED="radios ui bloat ai startup gestures"
describe(){ case "$1" in
  radios) echo "Background Wi-Fi/BT scanning, mobile data, location, sleep timeout";;
  ui) echo "Faster animations, internal display fixed at 60 Hz";;
  bloat) echo "Telemetry, feedback, screensavers, print, Meet, Messages, Wellbeing";;
  ai) echo "Viture AI, Google app + Hey Google, System Intelligence, mic access";;
  startup) echo "Boot-time services with no use on this device";;
  gestures) echo "Hand gestures (camera) off, Headgaze Navigation on";;
  background) echo "Block streaming/Office apps in background (late notifications)";;
  experimental) echo "Eden assistant + gamepad service (BT controllers may need it)";;
  esac; }

mod_radios(){ do_settings settings_radios.txt; }
mod_ui(){ do_settings settings_ui.txt; }
mod_bloat(){ do_pkgs pkg_bloat.txt; }
mod_startup(){ do_pkgs pkg_startup.txt; }
mod_gestures(){ do_settings settings_gestures.txt
  [ "$DRY" = 1 ] || warn "head gaze disables volume keys while on (Viture behavior). Toggle it with the TV Home quick-settings tile."; }
mod_ai(){ do_settings settings_ai.txt
  rows "$LISTS/perms_ai.txt" | while IFS='|' read -r p perm; do
    installed "$p" && enabled "$p" || continue
    if [ "$DRY" = 1 ]; then echo "  would revoke $perm from $p"; continue; fi
    sh_ pm revoke "$p" "$perm" 2>/dev/null && { jw "perm|$p|$perm"; ok "revoked microphone from $p"; }
  done
  do_pkgs pkg_ai.txt; }
mod_background(){ rows "$LISTS/background.txt" | while read -r p; do
    installed "$p" || continue
    local old; old="$(sh_ cmd appops get "$p" RUN_ANY_IN_BACKGROUND | grep -oE 'allow|ignore|deny' | head -1)"; old="${old:-allow}"
    [ "$old" = ignore ] && { ok "$p already background-restricted"; continue; }
    if [ "$DRY" = 1 ]; then echo "  would block background for $p"; continue; fi
    sh_ cmd appops set "$p" RUN_ANY_IN_BACKGROUND ignore && { jw "appop|$p|RUN_ANY_IN_BACKGROUND|$old"; ok "background blocked: $p"; }
  done; }
mod_experimental(){
  [ "$DRY" = 1 ] || confirm "Experimental: disables Eden's assistant + gamepad service. Bluetooth controllers may stop working. Continue?" || { warn "skipped experimental"; return; }
  do_pkgs pkg_experimental.txt; }

# ---------- commands ----------
cmd_pair(){ [ $# -ge 2 ] || die "usage: pair <ip:pairing-port> <6-digit-code>"; need_adb; "$ADB" start-server >/dev/null 2>&1; "$ADB" pair "$1" "$2"; }
cmd_connect(){ [ $# -ge 1 ] || die "usage: connect <ip:port>   (Settings > Developer options > Wireless debugging > IP address & Port)"
  need_adb; "$ADB" start-server >/dev/null 2>&1; "$ADB" connect "$1" | grep -q "connected to" || die "could not connect to $1"
  echo "$1" > "$TARGET_FILE"; preflight; ok "connected to $(sh_ getprop ro.product.model) ($(sh_ getprop ro.product.device)), Android $(sh_ getprop ro.build.version.release), build $(sh_ getprop ro.build.display.id)"; }

cmd_status(){ preflight
  info "${B}$(sh_ getprop ro.product.model) / $(sh_ getprop ro.product.device)${N}  Android $(sh_ getprop ro.build.version.release)  build $(sh_ getprop ro.build.display.id)"
  info "battery $(sh_ dumpsys battery | awk '/level/{print $2}')%   uptime$(sh_ uptime | sed 's/.*up/ /;s/,.*//')"
  local dis; dis="$(sh_ pm list packages -d)"
  for m in $MODULES; do
    local tot=0 nok=0 f
    for f in "$LISTS"/settings_"$m".txt; do [ -f "$f" ] || continue
      while IFS='|' read -r ns k v st d; do tot=$((tot+1)); cur="$(sh_ settings get "$ns" "$k")"
        { [ "$cur" = "$v" ] || { [ "$v" = "-" ] && { [ "$cur" = null ] || [ -z "$cur" ]; }; }; } && nok=$((nok+1)); done < <(rows "$f"); done
    for f in "$LISTS"/pkg_"$m".txt; do [ -f "$f" ] || continue
      while IFS='|' read -r p r d; do tot=$((tot+1)); echo "$dis" | grep -qx "package:$p" && nok=$((nok+1)); done < <(rows "$f"); done
    [ "$m" = background ] && { tot=$(rows "$LISTS/background.txt" | wc -l | tr -d ' '); nok=0
      while read -r p; do sh_ cmd appops get "$p" RUN_ANY_IN_BACKGROUND 2>/dev/null | grep -q ignore && nok=$((nok+1)); done < <(rows "$LISTS/background.txt"); }
    printf '  %-13s %2s/%-2s applied   %s\n' "$m" "$nok" "$tot" "$(describe "$m")"
  done
  local home; home="$(sh_ cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)"
  printf '  %-13s %s\n' "home" "$home"
  printf '  %-13s %s\n' "head gaze" "$([ "$(sh_ settings get global viture_settings_enable_head_gaze)" = 1 ] && echo on || echo off)"; }

cmd_list(){ local m
  for m in ${1:-$MODULES}; do echo; info "${B}$m${N}: $(describe "$m")"
    [ -f "$LISTS/settings_$m.txt" ] && rows "$LISTS/settings_$m.txt" | while IFS='|' read -r ns k v st d; do printf '    setting  %-45s %s -> %s   %s\n' "$ns/$k" "$st" "$v" "$d"; done
    [ -f "$LISTS/pkg_$m.txt" ] && rows "$LISTS/pkg_$m.txt" | while IFS='|' read -r p r d; do printf '    disable  %-45s [%s] %s\n' "$p" "$r" "$d"; done
    [ "$m" = ai ] && rows "$LISTS/perms_ai.txt" | while IFS='|' read -r p perm; do printf '    revoke   %-45s %s\n' "$p" "$perm"; done
    [ "$m" = background ] && rows "$LISTS/background.txt" | while read -r p; do printf '    bg-block %s\n' "$p"; done
  done; echo; }

cmd_apply(){ [ $# -ge 1 ] || die "usage: apply <module...|recommended|all> [--dry-run]"
  preflight; local mods="" m
  for m in "$@"; do case "$m" in recommended) mods="$mods $RECOMMENDED";; all) mods="$mods $MODULES";; *) echo " $MODULES " | grep -q " $m " || die "unknown module '$m'. Modules: $MODULES"; mods="$mods $m";; esac; done
  [ "$DRY" = 1 ] && info "${Y}dry run: nothing will change${N}"
  acknowledge
  for m in $mods; do echo; info "${B}[$m]${N} $(describe "$m")"; jopen "$m"; "mod_$m"; sleep 1
    if [ "$DRY" = 0 ] && [ -f "$J" ]; then ok "rollback journal: ${J#$ROOT/}"; fi
  done; echo; [ "$DRY" = 1 ] || info "done. Undo anytime with: ./vnd.sh rollback --last   (or ./vnd.sh journals)"; }

replay(){ # undo one journal, newest change first
  local f="$1"; info "rolling back ${f#$ROOT/}"
  local lines; lines="$(grep -v '^#' "$f" | awk '{l[NR]=$0} END{for(i=NR;i>0;i--) print l[i]}')"
  echo "$lines" | while IFS='|' read -r kind x y z; do case "$kind" in
    setting) if [ "$z" = null ] || [ -z "$z" ]; then sh_ settings delete "$x" "$y" >/dev/null; else sh_ settings put "$x" "$y" "$z"; fi; ok "restored $x/$y = $z";;
    pkg) sh_ pm enable --user 0 "$x" >/dev/null && ok "re-enabled $x";;
    pkgoff) sh_ pm disable-user --user 0 "$x" >/dev/null && ok "disabled $x again";;
    perm) sh_ pm grant "$x" "$y" 2>/dev/null; ok "microphone granted back to $x";;
    appop) sh_ cmd appops set "$x" "$y" "$z" && ok "background $z for $x";;
    home) sh_ cmd package set-home-activity --user 0 "$x" >/dev/null && ok "home restored to $x";;
    uninstall) a uninstall "$x" >/dev/null 2>&1 && ok "uninstalled $x";;
    "") ;;
  esac; done
  mv "$f" "$f.undone"; }

# ---------- configurator ----------
# Every item VND manages, with its live state. T = tuned (debloated), S = stock, - = not installed.
IK=(); IM=(); IA=(); IB=(); IV=(); IS=(); IR=(); ID=(); CUR=(); DES=(); NI=0
load_items(){
  local f kind ns k v st d p r mod
  NI=0
  for mod in $MODULES; do
    if [ -f "$LISTS/settings_$mod.txt" ]; then
      while IFS='|' read -r ns k v st d; do
        r=safe; [ "$mod" = ai ] && r=moderate
        IK[NI]=setting; IM[NI]=$mod; IA[NI]=$ns; IB[NI]=$k; IV[NI]=$v; IS[NI]=$st; IR[NI]=$r; ID[NI]=$d; NI=$((NI+1))
      done < <(rows "$LISTS/settings_$mod.txt"); fi
    if [ -f "$LISTS/pkg_$mod.txt" ]; then
      while IFS='|' read -r p r d; do
        IK[NI]=pkg; IM[NI]=$mod; IA[NI]=$p; IB[NI]=; IV[NI]=; IS[NI]=; IR[NI]=$r; ID[NI]=$d; NI=$((NI+1))
      done < <(rows "$LISTS/pkg_$mod.txt"); fi
    if [ "$mod" = background ]; then
      while read -r p; do
        IK[NI]=bg; IM[NI]=background; IA[NI]=$p; IB[NI]=; IV[NI]=; IS[NI]=; IR[NI]=trade-off; ID[NI]="Blocked from running in the background (late notifications)"; NI=$((NI+1))
      done < <(rows "$LISTS/background.txt"); fi
  done; }

read_state(){ # live device state for every item
  local all dis i cur
  all="$(sh_ pm list packages)"; dis="$(sh_ pm list packages -d)"
  for ((i=0;i<NI;i++)); do
    case "${IK[i]}" in
      setting) cur="$(sh_ settings get "${IA[i]}" "${IB[i]}")"
        if [ "$cur" = "${IV[i]}" ] || { [ "${IV[i]}" = "-" ] && { [ "$cur" = null ] || [ -z "$cur" ]; }; }; then CUR[i]=T; else CUR[i]=S; fi;;
      pkg) if ! echo "$all" | grep -qx "package:${IA[i]}"; then CUR[i]=-
           elif echo "$dis" | grep -qx "package:${IA[i]}"; then CUR[i]=T; else CUR[i]=S; fi;;
      bg) if ! echo "$all" | grep -qx "package:${IA[i]}"; then CUR[i]=-
          elif sh_ cmd appops get "${IA[i]}" RUN_ANY_IN_BACKGROUND | grep -q ignore; then CUR[i]=T; else CUR[i]=S; fi;;
    esac
    DES[i]=${CUR[i]}
  done; }

item_id(){ case "${IK[$1]}" in setting) echo "${IA[$1]}/${IB[$1]}";; *) echo "${IA[$1]}";; esac; }

show_items(){
  local i last="" mark st
  printf '\n  %s\n' "${B}#   now      risk        item${N}"
  for ((i=0;i<NI;i++)); do
    [ "${IM[i]}" != "$last" ] && { last=${IM[i]}; printf '\n  %s\n' "${C}${B}$last${N}  $(describe "$last")"; }
    case "${DES[i]}" in T) st="${G}tuned${N}";; S) st="${Y}stock${N}";; -) st="n/a  ";; esac
    mark=" "; [ "${DES[i]}" != "${CUR[i]}" ] && mark="${B}*${N}"
    printf '  %-3s %s%s  %-11s %-44s %s\n' "$((i+1))" "$mark" "$st" "${IR[i]}" "$(item_id "$i")" "${ID[i]}"
  done
  local n=0; for ((i=0;i<NI;i++)); do [ "${DES[i]}" != "${CUR[i]}" ] && n=$((n+1)); done
  printf '\n  %s pending change(s), marked *\n' "$n"; }

set_des(){ [ "${CUR[$1]}" = - ] && return; DES[$1]=$2; }
toggle(){ [ "${CUR[$1]}" = - ] && return; [ "${DES[$1]}" = T ] && DES[$1]=S || DES[$1]=T; }

apply_item(){ # i -> make device match DES[i], journaled
  local i=$1 p=${IA[$1]} perm old
  case "${IK[i]}:${DES[i]}" in
    setting:T) set_setting "${IA[i]}" "${IB[i]}" "${IV[i]}" "${ID[i]}";;
    setting:S) set_setting "${IA[i]}" "${IB[i]}" "${IS[i]}" "back to stock";;
    pkg:T) disable_pkg "$p" "${IR[i]}" "${ID[i]}"
      [ "$DRY" = 1 ] || rows "$LISTS/perms_ai.txt" | while IFS='|' read -r pp perm; do
        [ "$pp" = "$p" ] && sh_ pm revoke "$p" "$perm" 2>/dev/null && jw "perm|$p|$perm"; done;;
    pkg:S) if [ "$DRY" = 1 ]; then echo "  would re-enable $p"; else
        sh_ pm enable --user 0 "$p" >/dev/null && { jw "pkgoff|$p"; ok "re-enabled $p"; }
        rows "$LISTS/perms_ai.txt" | while IFS='|' read -r pp perm; do [ "$pp" = "$p" ] && sh_ pm grant "$p" "$perm" 2>/dev/null; done; fi;;
    bg:T|bg:S) local want=ignore; [ "${DES[i]}" = S ] && want=allow
      if [ "$DRY" = 1 ]; then echo "  would set background=$want for $p"; else
        old="$(sh_ cmd appops get "$p" RUN_ANY_IN_BACKGROUND | grep -oE 'allow|ignore|deny' | head -1)"
        sh_ cmd appops set "$p" RUN_ANY_IN_BACKGROUND "$want" && { jw "appop|$p|RUN_ANY_IN_BACKGROUND|${old:-allow}"; ok "background $want: $p"; }; fi;;
  esac; }

apply_pending(){
  local i n=0; for ((i=0;i<NI;i++)); do [ "${DES[i]}" != "${CUR[i]}" ] && n=$((n+1)); done
  [ "$n" = 0 ] && { info "nothing to change"; return 0; }
  echo; info "${B}$n change(s)${N}"
  for ((i=0;i<NI;i++)); do [ "${DES[i]}" != "${CUR[i]}" ] && printf '    %-6s %s\n' "$([ "${DES[i]}" = T ] && echo tune || echo stock)" "$(item_id "$i")"; done
  [ "$DRY" = 1 ] && { info "${Y}dry run: nothing changed${N}"; return 0; }
  acknowledge; confirm "Apply these changes?" || { warn "not applied"; return 0; }
  jopen configure
  for ((i=0;i<NI;i++)); do [ "${DES[i]}" != "${CUR[i]}" ] && apply_item "$i"; done
  [ -f "$J" ] && ok "rollback journal: ${J#$ROOT/}"
  read_state; }

export_config(){ # file
  { echo "# VND configuration ($(date '+%Y-%m-%d'))  T = tuned (debloated)  S = stock"
    echo "# Edit the first letter of any line, then: ./vnd.sh configure --import <file> [--dry-run]"
    local i last=""
    for ((i=0;i<NI;i++)); do [ "${CUR[i]}" = - ] && continue
      [ "${IM[i]}" != "$last" ] && { last=${IM[i]}; echo; echo "# --- $last: $(describe "$last")"; }
      printf '%s %-12s %-46s # [%s] %s\n' "${DES[i]}" "${IM[i]}" "$(item_id "$i")" "${IR[i]}" "${ID[i]}"
    done; } > "$1"; ok "configuration written to $1"; }

import_config(){ # file
  [ -f "$1" ] || die "no such file: $1"
  local s m id rest i hits=0
  while read -r s m id rest; do
    case "$s" in T|S) ;; *) continue;; esac
    for ((i=0;i<NI;i++)); do [ "$(item_id "$i")" = "$id" ] && { set_des "$i" "$s"; hits=$((hits+1)); }; done
  done < "$1"; ok "$hits item(s) read from $1"; }

cmd_configure(){ preflight
  info "reading the device state..."; load_items; read_state
  case "${1:-}" in
    --export) export_config "${2:-vnd.conf}"; return;;
    --import) import_config "${2:?usage: configure --import <file>}"; apply_pending; return;;
  esac
  local line w x i a b
  while true; do
    show_items
    cat <<EOM

  ${B}3 7 12${N} or ${B}4-9${N} toggle items     ${B}tune <module>${N} / ${B}stock <module>${N} a whole module
  ${B}rec${N} recommended set     ${B}stock all${N}     ${B}reset${N} discard pending     ${B}export <file>${N}
  ${B}apply${N} make the device match     ${B}q${N} quit
EOM
    printf '  > '; read -r line || break
    set -- $line; w="${1:-}"; x="${2:-}"
    case "$w" in
      q|quit|exit) break;;
      apply|a) apply_pending; printf '  press Enter '; read -r _;;
      reset) for ((i=0;i<NI;i++)); do DES[i]=${CUR[i]}; done;;
      rec|recommended) for ((i=0;i<NI;i++)); do echo " $RECOMMENDED " | grep -q " ${IM[i]} " && set_des "$i" T; done;;
      tune|stock) for ((i=0;i<NI;i++)); do { [ "$x" = all ] || [ "${IM[i]}" = "$x" ]; } && set_des "$i" "$([ "$w" = tune ] && echo T || echo S)"; done;;
      export) export_config "${x:-vnd.conf}"; printf '  press Enter '; read -r _;;
      *) for a in $line; do
           case "$a" in
             *-*) b=${a#*-}; a=${a%-*}; case "$a$b" in *[!0-9]*|"") continue;; esac
                  for ((i=a;i<=b;i++)); do [ "$i" -ge 1 ] && [ "$i" -le "$NI" ] && toggle $((i-1)); done;;
             *[!0-9]*|"") ;;
             *) [ "$a" -ge 1 ] && [ "$a" -le "$NI" ] && toggle $((a-1));;
           esac; done;;
    esac
  done; }

# ---------- battery test ----------
BT_DEV_SCRIPT=/data/local/tmp/vnd_batlog.sh
BT_DEV_CSV=/sdcard/Download/vnd_battery_test.csv
BT_DEV_STOP=/data/local/tmp/vnd_batlog.stop
bt_script(){ cat <<'EOS'
#!/system/bin/sh
# VND battery test logger: one CSV line per minute, runs on the neckband, survives adb disconnects.
OUT=/sdcard/Download/vnd_battery_test.csv
[ -f $OUT ] || echo "time,uptime_s,level,voltage_mv,temp_c,charge_uah,plugged,screen,brightness,head_gaze,gestures,foreground" > $OUT
rm -f /data/local/tmp/vnd_batlog.stop
while [ ! -f /data/local/tmp/vnd_batlog.stop ]; do
  B=$(dumpsys battery)
  lvl=$(echo "$B" | grep ' level:' | tr -dc 0-9); v=$(echo "$B" | grep ' voltage:' | tr -dc 0-9); t=$(echo "$B" | grep ' temperature:' | tr -dc 0-9)
  cc=$(echo "$B" | grep 'Charge counter' | tr -dc 0-9); pl=$(echo "$B" | grep -cE 'powered: true')
  scr=$(dumpsys power | grep -m1 'mWakefulness=' | cut -d= -f2)
  br=$(settings get system screen_brightness); hg=$(settings get global viture_settings_enable_head_gaze); ge=$(settings get global eden_gesture_mode)
  fg=$(dumpsys activity activities | grep -m1 topResumedActivity | grep -oE 'u0 [^/ ]+' | cut -c4-)
  echo "$(date '+%Y-%m-%d %H:%M:%S'),$(cut -d. -f1 /proc/uptime),$lvl,$v,$((t/10)),$cc,$pl,$scr,$br,$hg,$ge,$fg" >> $OUT
  sleep 60
done
EOS
}
bt_running(){ sh_ 'ps -A -o ARGS' | grep -q "^sh $BT_DEV_SCRIPT"; }

cmd_battery_test(){ preflight; case "${1:-}" in
  start)
    bt_running && die "a battery test is already running (battery-test status)"
    local tmp="$STATE/vnd_batlog.sh"; bt_script > "$tmp"
    a push "$tmp" "$BT_DEV_SCRIPT" >/dev/null || die "could not copy the logger"
    if [ "${2:-}" != "--keep" ]; then sh_ rm -f "$BT_DEV_CSV"; sh_ dumpsys batterystats --reset >/dev/null; fi
    sh_ "chmod 755 $BT_DEV_SCRIPT; nohup setsid $BT_DEV_SCRIPT >/dev/null 2>&1 </dev/null &"
    sleep 3; bt_running || die "logger did not start"
    ok "battery test running on the neckband, one line per minute in $BT_DEV_CSV"
    cat <<EOM
  Protocol for a comparable result:
    1. Charge to 100%, then unplug. The report counts from the first minute unplugged at 100%.
    2. Use the device the way you normally do (for example streaming), glasses on, same brightness throughout.
    3. Let it run until it shuts down. The logger stops with the device; nothing else is needed.
    4. After restarting: ./vnd.sh connect <ip:port> && ./vnd.sh battery-test report
  The logger keeps running if wireless debugging disconnects.
EOM
    ;;
  status)
    if bt_running; then ok "logger running"; else warn "logger not running"; fi
    sh_ "wc -l < $BT_DEV_CSV; tail -3 $BT_DEV_CSV" 2>/dev/null | sed 's/^/  /';;
  stop) sh_ "touch $BT_DEV_STOP"; ok "logger will stop within a minute";;
  report)
    local out="$STATE/battery_test_$(date +%Y%m%d-%H%M%S).csv"
    a pull "$BT_DEV_CSV" "$out" >/dev/null 2>&1 || die "no battery test log on the device"
    ok "log copied to ${out#$ROOT/}"; bt_report "$out";;
  *) die "usage: battery-test start [--keep] | status | stop | report [file]";;
  esac; }

bt_report(){ # csv -> summary (the csv is kept locally; it contains no personal data beyond app names)
  awk -F, 'NR==1{next}
    { n++; L[n]=$3+0; U[n]=$2+0; P[n]=$7+0; T[n]=$5+0; S[n]=$8; H[n]=$10; G[n]=$11; F[n]=$12; TM[n]=$1 }
    END{
      if(n<3){print "  not enough data yet"; exit}
      s=0; for(i=1;i<=n;i++) if(P[i]==0 && L[i]==100){s=i;break}
      full=1; if(!s){ for(i=1;i<=n;i++) if(P[i]==0){s=i;break}; full=0 }
      if(!s){print "  the device has not been unplugged yet"; exit}
      e=s; for(i=s+1;i<=n;i++){ if(P[i]!=0 || U[i]<U[i-1]) break; e=i }
      dur=(U[e]-U[s])/3600; drop=L[s]-L[e]
      printf "  start      %s at %d%%%s\n", TM[s], L[s], full?"":"  (did not start at 100%: result is extrapolated)"
      printf "  end        %s at %d%%  (last logged minute)\n", TM[e], L[e]
      printf "  duration   %dh %02dm for %d%% of battery\n", int(dur), int((dur-int(dur))*60+0.5), drop
      if(drop>0 && dur>0){ r=drop/dur; f=100/r
        printf "  drain      %.1f%% per hour\n", r
        printf "  projected  %dh %02dm from 100%% to 0%%\n", int(f), int((f-int(f))*60+0.5) }
      aw=0; hg=0; ge=0; tsum=0; tmax=0
      for(i=s;i<=e;i++){ if(S[i]=="Awake")aw++; if(H[i]=="1")hg++; if(G[i]=="1")ge++; tsum+=T[i]; if(T[i]>tmax)tmax=T[i]; fc[F[i]]++ }
      m=e-s+1
      printf "  screen on  %d%% of the time   head gaze on %d%%   hand gestures on %d%%\n", 100*aw/m, 100*hg/m, 100*ge/m
      printf "  battery    %.0f C average, %d C peak\n", tsum/m, tmax
      printf "  milestones"; for(k=90;k>=0;k-=10) for(i=s;i<=e;i++) if(L[i]<=k){ h=(U[i]-U[s])/3600; printf "  %d%%:%dh%02d", k, int(h), int((h-int(h))*60); break }; printf "\n"
      printf "  foreground (minutes):"; for(a in fc) printf " %s=%d", a, fc[a]; printf "\n"
    }' "$1"; }

cmd_journals(){ ls -1 "$JOURNALS" 2>/dev/null | sed 's/^/  /' || true; }
cmd_rollback(){ preflight
  case "${1:-}" in
    --last) local f; f="$(ls -1t "$JOURNALS"/*.journal 2>/dev/null | head -1)"; [ -n "$f" ] || die "no journal to roll back"; replay "$f";;
    --all) local f; for f in $(ls -1t "$JOURNALS"/*.journal 2>/dev/null); do replay "$f"; done;;
    "") die "usage: rollback --last | --all | <journal-file>   (list them with: ./vnd.sh journals)";;
    *) local f="$1"; [ -f "$f" ] || f="$JOURNALS/$1"; [ -f "$f" ] || die "journal not found: $1"; replay "$f";;
  esac; }

cmd_restore_stock(){ preflight
  confirm "Restore every setting VND knows to Viture's stock value and re-enable every listed app?" || exit 0
  local f
  for f in "$LISTS"/settings_*.txt; do rows "$f" | while IFS='|' read -r ns k v st d; do
    if [ "$st" = "-" ]; then sh_ settings delete "$ns" "$k" >/dev/null; else sh_ settings put "$ns" "$k" "$st"; fi; ok "$ns/$k = $st"; done; done
  for f in "$LISTS"/pkg_*.txt; do rows "$f" | while IFS='|' read -r p r d; do installed "$p" && sh_ pm enable --user 0 "$p" >/dev/null && ok "enabled $p"; done; done
  rows "$LISTS/perms_ai.txt" | while IFS='|' read -r p perm; do sh_ pm grant "$p" "$perm" 2>/dev/null; done
  rows "$LISTS/background.txt" | while read -r p; do installed "$p" && sh_ cmd appops set "$p" RUN_ANY_IN_BACKGROUND allow; done
  if installed "$TVHOME_PKG"; then cmd_tvhome uninstall; fi
  for f in "$JOURNALS"/*.journal; do [ -f "$f" ] && mv "$f" "$f.undone"; done
  info "stock state restored. A reboot is recommended."; }

cmd_tvhome(){ preflight; case "${1:-}" in
  install)
    acknowledge
    [ -f "$TVHOME_APK" ] || die "missing $TVHOME_APK (build it with tvhome/build.sh)"
    installed com.google.android.videos || die "Google TV (com.google.android.videos) is not installed"
    jopen tvhome
    a install -r "$TVHOME_APK" 2>&1 | grep -q Success || { warn "update refused (different signature?), reinstalling"; a uninstall "$TVHOME_PKG" >/dev/null 2>&1; a install "$TVHOME_APK" | grep -q Success || die "install failed"; }
    jw "uninstall|$TVHOME_PKG"
    sh_ pm grant "$TVHOME_PKG" android.permission.WRITE_SECURE_SETTINGS
    sh_ appops set "$TVHOME_PKG" GET_USAGE_STATS allow
    sh_ appops set "$TVHOME_PKG" SYSTEM_ALERT_WINDOW allow
    local k v; for k in $TVHOME_PREFS; do v="$(sh_ settings get global "$k")"; jw "setting|global|$k|$v"; done
    local cur; cur="$(sh_ cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)"
    case "$cur" in "$TVHOME_HOME"|*vhome*) cur="$STOCK_HOME";; esac
    jw "home|$cur"
    sh_ cmd package set-home-activity --user 0 "$TVHOME_HOME" >/dev/null
    sh_ cmd statusbar add-tile "$TVHOME_TILE" >/dev/null 2>&1
    local acc accon; acc="$(sh_ settings get secure enabled_accessibility_services)"; accon="$(sh_ settings get secure accessibility_enabled)"
    case "$acc" in *ControlService*) ;; *)
      jw "setting|secure|enabled_accessibility_services|$acc"; jw "setting|secure|accessibility_enabled|$accon"
      if [ -z "$acc" ] || [ "$acc" = null ]; then acc="$TVHOME_A11Y"; else acc="$acc:$TVHOME_A11Y"; fi
      sh_ settings put secure enabled_accessibility_services "$acc"; sh_ settings put secure accessibility_enabled 1;; esac
    local fl; fl="$(sh_ settings get global vnd_tvhome_controls)"; jw "setting|global|vnd_tvhome_controls|$fl"
    sh_ settings put global vnd_tvhome_controls 1
    ok "TV Home installed: Home opens Google TV, Home again opens the app list"
    ok "\"Head gaze\" button added to quick settings; head gaze is switched on at every boot"
    ok "floating control button enabled: Close app, Close all, Notifications, Volume, Head gaze, Settings"
    ok "rollback journal: ${J#$ROOT/}";;
  uninstall)
    sh_ cmd package set-home-activity --user 0 "$STOCK_HOME" >/dev/null; sh_ cmd statusbar remove-tile "$TVHOME_TILE" >/dev/null 2>&1
    local acc; acc="$(sh_ settings get secure enabled_accessibility_services | tr ':' '\n' | grep -v ControlService | paste -sd: -)"
    if [ -z "$acc" ]; then sh_ settings delete secure enabled_accessibility_services >/dev/null; else sh_ settings put secure enabled_accessibility_services "$acc"; fi
    sh_ settings delete global vnd_tvhome_controls >/dev/null
    for k in $TVHOME_PREFS; do sh_ settings delete global "$k" >/dev/null; done
    a uninstall "$TVHOME_PKG" >/dev/null 2>&1; ok "TV Home removed, stock launcher is home";;
  *) die "usage: tvhome install | uninstall";;
  esac; }

cmd_analyze(){ preflight
  local D="$SNAPS/$(date +%Y%m%d-%H%M%S)"; mkdir -p "$D"; info "snapshot -> ${D#$ROOT/}"
  sh_ getprop > "$D/getprop.txt"
  for n in global system secure; do sh_ settings list "$n" > "$D/settings_$n.txt"; done
  sh_ pm list packages -f -U --show-versioncode > "$D/packages_all.txt"
  sh_ pm list packages -d > "$D/packages_disabled.txt"
  for s in battery batterystats meminfo cpuinfo thermalservice media.camera deviceidle; do sh_ dumpsys "$s" > "$D/dumpsys_$s.txt" 2>&1; done
  ok "saved settings, packages and system dumps"
  echo; info "${B}top processor time since the last battery reset${N} (\"Android System\" hides most of these)"
  awk 'function ms(s,  t,n,u){t=0; while(match(s,/[0-9]+(ms|h|m|s)/)){n=substr(s,RSTART,RLENGTH); u=n; gsub(/[0-9]/,"",u); n+=0;
         t+= (u=="h")?n*3600000:(u=="m")?n*60000:(u=="s")?n*1000:n; s=substr(s,RSTART+RLENGTH)}; return t}
       /^  (u0a[0-9]+|[0-9]+):[[:space:]]*$/{uid=$1}
       /^[[:space:]]+Proc .+:$/{p=$0; sub(/^[[:space:]]+Proc /,"",p); sub(/:$/,"",p); getline; if($0 ~ /CPU:/){split($0,x,"usr \\+ "); u=x[1]; sub(/.*CPU: /,"",u); k=x[2]; sub(/ krn.*/,"",k); v[uid" "p]+=ms(u)+ms(k)}}
       END{for(i in v) printf "%8.1f min  %s\n", v[i]/60000, i}' "$D/dumpsys_batterystats.txt" | sort -rn | head -15
  echo; info "${B}hardware energy since reset${N}"; grep -A12 'Estimated power use' "$D/dumpsys_batterystats.txt" | grep -E ' (screen|cpu|camera|wifi|bluetooth|audio|video): [0-9]|Capacity' | sed 's/^ */  /'
  local cam; cam="$(grep -cE 'CONNECT device' "$D/dumpsys_media.camera.txt")"; echo; info "camera sessions in log: $cam   (gesture tracking uses the camera)"
  echo; info "${B}live 20-second CPU sample${N}"
  sh_ 'snap(){ for p in /proc/[0-9]*; do read -r l < $p/stat 2>/dev/null && echo "$l"; done | awk "{n=\$2; for(i=3;i<=NF;i++) if(\$i ~ /^[RSDZTI]\$/){s=i;break}; print \$1, \$(s+11)+\$(s+12), n}"; }; snap > /data/local/tmp/v1; sleep 20; snap > /data/local/tmp/v2; awk "NR==FNR{a[\$1]=\$2;next} (\$1 in a){d=\$2-a[\$1]; if(d>10) printf \"  %5.1f%% of one core  %s\n\", d/20, \$3}" /data/local/tmp/v1 /data/local/tmp/v2 | sort -rn | head -10; rm -f /data/local/tmp/v1 /data/local/tmp/v2'
  echo; info "tip: ./vnd.sh reset-stats, use the device normally for an hour, then analyze again for a clean reading"; }

cmd_reset_stats(){ preflight; sh_ dumpsys batterystats --reset; ok "battery statistics reset (Android's estimate settles after ~30 minutes of use)"; }

cmd_backup(){ preflight; local D="$BACKUPS/$(date +%Y%m%d-%H%M%S)"; mkdir -p "$D/apks" "$D/info"; info "backup -> ${D#$ROOT/}"
  sh_ getprop > "$D/info/getprop.txt"; for n in global system secure; do sh_ settings list "$n" > "$D/info/settings_$n.txt"; done
  sh_ pm list packages -f -U --show-versioncode > "$D/info/packages_all.txt"; sh_ pm list packages -d > "$D/info/packages_disabled.txt"
  for p in $(sh_ pm list packages -3 | cut -d: -f2); do mkdir -p "$D/apks/$p"; for f in $(sh_ pm path "$p" | cut -d: -f2); do a pull "$f" "$D/apks/$p/" >/dev/null 2>&1; done; ok "apk $p"; done
  [ "${1:-}" = "--no-media" ] || { info "copying shared storage (photos, downloads...)"; a pull /sdcard/ "$D/sdcard/" >/dev/null && ok "shared storage copied"; }
  info "done. App data (logins) cannot be backed up without root; reinstalling an apk restores the app, not its data."; }

usage(){ cat <<EOF
${B}VND $VERSION - Viture Neckband Debloater${N}   no root, no uninstalls, everything reversible
Unofficial community tool, not affiliated with VITURE. No warranty: read DISCLAIMER.md.

  ${B}Setup${N}
    pair <ip:port> <code>      Pair once (Wireless debugging > Pair device with pairing code)
    connect <ip:port>          Connect and save the target (the port changes after sleep/reboot)

  ${B}Look${N}
    status                     What is applied, module by module
    list [module]              Every change with its stock value and risk rating
    analyze                    Snapshot + find what drains the battery
    reset-stats                Reset battery statistics for a clean measurement

  ${B}Change${N}
    configure                  Interactive configurator: pick each service/setting, review, apply
    configure --export <f>     Save the device's current choices to an editable text file
    configure --import <f>     Make the device match a configuration file (with --dry-run to preview)
    apply recommended          radios ui bloat ai startup gestures
    apply <module...>          $MODULES
    apply all                  Everything, including background + experimental
    tvhome install|uninstall   Google TV home, Head gaze tile, floating controls (close apps, notifications, volume)

  ${B}Undo${N}
    journals                   List rollback journals
    rollback --last|--all|<j>  Undo one run, or everything, newest first
    restore-stock              Put every known setting back to Viture's stock value

  ${B}Safety${N}
    backup [--no-media]        Settings, app list, user apks, shared storage

  ${B}Measure${N}
    battery-test start         Log battery every minute on the device, from 100% to shutdown
    battery-test status|stop   Check or stop the logger
    battery-test report        Runtime, drain per hour, milestones, conditions

  Flags: --dry-run (show, change nothing)  --yes (no prompts)  --force (skip model check)
EOF
}

# ---------- main ----------
ARGS=()
for x in "$@"; do case "$x" in --dry-run) DRY=1;; --yes|-y) YES=1;; --force) FORCE=1;; *) ARGS+=("$x");; esac; done
set -- ${ARGS[@]+"${ARGS[@]}"}
c="${1:-help}"; shift || true
case "$c" in
  pair) cmd_pair "$@";; connect) cmd_connect "$@";; status) cmd_status;; list) cmd_list "$@";;
  analyze) cmd_analyze;; reset-stats) cmd_reset_stats;; apply) cmd_apply "$@";; configure|config) cmd_configure "$@";; tvhome) cmd_tvhome "$@";;
  journals) cmd_journals;; rollback) cmd_rollback "$@";; restore-stock) cmd_restore_stock;; battery-test) cmd_battery_test "$@";; backup) cmd_backup "$@";;
  version|--version) echo "$VERSION";; help|-h|--help) usage;; *) usage; exit 1;;
esac
