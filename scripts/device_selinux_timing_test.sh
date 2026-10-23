#!/usr/bin/env bash
# Device test of the SELinux hide hook's attr/current timing (kernel/feature/selinux_hide.c,
# my_setprocattr). Duck Detector's App Zygote timing probe writes two payloads that both fail
# with EINVAL: A names a type no policy has, B is A with a leading newline. Stock parses A and
# skips B; an app may not see the hook change how much more A costs than B.
#
#   scripts/device_selinux_timing_test.sh [-s SERIAL]
#
# The probe (scripts/selinux_timing_probe.c, built here with the Android NDK's clang) runs as
# u:r:app_zygote:s0 with an app uid, pinned to one CPU. The sign of A-B depends on the device,
# the CPU and its clock (stock can read either way), so the test compares the hook against stock
# on the same CPU: selinux_hide on, then off (the hook removed), three times each. Before the
# 2026-10-23 fix the hook made A ~110-260 ns slower than stock on a TB323FU; after it, 6-15 ns.
# It also checks what the hook is for: root contexts unknown with hiding on, known with it off,
# and a stock transition still allowed.
#
# Known residual, not measured here: a context valid in both policies (a successful attr/current
# transition) is parsed twice by the hook (backup to decide hiding, live for the transition SID)
# where stock parses once. It cannot be dropped without ceasing to hide, and a successful write
# mutates the thread's creds, so it does not micro-time in a tight loop like the invalid payloads.
# The context/access selinuxfs writes no longer double-parse (they discard the live result).
#
# Needs root granted to Shell and the NDK (NDK_CLANG=<clang> to pick one). Changes selinux_hide
# in the kernel only (camd feature set, not saved) and puts it back, even when interrupted.
set -u
export MSYS_NO_PATHCONV=1

ADB=(adb)
command -v adb >/dev/null || ADB=("${LOCALAPPDATA:-}/Android/Sdk/platform-tools/adb.exe")
if [ "${1:-}" = "-s" ] && [ -n "${2:-}" ]; then
    ADB+=(-s "$2")
fi

CAMD=/data/adb/camd
PROBE=/data/local/tmp/cam_selinux_timing
PAIRS=20000
LIMIT=50 # ns the hook may add to A-B over stock
pass=0
fail=0
ok() { pass=$((pass + 1)); echo "PASS  $1"; }
bad() { fail=$((fail + 1)); echo "FAIL  $1"; [ -n "${2:-}" ] && printf '      %s\n' "$2"; }

feature() { "${ADB[@]}" shell "su -c '$CAMD feature $*'" 2>&1 | tr -d '\r'; }
value() { feature get selinux_hide | sed -n 's/^Value: //p'; }
probe() { "${ADB[@]}" shell "su -c '$PROBE $* 2>&1 | cat'" | tr -d '\r'; }

clang="${NDK_CLANG:-}"
if [ -z "$clang" ]; then
    for c in "${LOCALAPPDATA:-}"/Android/Sdk/ndk/*/toolchains/llvm/prebuilt/*/bin/clang{,.exe} \
        "${ANDROID_NDK_HOME:-/nonexistent}"/toolchains/llvm/prebuilt/*/bin/clang; do
        [ -x "$c" ] && clang="$c"
    done
fi
if [ -z "$clang" ]; then
    echo "no NDK clang found: set NDK_CLANG" >&2
    exit 2
fi
here=$(cd "$(dirname "$0")" && pwd)
bin=$(mktemp)
trap 'rm -f "$bin"' EXIT
# The Windows NDK's clang.exe and adb.exe want Windows paths.
native() { if command -v cygpath >/dev/null; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
# -DCAM_NR... so the selfattr mode builds even if this NDK's libc predates the syscall number
if ! "$clang" --target=aarch64-linux-android31 -O2 -DCAM_NR_LSM_SET_SELF_ATTR=460 \
    -o "$(native "$bin")" "$(native "$here/selinux_timing_probe.c")"; then
    echo "cannot build the probe" >&2
    exit 2
fi

if ! "${ADB[@]}" shell "su -c id" 2>/dev/null | grep -q 'uid=0'; then
    echo "su from adb shell does not give root: grant root to Shell in the Manager" >&2
    exit 2
fi
"${ADB[@]}" push "$(native "$bin")" "$PROBE" >/dev/null 2>&1 && "${ADB[@]}" shell "chmod 755 $PROBE" || exit 2

before=$(value)
if [ -z "$before" ]; then
    echo "camd feature get selinux_hide failed" >&2
    exit 2
fi
trap 'feature set selinux_hide "$before" >/dev/null; "${ADB[@]}" shell "rm -f $PROBE"; rm -f "$bin"' EXIT
trap 'exit 130' INT TERM

set_hide() { feature set selinux_hide "$1" >/dev/null; [ "$(value)" = "$1" ]; }
mean_of() { sed -n 's/.* mean=\([-0-9.]*\) .*/\1/p'; }
middle() { sort -n | sed -n 2p; } # of three

