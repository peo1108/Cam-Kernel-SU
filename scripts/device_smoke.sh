#!/usr/bin/env bash
# Smoke test of an installed Cam Kernel SU on a device over adb, the part CI does not
# cover (CI builds the LKM but never loads it). Run it by hand after flashing or an OTA:
#
#   scripts/device_smoke.sh            # the only device attached
#   scripts/device_smoke.sh -s SERIAL  # pick one
#
# Needs: adb, python (to check the JSON), and root granted to Shell in the Manager.
# It only reads state; nothing on the device is changed.
set -u

ADB=(adb)
if [ "${1:-}" = "-s" ] && [ -n "${2:-}" ]; then
    ADB=(adb -s "$2")
fi

CAMD=/data/adb/camd
PKG=cam.su.kernel
FEATURES="su_compat kernel_umount sulog adb_root selinux_hide shell_su_adb_only"
pass=0
fail=0

ok() { pass=$((pass + 1)); echo "PASS  $1"; }
bad() { fail=$((fail + 1)); echo "FAIL  $1"; [ -n "${2:-}" ] && printf '      %s\n' "$2"; }

# adb shell drops the exit status on old adbd, so the status is echoed after the output
root() {
    "${ADB[@]}" shell "su -c '$1; echo __rc=\$?'" 2>&1 | tr -d '\r'
}
rc_of() { sed -n 's/^__rc=//p' <<<"$1" | tail -n 1; }
body_of() { sed '/^__rc=/d' <<<"$1"; }

is_json() { "$PY" -c 'import json,sys; json.load(sys.stdin)' >/dev/null 2>&1 <<<"$1"; }

check_json() {
    local name=$1 cmd=$2 out
    out=$(root "$CAMD $cmd")
    if [ "$(rc_of "$out")" = 0 ] && is_json "$(body_of "$out")"; then
        ok "$name"
    else
        bad "$name" "$(body_of "$out" | head -n 3)"
    fi
}

if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
    echo "no device: connect one, or pick it with -s SERIAL" >&2
    exit 2
fi
# Windows has a python3 stub that only opens the Store, so try each name for real
PY=
for p in python3 python; do
    if "$p" -c '' >/dev/null 2>&1; then
        PY=$p
        break
    fi
done
[ -n "$PY" ] || { echo "python is needed to check the JSON" >&2; exit 2; }

echo "== device: $("${ADB[@]}" shell getprop ro.product.model | tr -d '\r'), Android $("${ADB[@]}" shell getprop ro.build.version.release | tr -d '\r')"

if "${ADB[@]}" shell pm path "$PKG" | grep -q '^package:'; then
    ok "Manager $PKG installed"
else
    bad "Manager $PKG installed"
fi

out=$(root id)
if grep -q 'uid=0' <<<"$out"; then
    ok "su from adb shell gives uid 0"
else
    bad "su from adb shell gives uid 0" "grant root to Shell in the Manager, then run again"
    echo "== $pass passed, $fail failed"
    exit 1
fi

out=$(root "$CAMD debug info")
info=$(body_of "$out")
if [ "$(rc_of "$out")" = 0 ] && grep -q '^version: [1-9]' <<<"$info"; then
    ok "kernel answers ($(tr '\n' ' ' <<<"$info"))"
else
    bad "kernel answers" "$(head -n 3 <<<"$info")"
fi

for f in $FEATURES; do
    out=$(root "$CAMD feature check $f")
    if [ "$(rc_of "$out")" = 0 ]; then
        ok "feature $f: $(body_of "$out" | tr '\n' ' ')"
    else
        bad "feature $f" "$(body_of "$out" | head -n 2)"
    fi
done

check_json "camd hiding-audit" "hiding-audit"
check_json "camd boot-guard status" "boot-guard status"
check_json "camd hide-bootloader status" "hide-bootloader status"
check_json "camd module list" "module list"

# Block RKP was removed in 1.1.5: the Manager's `camd install` clears what it left
out=$(root "test -e /data/adb/cam/.block_rkp; echo flag=\$?; iptables -w -C OUTPUT ! -o lo -j DROP 2>/dev/null; echo rule=\$?")
if grep -q 'flag=1' <<<"$out" && grep -q 'rule=1' <<<"$out"; then
    ok "no Block RKP flag or DROP rule left"
else
    bad "no Block RKP flag or DROP rule left" "open the Manager once (it runs camd install), then check again"
fi

echo "== $pass passed, $fail failed"
[ "$fail" = 0 ]
