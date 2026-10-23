#!/usr/bin/env bash
# Device test of SELinux hiding turned on after boot (kernel/feature/selinux_hide.c,
# selinux_hide_feature_set). The kernel keeps the policy backup the hooks need only when hiding
# runs at boot_completed, so turning it on later fails with EAGAIN and starts at the next boot.
# Until then the kernel must report the switch on, since `camd feature save` (which the Manager
# runs after every switch) persists what the kernel reports, but apps must still get the real
# status page, as the context, access and attr/current hooks are not there yet.
#
#   scripts/device_selinux_hide_pending_test.sh [-s SERIAL]
#
# Reboots the device twice: with hiding saved off, then after switching it on. Needs root granted
# to Shell and the NDK (NDK_CLANG=<clang> to pick one) for scripts/selinux_timing_probe.c. Puts
# the feature config back as it was at the end, even when interrupted; the kernel follows it at
# the next boot.
set -u
export MSYS_NO_PATHCONV=1

ADB=(adb)
command -v adb >/dev/null || ADB=("${LOCALAPPDATA:-}/Android/Sdk/platform-tools/adb.exe")
if [ "${1:-}" = "-s" ] && [ -n "${2:-}" ]; then
    ADB+=(-s "$2")
fi

CAMD=/data/adb/camd
CONFIG=/data/adb/cam/.feature_config
SAVED=/data/local/tmp/cam_hide_pending.feature_config
PROBE=/data/local/tmp/cam_selinux_timing
BOOT_TIMEOUT=${BOOT_TIMEOUT:-600}
pass=0
fail=0
ok() { pass=$((pass + 1)); echo "PASS  $1"; }
bad() { fail=$((fail + 1)); echo "FAIL  $1"; [ -n "${2:-}" ] && printf '      %s\n' "$2"; }
check() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "expected '$3', got '$2'"; fi; }

root() { "${ADB[@]}" shell "su -c '$*'" 2>&1 | tr -d '\r'; }
feature() { root "$CAMD feature $*"; }
value() { feature get selinux_hide | sed -n 's/^Value: //p'; }
probe() { "${ADB[@]}" shell "su -c '$PROBE $* 2>&1 | cat'" | tr -d '\r'; }
# selinux_hide (id 4) in the saved config: magic, version, count, then id, value lo, value hi
saved() {
    root "od -An -v -tu4 $CONFIG" | tr -s ' ' '\n' | awk 'NF' |
        awk 'NR >= 4 && (NR - 4) % 3 == 0 && $1 == 4 { getline; print; exit }'
}

reboot_wait() {
    "${ADB[@]}" reboot >/dev/null 2>&1
    sleep 15
    local start=$SECONDS
    while [ $((SECONDS - start)) -lt "$BOOT_TIMEOUT" ]; do
        if [ "$("${ADB[@]}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then
            # camd's boot-completed event, where the kernel drops an unused backup
            sleep 10
            return 0
        fi
        sleep 4
    done
    echo "device did not finish booting in ${BOOT_TIMEOUT}s" >&2
    exit 3
}

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
if ! "$clang" --target=aarch64-linux-android31 -O2 -o "$(native "$bin")" "$(native "$here/selinux_timing_probe.c")"; then
    echo "cannot build the probe" >&2
    exit 2
fi

if ! root id | grep -q 'uid=0'; then
    echo "su from adb shell does not give root: grant root to Shell in the Manager" >&2
    exit 2
fi
"${ADB[@]}" push "$(native "$bin")" "$PROBE" >/dev/null 2>&1 && "${ADB[@]}" shell "su -c 'chmod 755 $PROBE'" || exit 2

had_config=$(root "[ -f $CONFIG ] && cp -p $CONFIG $SAVED && echo yes")
before=$(saved)
restore() {
    if [ "$had_config" = yes ]; then
        root "cp -p $SAVED $CONFIG && rm -f $SAVED" >/dev/null
    else
        root "rm -f $CONFIG" >/dev/null
    fi
    "${ADB[@]}" shell "su -c 'rm -f $PROBE'" >/dev/null 2>&1
    rm -f "$bin"
    if [ "$(saved)" != "$before" ]; then
        echo "could not put $CONFIG back: selinux_hide saved as '$(saved)', was '$before'" >&2
    elif [ "$(value)" != "${before:-0}" ]; then
        echo "feature config restored (selinux_hide=${before:-unset}); reboot to apply it" >&2
    fi
}
trap restore EXIT
trap 'exit 130' INT TERM

# Boot 1: hiding saved off, so the kernel drops the backup at boot_completed.
feature set selinux_hide 0 >/dev/null
feature save >/dev/null
check "hiding saved off" "$(saved)" 0
reboot_wait

out=$(feature set selinux_hide 1)
case "$out" in
*"os error 11"*) ok "switching on after boot waits for the next boot (EAGAIN)" ;;
*)
    bad "switching on after boot" "$out"
    exit 1
    ;;
esac
check "the kernel reports the switch on" "$(value)" 1
out=$(probe write u:r:cam:s0)
case "$out" in *errno=13*) ok "hooks not running yet: u:r:cam:s0 is known (EACCES)" ;; *) bad "hooks before the next boot" "$out" ;; esac
app=$(probe status)
real=$(probe rootstatus)
case "$app" in
sequence=*) check "apps read the real status page until the hooks run" "$app" "$real" ;;
*) bad "status page as an app" "$app" ;;
esac
feature save >/dev/null
check "camd feature save keeps it on" "$(saved)" 1

# Boot 2: the saved switch starts hiding.
reboot_wait
check "hiding on after the reboot" "$(value)" 1
out=$(probe write u:r:cam:s0)
case "$out" in *errno=22*) ok "hooks running: u:r:cam:s0 is not a context (EINVAL)" ;; *) bad "hooks after the reboot" "$out" ;; esac
app=$(probe status)
real=$(probe rootstatus)
case "$app" in
sequence=*) if [ "$app" != "$real" ]; then ok "apps read the fake status page ($app, real $real)"; else bad "apps read the real status page with hiding on" "$app"; fi ;;
*) bad "status page as an app" "$app" ;;
esac

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
