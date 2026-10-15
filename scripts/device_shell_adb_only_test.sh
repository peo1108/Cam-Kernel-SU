#!/usr/bin/env bash
# Device test of the shell_su_adb_only feature (Settings: "only Shell over ADB gets root"):
# with it on, a granted shell gets root only when adbd (or a root process in the cam/ksu domain)
# is among its ancestors. An orphaned shell process, reparented to init, stands in for code that
# runs as the shell uid without an adb session (e.g. through a system service bug).
#
#   scripts/device_shell_adb_only_test.sh [-s SERIAL]
#
# Needs root granted to Shell. Changes the feature in the kernel only (camd feature set, not
# saved) and puts it back as it was, even when interrupted.
set -u
export MSYS_NO_PATHCONV=1

ADB=(adb)
command -v adb >/dev/null || ADB=("${LOCALAPPDATA:-}/Android/Sdk/platform-tools/adb.exe")
if [ "${1:-}" = "-s" ] && [ -n "${2:-}" ]; then
    ADB+=(-s "$2")
fi

CAMD=/data/adb/camd
OUT=/data/local/tmp/cam_adb_only.out
pass=0
fail=0
ok() { pass=$((pass + 1)); echo "PASS  $1"; }
bad() { fail=$((fail + 1)); echo "FAIL  $1"; [ -n "${2:-}" ] && printf '      %s\n' "$2"; }

feature() { "${ADB[@]}" shell "su -c '$CAMD feature $*'" 2>&1 | tr -d '\r'; }
value() { feature get shell_su_adb_only | sed -n 's/^Value: //p'; }

# A shell-uid process detached from adb: setsid + background, then the adb session ends and it
# is reparented to init. Prints its parent pid and what `su -c id` gave.
orphan_su() {
    "${ADB[@]}" shell "rm -f $OUT; setsid sh -c 'sleep 2; echo ppid=\$(cut -d\" \" -f4 /proc/\$\$/stat); su -c id; echo rc=\$?' > $OUT 2>&1 < /dev/null &"
    sleep 4
    "${ADB[@]}" shell "cat $OUT" | tr -d '\r'
}

if ! "${ADB[@]}" shell "su -c id" 2>/dev/null | grep -q 'uid=0'; then
    echo "su from adb shell does not give root: grant root to Shell in the Manager" >&2
    exit 2
fi

before=$(value)
[ -n "$before" ] || { echo "camd feature get shell_su_adb_only failed" >&2; exit 2; }
restore() {
    feature set shell_su_adb_only "$before" >/dev/null
    "${ADB[@]}" shell "rm -f $OUT" >/dev/null 2>&1
}
trap restore EXIT INT TERM

feature set shell_su_adb_only 0 >/dev/null
out=$(orphan_su)
if grep -q 'ppid=1$' <<<"$out" && grep -q 'uid=0' <<<"$out"; then
    ok "off: an orphaned shell process gets root"
else
    bad "off: an orphaned shell process gets root" "$(tr '\n' ' ' <<<"$out")"
fi

feature set shell_su_adb_only 1 >/dev/null
if "${ADB[@]}" shell "su -c id" | grep -q 'uid=0'; then
    ok "on: adb shell still gets root"
else
    bad "on: adb shell still gets root"
fi
out=$(orphan_su)
if grep -q 'ppid=1$' <<<"$out" && ! grep -q 'uid=0' <<<"$out"; then
    ok "on: an orphaned shell process is refused"
else
    bad "on: an orphaned shell process is refused" "$(tr '\n' ' ' <<<"$out")"
fi
if "${ADB[@]}" shell "su -c 'su 2000 -c \"su -c id\"'" | grep -q 'uid=0'; then
    ok "on: shell started by a root process gets root (ksu domain ancestor)"
else
    bad "on: shell started by a root process gets root (ksu domain ancestor)"
fi

restore
trap - EXIT INT TERM
if [ "$(value)" = "$before" ]; then
    ok "feature back to $before"
else
    bad "feature back to $before" "now $(value)"
fi

echo "== $pass passed, $fail failed"
[ "$fail" = 0 ]