if ! set_hide 1; then
    bad "selinux_hide could not be turned on (no backup policy? save the feature and reboot)"
    exit 1
fi

# What hiding is for, before timing it.
out=$(probe write u:r:cam:s0)
case "$out" in *errno=22*) ok "hide on: u:r:cam:s0 is not a context (EINVAL)" ;; *) bad "hide on: u:r:cam:s0" "$out" ;; esac
out=$(probe write u:r:isolated_app:s0)
case "$out" in *now=u:r:isolated_app:s0*) ok "hide on: app_zygote -> isolated_app still allowed" ;; *) bad "hide on: app_zygote -> isolated_app" "$out" ;; esac
set_hide 0
out=$(probe write u:r:cam:s0)
case "$out" in *errno=13*) ok "hide off: u:r:cam:s0 is known (EACCES)" ;; *) bad "hide off: u:r:cam:s0" "$out" ;; esac

grew_of() { sed -n 's/.*grew=\(-\{0,1\}[0-9]\{1,\}\).*/\1/p'; }
# New contexts checked through selinuxfs grow the live sidtab; stock does, so the hook must too,
# or an app reads the gap in ss/sidtab_hash_stats (an app_zygote can read that file).
g=$(probe sidtab context 40)
if [ "$(grew_of <<<"$g")" -gt 0 ] 2>/dev/null; then ok "hide off: new contexts grow the live sidtab"; else bad "hide off: sidtab did not grow" "$g"; fi
set_hide 1
g=$(probe sidtab context 40)
if [ "$(grew_of <<<"$g")" -gt 0 ] 2>/dev/null; then ok "hide on: new contexts still grow the live sidtab"; else bad "hide on: sidtab did not grow, an app reads the gap" "$g"; fi
g=$(probe sidtab access 40)
if [ "$(grew_of <<<"$g")" -gt 0 ] 2>/dev/null; then ok "hide on: access checks still grow the live sidtab"; else bad "hide on: access sidtab did not grow" "$g"; fi
# lsm_set_self_attr(2) must hide like attr/current (kernel 6.8+); older kernels have no syscall.
out=$(probe selfattr u:r:cam:s0)
case "$out" in
*errno=38*) ok "hide on: lsm_set_self_attr not on this kernel, skipped" ;;
*errno=22*) ok "hide on: lsm_set_self_attr u:r:cam:s0 is not a context (EINVAL)" ;;
*) bad "hide on: lsm_set_self_attr u:r:cam:s0 leaks (want EINVAL)" "$out" ;;
esac
out=$(probe selfattr u:r:isolated_app:s0)
case "$out" in
*errno=38*) : ;;
*now=u:r:isolated_app:s0*) ok "hide on: lsm_set_self_attr app_zygote -> isolated_app still allowed" ;;
*) bad "hide on: lsm_set_self_attr app_zygote -> isolated_app" "$out" ;;
esac

possible=$("${ADB[@]}" shell cat /sys/devices/system/cpu/possible | tr -d '\r')
last=${possible##*-}
for cpu in 0 $((last / 2)) "$last"; do
    on="" off=""
    for _ in 1 2 3; do
        set_hide 1
        on+="$(probe time "$cpu" "$PAIRS" | mean_of)"$'\n'
        set_hide 0
        off+="$(probe time "$cpu" "$PAIRS" | mean_of)"$'\n'
    done
    on_mid=$(printf '%s' "$on" | middle)
    off_mid=$(printf '%s' "$off" | middle)
    if [ -z "$on_mid" ] || [ -z "$off_mid" ]; then
        bad "CPU $cpu: no timing" "$(probe time "$cpu" 100)"
        continue
    fi
    extra=$(awk -v a="$on_mid" -v b="$off_mid" 'BEGIN { printf "%.1f", a - b }')
    line="CPU $cpu: A-B $on_mid ns with the hook, $off_mid ns stock, hook adds $extra ns (limit $LIMIT)"
    if awk -v e="$extra" -v l="$LIMIT" 'BEGIN { exit !(e < l) }'; then ok "$line"; else bad "$line"; fi
done

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
