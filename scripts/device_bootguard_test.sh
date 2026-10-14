#!/usr/bin/env bash
# On-device test of camd's boot guard (rows 17 and 46 of docs/CAM_CHANGES.md section 1):
# real boot loops, by test modules that reboot the device, then a check of what camd did.
# Run it by hand after changing boot_guard.rs, boot_evidence.rs or the module rc code:
#
#   scripts/device_bootguard_test.sh                  # every scenario, the only device attached
#   scripts/device_bootguard_test.sh -s SERIAL ok zip # pick a device and scenarios
#
# Scenarios: ok one loop zip initrc pfd (see the functions below). The whole run reboots the
# device about 15 times (10 to 15 minutes).
#
# Every loop limits itself: an init rc loop rewrites its own rc through a copy queue at
# early-init, so it stops after the number of loops armed even when the boot guard fails;
# the post-fs-data loop removes its "armed" file before rebooting. The device needs init
# allowed to write to /metadata (scenario ok shows it: its rc counts its runs there) and the camd built from this checkout
# (`camd boot-guard initrc-enable` must exist). The modules enabled before the run are
# enabled again after each scenario (straight through their disable file, so they do not
# turn into suspects), the boot guard settings are put back, and the device reboots once more
# at the end. Do not open the Manager meanwhile: it copies its own camd over /data/adb/camd.
#
# If adb vanishes after a reboot, USB debugging went off (the wireless ADB boot clean-up turns
# it off when wireless ADB was what turned it on): turn it back on and run the rest.
set -u
export MSYS_NO_PATHCONV=1

ADB=(adb)
if [ "${1:-}" = "-s" ] && [ -n "${2:-}" ]; then
    ADB=(adb -s "$2")
    shift 2
fi
SCENARIOS=("$@")
[ ${#SCENARIOS[@]} -gt 0 ] || SCENARIOS=(ok one loop zip initrc pfd)

BOOT_TIMEOUT=${BOOT_TIMEOUT:-600}
HELPER=/data/local/tmp/cam_bgtest.sh
pass=0
fail=0

ok() { pass=$((pass + 1)); echo "PASS  $1"; }
bad() { fail=$((fail + 1)); echo "FAIL  $1"; [ -n "${2:-}" ] && printf '      %s\n' "$2"; }
check() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "expected '$3', got '$2'"; fi; }
has() { if grep -q -- "$3" <<<"$2"; then ok "$1"; else bad "$1" "no '$3' in: $(head -c 400 <<<"$2")"; fi; }
hasnt() { if grep -q -- "$3" <<<"$2"; then bad "$1" "unexpected '$3'"; else ok "$1"; fi; }

dev() { "${ADB[@]}" shell "su -c 'sh $HELPER $*'" 2>&1 | tr -d '\r'; }
field() { sed -n "s/^$1=//p" <<<"$2" | head -n 1; }

reboot_wait() {
    "${ADB[@]}" reboot >/dev/null 2>&1
    sleep 15
    local start=$SECONDS
    while [ $((SECONDS - start)) -lt "$BOOT_TIMEOUT" ]; do
        if [ "$("${ADB[@]}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then
            sleep 8
            return 0
        fi
        sleep 4
    done
    echo "device did not finish booting in ${BOOT_TIMEOUT}s" >&2
    exit 3
}

push_helper() {
    "${ADB[@]}" shell "cat > $HELPER" <<'DEVICE'
# Device side of scripts/device_bootguard_test.sh; runs as root.
CAMD=/data/adb/camd
STATE=/data/local/tmp/cam_bgtest.state
MODS=/data/adb/modules
UPD=/data/adb/modules_update
INITRC=/data/adb/initrc.d
if [ -d /metadata/watchdog ]; then D=/metadata/watchdog/cam; else D=/metadata/cam; fi
TEST_IDS="bgt-ok bgt-loop bgt-new bgt-pfd"

label() { chcon u:object_r:metadata_file:s0 "$@" 2>/dev/null; }

# init rc that reboots at early-init while its queue (tl.next*) still holds itself
loop_rc() {
    cat <<EOF
on early-init
    copy $D/tl.count2 $D/tl.count3
    copy $D/tl.count1 $D/tl.count2
    write $D/tl.count1 1
    copy $D/tl.next1 $D/testloop.rc
    copy $D/tl.next2 $D/tl.next1
    copy $D/tl.next3 $D/tl.next2
    setprop sys.powerctl reboot,camtest
EOF
}

ok_rc() {
    cat <<EOF
on early-init
    copy $D/ok.c2 $D/ok.c3
    copy $D/ok.c1 $D/ok.c2
    write $D/ok.c1 1
EOF
}

count() { n=0; for i in 1 2 3; do [ -f "$1$i" ] && n=$i; done; echo $n; }

case "$1" in
prep)
    mkdir -p "$D" || exit 1
    # init has to be able to write here, or neither the guard nor the loops can work
    echo "D=$D"
    if [ ! -f "$STATE" ]; then
        {
            echo "enabled=$(for m in "$MODS"/*; do [ -f "$m/module.prop" ] && [ ! -f "$m/disable" ] && [ ! -f "$m/remove" ] && basename "$m"; done | tr '\n' ' ')"
            echo "initrc=$(for f in "$INITRC"/*.rc; do [ -x "$f" ] && basename "$f"; done 2>/dev/null | tr '\n' ' ')"
            echo "guard=$($CAMD boot-guard status | tr -d ' \n')"
        } > "$STATE"
    fi
    cat "$STATE"
    $CAMD boot-guard --help 2>&1 | grep -q initrc-enable && echo "camd=new" || echo "camd=old"
    ;;
clean)
    for id in $TEST_IDS; do rm -rf "$MODS/$id" "$UPD/$id"; done
    rmdir "$UPD" 2>/dev/null
    rm -f "$D"/testloop.rc "$D"/tl.* "$D"/ok.c* "$INITRC/bgt-test.rc"
    for id in $(sed -n 's/^enabled=//p' "$STATE"); do rm -f "$MODS/$id/disable"; done
    for f in $(sed -n 's/^initrc=//p' "$STATE"); do chmod +x "$INITRC/$f"; done
    $CAMD boot-guard clear
    $CAMD initrc refresh
    ;;
restore)
    sh "$0" clean
    g=$(sed -n 's/^guard=//p' "$STATE")
    case "$g" in *'"enabled":false'*) en=false ;; *) en=true ;; esac
    th=$(echo "$g" | sed -n 's/.*"threshold":\([0-9]*\).*/\1/p')
    $CAMD boot-guard set --enabled "${en:-true}" --threshold "${th:-3}"
    rm -f "$STATE" "$0"
    ;;
threshold)
    $CAMD boot-guard set --enabled true --threshold "$2"
    ;;
arm)
    # loops $2 times at most
    loop_rc > "$D/testloop.rc"
    for i in 1 2 3; do
        if [ "$i" -lt "$2" ]; then cp "$D/testloop.rc" "$D/tl.next$i"; else echo "# done" > "$D/tl.next$i"; fi
    done
    rm -f "$D"/tl.count*
    label "$D/testloop.rc" "$D"/tl.next*
    ;;
module)
    # module ID KIND [staged]: KIND ok (counts its runs), loop (imports testloop.rc), pfd
    # (reboots once from post-fs-data.sh); staged sets it up as a pending install
    id=$2
    if [ "$4" = staged ]; then root="$UPD/$id"; else root="$MODS/$id"; fi
    mkdir -p "$root"
    printf 'id=%s\nname=%s\nversion=1\nversionCode=1\nauthor=cam\ndescription=boot guard test\n' "$id" "$id" > "$root/module.prop"
    case "$3" in
    ok) mkdir -p "$root/initrc"; ok_rc > "$root/initrc/test.rc" ;;
    loop) mkdir -p "$root/initrc"; echo "import $D/testloop.rc" > "$root/initrc/test.rc" ;;
    pfd)
        cat > "$root/post-fs-data.sh" <<'EOF'
MODDIR=${0%/*}
if [ -f "$MODDIR/armed" ]; then
    rm -f "$MODDIR/armed"
    log -t camtest "FATAL: boom from $MODDIR"
    sleep 2
    reboot
fi
EOF
        chmod 755 "$root/post-fs-data.sh"
        touch "$root/armed"
        ;;
    esac
    if [ "$4" = staged ]; then
        mkdir -p "$MODS/$id"
        cp "$root/module.prop" "$MODS/$id/module.prop"
        touch "$MODS/$id/update"
    fi
    $CAMD initrc refresh
    ;;
initrc)
    mkdir -p "$INITRC"
    echo "import $D/testloop.rc" > "$INITRC/bgt-test.rc"
    chmod 755 "$INITRC/bgt-test.rc"
    $CAMD initrc refresh
    ;;
initrc-enable)
    $CAMD boot-guard initrc-enable bgt-test.rc && echo "initrc_enable=ok"
    [ -x "$INITRC/bgt-test.rc" ] && echo "initrc_exec=1" || echo "initrc_exec=0"
    ;;
report)
    echo "loops=$(count "$D/tl.count")"
    echo "okruns=$(count "$D/ok.c")"
    # modules the user had turned off before the run are not the boot guard's doing
    echo "disabled=$(for id in $(sed -n 's/^enabled=//p' "$STATE") $TEST_IDS; do [ -f "$MODS/$id/disable" ] && echo "$id"; done | tr '\n' ' ')"
    echo "pending=$(ls "$UPD" 2>/dev/null | tr '\n' ' ')"
    [ -x "$INITRC/bgt-test.rc" ] && echo "initrc_exec=1" || echo "initrc_exec=0"
    echo "held=$([ -f "$D/modules.rc.held" ] && echo 1 || echo 0)"
    echo "reason=$(getprop sys.boot.reason.last)"
    # one line: only the indentation goes, the spaces inside strings (log lines) stay
    echo "status=$($CAMD boot-guard status | sed 's/^ *//' | tr -d '\n')"
    ;;
esac
DEVICE
}

# the culprits array of a status line, and one culprit's object (serde_json sorts the keys and
# prints "key": value)
culprits() { sed -n 's/.*"culprits": \[\(.*\)\],"enabled".*/\1/p' <<<"$1"; }
culprit() { culprits "$1" | grep -o "{\"evidence\": [^}]*\"id\": \"$2\"[^}]*}"; }

scenario_ok() {
    echo "== ok: a harmless init rc, threshold 2, three reboots: never disabled"
    dev threshold 2 >/dev/null
    dev module bgt-ok ok >/dev/null
    for _ in 1 2 3; do reboot_wait; done
    local r
    r=$(dev report)
    check "ok: its rc ran on every boot" "$(field okruns "$r")" 3
    check "ok: nothing disabled" "$(field disabled "$r" | tr -d ' ')" ""
    has "ok: no culprits" "$(field status "$r")" '"autoDisabled": \[\]'
}

scenario_one() {
    echo "== one: threshold 3, one boot dies at early-init, then a good one: nothing disabled"
    dev threshold 3 >/dev/null
    dev module bgt-ok ok >/dev/null
    dev module bgt-loop loop >/dev/null
    dev arm 1 >/dev/null
    reboot_wait
    local r
    r=$(dev report)
    check "one: looped once" "$(field loops "$r")" 1
    check "one: rc ran on both boots" "$(field okruns "$r")" 2
    check "one: nothing disabled" "$(field disabled "$r" | tr -d ' ')" ""
}

scenario_loop() {
    echo "== loop: threshold 3, early-init loop: 2 loops, then everything off, rc modules named"
    dev threshold 3 >/dev/null
    dev module bgt-ok ok >/dev/null
    dev module bgt-loop loop >/dev/null
    dev arm 3 >/dev/null
    reboot_wait
    local r s
    r=$(dev report)
    s=$(field status "$r")
    check "loop: looped twice" "$(field loops "$r")" 2
    check "loop: rc skipped on the third boot" "$(field okruns "$r")" 2
    has "loop: test modules disabled" "$(field disabled "$r")" "bgt-loop"
    has "loop: the others too" "$(field disabled "$r")" "bgt-ok"
    has "loop: bgt-loop named for its init rc" "$(culprit "$s" bgt-loop)" '"init_rc"'
    check "loop: held flag cleaned up" "$(field held "$r")" 0
}

scenario_zip() {
    echo "== zip: threshold 2, a pending install loops at early-init: it comes first, as changed"
    dev threshold 2 >/dev/null
    dev module bgt-ok ok >/dev/null
    dev module bgt-new loop staged >/dev/null
    dev arm 1 >/dev/null
    reboot_wait
    local r s
    r=$(dev report)
    s=$(field status "$r")
    check "zip: looped once" "$(field loops "$r")" 1
    check "zip: the pending install was moved in" "$(field pending "$r" | tr -d ' ')" ""
    has "zip: all modules disabled" "$(field disabled "$r")" "bgt-ok"
    has "zip: bgt-new named first" "$(culprits "$s")" '^{[^}]*"id": "bgt-new"'
    has "zip: bgt-new is changed and has an rc" "$(culprit "$s" bgt-new)" '"changed","init_rc"'
}

scenario_initrc() {
    echo "== initrc: threshold 2, an /data/adb/initrc.d file loops: it loses its exec bit"
    dev threshold 2 >/dev/null
    dev initrc >/dev/null
    dev arm 1 >/dev/null
    reboot_wait
    local r s
    r=$(dev report)
    s=$(field status "$r")
    check "initrc: looped once" "$(field loops "$r")" 1
    check "initrc: exec bit taken" "$(field initrc_exec "$r")" 0
    has "initrc: named" "$s" '"id": "initrc.d/bgt-test.rc"'
    has "initrc: listed as turned off" "$s" '"initrcDisabled": \["bgt-test.rc"\]'
    r=$(dev initrc-enable)
    check "initrc: initrc-enable gives it back" "$(field initrc_exec "$r")" 1
}

scenario_pfd() {
    echo "== pfd: threshold 2, post-fs-data.sh reboots once: named from the failed boot's logcat"
    dev threshold 2 >/dev/null
    dev module bgt-pfd pfd >/dev/null
    reboot_wait
    local r s c
    r=$(dev report)
    s=$(field status "$r")
    c=$(culprit "$s" bgt-pfd)
    has "pfd: all modules disabled" "$(field disabled "$r")" "bgt-pfd"
    has "pfd: named from the log" "$c" '"log"'
    has "pfd: with the log line" "$c" 'boom from /data/adb/modules/bgt-pfd'
}

if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
    echo "no device: connect one, or pick it with -s SERIAL" >&2
    exit 2
fi
echo "== device: $("${ADB[@]}" shell getprop ro.product.model | tr -d '\r'), Android $("${ADB[@]}" shell getprop ro.build.version.release | tr -d '\r')"
push_helper
prep=$(dev prep)
while IFS= read -r line; do echo "   $line"; done <<<"$prep"
if [ "$(field camd "$prep")" != new ]; then
    echo "camd on the device is older than this test (no boot-guard initrc-enable): install the Manager built from this checkout first" >&2
    dev restore >/dev/null
    exit 2
fi

# leftovers of a run that stopped half way
dev clean >/dev/null

for name in "${SCENARIOS[@]}"; do
    case "$name" in
    ok | one | loop | zip | initrc | pfd) "scenario_$name" ;;
    *) bad "unknown scenario $name" ;;
    esac
    dev clean >/dev/null
done

echo "== putting the device back"
dev restore >/dev/null
reboot_wait
echo
echo "passed $pass, failed $fail"
[ "$fail" -eq 0 ]
