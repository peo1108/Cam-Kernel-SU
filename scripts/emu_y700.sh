#!/usr/bin/env bash
# Android emulator shaped like a Lenovo Y700 Gen5 for Cam Kernel SU: Android 16, KMI
# android16-6.12, 1904x3040 at the 374 dpi the tablet runs at (440 physical). Same KMI, but
# x86_64 and no ZUI, so it cannot replace the tablet for the arm64 hooks, flashing init_boot,
# boot guard or hiding timing. A soft reboot with an app on screen takes the whole emulator down
# (null read in libgfxstream_backend.dll as surfaceflinger is killed): reboot it instead.
#
#   scripts/emu_y700.sh create          # (re)create the AVD, wipes its data
#   scripts/emu_y700.sh start [APK...]  # boot, install the APKs if given, load the LKM unless
#                                       # it is in already
#   scripts/emu_y700.sh load            # load the LKM again on the running emulator, runs the
#                                       # module stages again too
#   scripts/emu_y700.sh stop
#
# The LKM goes in with `camd late-load`, so it is gone after every reboot: run `load` again.
# APK: the CI `manager` artifact (holds the x86_64 camd), not a local build.
#
# `user` drives a second AVD kept like a customer's tablet: Google Play image (user build, no
# adb root), its own /data, cold boot every time, the LKM in the ramdisk so it comes back after
# reboots, OTA updates and installs over the old Manager. It listens on emulator-5556, so it can
# run beside the first one.
#
#   scripts/emu_y700.sh user create           # (re)create it, wipes its data and the patched
#                                             # ramdisk
#   scripts/emu_y700.sh user start [FILE...]  # boot, install each APK, copy any other file
#                                             # (module zip) to Download
#   scripts/emu_y700.sh user drop             # start for may-ao.bat: the names come from
#                                             # $CAM_DROP, the end of cmd's raw command line
#   scripts/emu_y700.sh user flash           # patch the ramdisk with the installed Manager's
#                                             # camd, then restart: `fastboot flash init_boot`
#                                             # for the AVD
#   scripts/emu_y700.sh user unflash          # back to the stock ramdisk, then restart
#   scripts/emu_y700.sh user stop             # power Android off, as from its power menu
#
# Once flashed, config.ini names cam-ramdisk.img, so the AVD boots it however it is launched. A
# newer system image in the SDK takes it away, as an OTA replaces init_boot: a start from here
# says so and boots the stock ramdisk, then run `user flash` again.
#
# Start both AVDs from here (may-ao.bat for the user one), not from Android Studio: its launch
# has none of EMU_FLAGS below (surfaceflinger aborts, no touch device), and it boots
# cam-ramdisk.img without checking that it still belongs to the system image. An AVD that is
# already up is used as it is, on whatever port it runs; a missing touch device or an old
# ramdisk gets a warning.
# shellcheck disable=SC2016 # single-quoted commands run in the device's shell
set -eu

USER_MODE=
if [ "${1:-}" = user ]; then
    USER_MODE=1
    shift
fi
if [ -n "$USER_MODE" ]; then
    AVD=CamY700User
    IMAGE="system-images;android-36.1;google_apis_playstore;x86_64"
    EMU_PORT=5556
    # every start is a cold boot, as on the tablet
    SNAPSHOT=-no-snapshot
else
    AVD=CamY700Gen5
    IMAGE="system-images;android-36.1;google_apis;x86_64"
    EMU_PORT=5554
    SNAPSHOT=-no-snapshot-load
fi
# the console port the commands below talk to; own_port moves it to where $AVD runs
PORT=$EMU_PORT
PKG=cam.su.kernel

: "${LOCALAPPDATA:?run this from Git Bash on Windows}"
SDK=$(cygpath -u "$LOCALAPPDATA")/Android/Sdk
ADB=$SDK/platform-tools/adb.exe
emu_adb() { "$ADB" -s "emulator-$PORT" "$@"; } # the tablet may be attached too
# adb_within SECONDS PORT ARGS: a qemu hung in a shutdown takes the connection and never answers,
# and that must not hang this script
adb_within() {
    local secs=$1 port=$2 rc
    shift 2
    timeout "$secs" "$ADB" -s "emulator-$port" "$@" && return
    rc=$?
    [ $rc -ne 124 ] || echo "adb $1: no answer in $secs s" >&2
    return $rc
}
# for the adb calls a live emulator answers at once
quick_adb() { adb_within 15 "$PORT" "$@"; }
AVD_DIR=$(cygpath -u "$USERPROFILE")/.android/avd/$AVD.avd
CONFIG=$AVD_DIR/config.ini
# what `user flash` writes and config.ini boots; the stock one stays in the SDK
RAMDISK=$AVD_DIR/cam-ramdisk.img
# sha256 of the stock ramdisk it was patched from
RAMDISK_SRC=$RAMDISK.src
# where `user flash` works on the device
DEV_TMP=/data/local/tmp
export MSYS_NO_PATHCONV=1

# Both AVDs need these.
# Without GLDirectMem this image has no ReadColorBufferDma: surfaceflinger aborts in
# mapper.ranchu every 5 s and system_server goes with it, even with a software GPU.
# Without GLESDynamicVersion it reports GLES 2.0: the Filament slime material has no
# package for that level, so the Manager aborts in Material::Builder::build on launch.
# -screen: emulator 37.2 starts these AVDs with hw.screen=touch whatever config.ini says, and
# then the guest has no touch device at all, so the mouse does nothing in the window. No key in
# config.ini brings it back (skin.* did not, 2026-10-31).
# Not ~/.android/advancedFeatures.ini: it does work for -feature, but every AVD on this PC reads it.
EMU_FLAGS=(-no-boot-anim -gpu host -screen multi-touch
    -feature "GLDMA,GLDirectMem,HasSharedSlotsHostMemoryAllocator,GLESDynamicVersion")

usage() {
    sed -n '2,/^# shellcheck/{/^# shellcheck/!p}' "$0"
    exit 2
}

create() {
    # avdmanager rewrites files a running emulator holds open
    ! emu_running || stop_emu
    "$SDK/cmdline-tools/latest/bin/sdkmanager.bat" --install emulator "$IMAGE" </dev/null >/dev/null 2>&1 || true
    echo no | "$SDK/cmdline-tools/latest/bin/avdmanager.bat" create avd -n "$AVD" -k "$IMAGE" -d pixel_tablet --force >/dev/null
    # drop <temp> too: it would lose /data on every boot
    sed -i -E '/^(hw\.lcd\.(width|height|density)|hw\.ramSize|hw\.cpu\.ncore|hw\.gpu\.(enabled|mode)|disk\.dataPartition\.(size|path)|kernel\.parameters)=/d' "$CONFIG"
    cat >>"$CONFIG" <<'EOF'
hw.gpu.enabled=yes
hw.gpu.mode=host
hw.lcd.width=1904
hw.lcd.height=3040
hw.lcd.density=440
hw.ramSize=12288
hw.cpu.ncore=8
disk.dataPartition.size=32G
EOF
    rm -f "$RAMDISK" "$RAMDISK_SRC"
    echo "created $AVD"
}

# PowerShell listing emulator.exe and its qemu child for this AVD, however it was launched
# (`-avd NAME`, quoted or not, or `@NAME`); adb loses the device before they exit and let go of
# the AVD's lock and disk images. A PowerShell start costs about half a second.
PS_EMU="Get-CimInstance Win32_Process -Filter \"Name like 'emulator%' or Name like 'qemu-system%'\" |
    Where-Object { \$_.CommandLine -match '(-avd\"? +\"?|@)$AVD\"?( |\$)' }"
emu_running() { [ -n "$(powershell.exe -NoProfile -Command "$PS_EMU | ForEach-Object { \$_.ProcessId }")" ]; }

# wait up to $1 s for this AVD to exit. PS_EMU sees nothing of an emulator started elevated, so
# the console has to be gone too
wait_exit() {
    local deadline=$((SECONDS + $1))
    powershell.exe -NoProfile -Command "try { $PS_EMU | ForEach-Object { Get-Process -Id \$_.ProcessId -ErrorAction Ignore } |
        Wait-Process -Timeout $1 -ErrorAction Stop; exit 0 } catch { exit 1 }" || return 1
    while [ "$(emu_name)" = "$AVD" ]; do
        [ $SECONDS -lt $deadline ] || return 1
        sleep 1
    done
}

# which AVD runs on emulator-${1:-$PORT}: its console answers without an adb transport, adbd when
# the console is busy
emu_name() {
    local port=${1:-$PORT} name
    name=$(adb_within 10 "$port" emu avd name 2>/dev/null | head -n 1 | tr -d '\r')
    [ -n "$name" ] || name=$(adb_within 5 "$port" shell getprop ro.boot.qemu.avd_name 2>/dev/null | tr -d '\r')
    echo "$name"
}

# the console port $AVD answers on: Android Studio puts it on the first free one, so look
# through every emulator adb lists (offline ones too) after $PORT
find_port() {
    local p
    for p in $PORT $(timeout 10 "$ADB" devices 2>/dev/null |
        sed -n "/^emulator-$PORT\s/d; s/^emulator-\([0-9]*\)\s.*/\1/p"); do
        if [ "$(emu_name "$p")" = "$AVD" ]; then
            echo "$p"
            return
        fi
    done
    return 1
}

# another AVD can sit on emulator-$PORT: never install, flash or stop that one
own_port() {
    local p
    if ! p=$(find_port); then
        echo "$AVD does not answer on any emulator console" >&2
        return 1
    fi
    PORT=$p
}

# point the commands at $AVD. 1: it is not running. 2: it runs but answers on no console, even
# after a wait for what passes in seconds (an adb server that has just started, a console not up
# yet or under load), so qemu probably hung
locate() {
    local deadline=$((SECONDS + 20))
    own_port 2>/dev/null && return 0
    emu_running || return 1
    while [ $SECONDS -lt $deadline ]; do
        sleep 3
        own_port 2>/dev/null && return 0
    done
    return 2
}

wait_boot() {
    # one short adb call per try, not wait-for-device: adbd restarts during the first boot of the
    # user image and drops the shell (exit 255), and an emulator that died must not hang this
    local deadline=$((SECONDS + 600)) look=0 out
    until out=$(quick_adb shell getprop sys.boot_completed 2>&1 | tr -d '\r'); [ "$out" = 1 ]; do
        if [ $SECONDS -ge $deadline ]; then
            echo "$AVD did not finish booting in 10 minutes${out:+ (adb: $out)}" >&2
            exit 1
        fi
        # anything but an empty getprop is an adb error ("device 'emulator-5556' not found",
        # "device offline"): fine while it boots, only a gone emulator ends the wait early. Not
        # at every try: PowerShell would compete with the boot for the CPU
        if [ -n "$out" ] && [ $SECONDS -ge $look ]; then
            look=$((SECONDS + 10))
            if ! emu_running; then
                echo "$AVD exited while booting (adb: $out)" >&2
                exit 1
            fi
        fi
        sleep 2
    done
}

# the system image this AVD was made from, as config.ini names it
stock_ramdisk() {
    local dir
    dir=$(sed -n 's/^image\.sysdir\.1=//p' "$CONFIG" | sed 's/\r$//; s|\\|/|g')
    [ -n "$dir" ] || {
        echo "no image.sysdir.1 in $CONFIG" >&2
        exit 1
    }
    echo "$SDK/${dir%/}/ramdisk.img"
}

# cam-ramdisk.img was patched from another system image: its init and first-stage modules
# belong to the old kernel. No stock ramdisk to compare with (the SDK is replacing the image right
# now, config.ini lost its image) says nothing about the patch.
ramdisk_stale() {
    local sha
    [ -f "$RAMDISK" ] || return 1
    sha=$(sha256sum "$(stock_ramdisk)" 2>/dev/null | cut -d' ' -f1)
    [ -n "$sha" ] && [ "$(cat "$RAMDISK_SRC" 2>/dev/null)" != "$sha" ]
}

# set a key of config.ini, or drop it when the value is empty
set_config() {
    sed -i "/^${1//./[.]}=/d" "$CONFIG"
    [ -z "$2" ] || printf '%s=%s\n' "$1" "$2" >>"$CONFIG"
}

user_config() {
    local path=
    if ramdisk_stale; then
        rm -f "$RAMDISK" "$RAMDISK_SRC"
        echo "cam-ramdisk.img is not from the current system image: stock ramdisk now, flash again" >&2
    fi
    [ ! -f "$RAMDISK" ] || path=$(cygpath -w "$RAMDISK")
    set_config disk.ramdisk.path "$path"
    # a launch from Android Studio cold boots too
    set_config fastboot.forceColdBoot yes
    set_config fastboot.forceFastBoot no
}

# what a launch from elsewhere leaves out; the emulator rewrites hardware-qemu.ini every launch
warn_launch() {
    if grep -q '^hw\.screen = touch' "$AVD_DIR/hardware-qemu.ini" 2>/dev/null; then
        # no -screen, so most likely no -feature either
        echo "$AVD was not started with this script's flags: the mouse does nothing in its window, and without -feature it never finishes booting. Stop it and start it from here" >&2
    fi
    if [ -n "$USER_MODE" ] && ramdisk_stale; then
        echo "$AVD booted cam-ramdisk.img of an older system image: stop it and start it from here" >&2
    fi
}

# boot $AVD unless it runs already, then wait for Android
boot() {
    [ -f "$CONFIG" ] || create
    local found=0 name
    locate || found=$?
    case $found in
    0)
        # already up (may-ao.bat with a file dropped on it, or started elsewhere): only install
        warn_launch
        wait_boot
        ;;
    1)
        # -port: left to itself the emulator takes the first free port, maybe the other AVD's
        PORT=$EMU_PORT
        name=$(emu_name)
        if [ -n "$name" ]; then
            echo "emulator-$PORT is taken by $name: stop it first" >&2
            exit 1
        fi
        [ -z "$USER_MODE" ] || user_config
        # Start-Process, not nohup: a child of this console dies when its window is closed,
        # may-ao.bat's too. No argument has a space, so they go unquoted in one string
        powershell.exe -NoProfile -Command "Start-Process -WindowStyle Hidden \
            -FilePath '$(cygpath -w "$SDK/emulator/emulator.exe")' \
            -ArgumentList '-avd $AVD -port $PORT $SNAPSHOT ${EMU_FLAGS[*]}'"
        wait_boot
        # make sure it was this AVD that answered on the port
        own_port
        ;;
    *)
        echo "$AVD runs but answers on no emulator console, it probably hung: stop it" >&2
        exit 1
        ;;
    esac
}

# power Android off inside, as the tablet does: /data is flushed and no crash report dialog is
# left as after `emu kill`; adb exits 255 as the device goes
stop_emu() {
    local found=0
    locate || found=$?
    if [ $found = 1 ]; then
        echo "$AVD is not running"
        return
    fi
    if [ $found = 0 ]; then
        # adb can lag behind a live emulator (just restarted, the PC just woke up)
        if quick_adb wait-for-device 2>/dev/null; then
            # qemu hangs in some shutdowns (user AVD 10 of 44, dev 1 of 15 on 2026-10-31) and gets
            # killed below: sync first so /data keeps the last writes anyway
            quick_adb shell sync >/dev/null 2>&1 || true
            quick_adb shell svc power shutdown >/dev/null 2>&1 || true
            wait_exit 45 && return
        fi
        # boot loop or adb offline. Not waited for: when qemu itself hangs (the console takes
        # the connection and never answers) `emu kill` never returns until the process below is
        # killed, or the time limit ends it
        adb_within 60 "$PORT" emu kill >/dev/null 2>&1 &
        wait_exit 15 && return
    fi
    # no console answers, or it hung after one did
    echo "killing $AVD" >&2
    powershell.exe -NoProfile -Command "$PS_EMU | ForEach-Object { Stop-Process -Force -Id \$_.ProcessId -ErrorAction Ignore }" || true
    wait_exit 30 || {
        echo "$AVD is still running" >&2
        exit 1
    }
}

load() {
    if ! locate; then
        echo "$AVD is not running, or answers on no emulator console" >&2
        exit 1
    fi
    emu_adb root >/dev/null
    wait_boot
    # allow_shell: adb shell can use su, as scripts/device_*.sh expect
    emu_adb shell "P=\$(pm path $PKG | sed 's/package://'); \${P%/base.apk}/lib/x86_64/libcamd.so late-load --allow-shell"
    emu_adb shell 'for i in $(seq 1 30); do [ -x /data/adb/camd ] && grep -q camsu /proc/modules && exit 0; sleep 1; done; echo "camsu did not load" >&2; exit 1'
    quick_adb shell wm density 374 || true
    emu_adb shell '/data/adb/camd debug info | grep "^version"; uname -r'
}

# 0: camsu is loaded. 1: it is not. Anything else: adb could not tell. The shell can read this on
# the user build too
camsu_loaded() { quick_adb shell 'grep -q "^camsu " /proc/modules'; }

# /sdcard refuses writes for a few seconds after boot_completed ("Operation not permitted")
SDCARD_READY=
wait_sdcard() {
    [ -z "$SDCARD_READY" ] || return 0
    emu_adb shell 'for i in $(seq 1 30); do touch /sdcard/Download/.cam-probe 2>/dev/null &&
        exec rm /sdcard/Download/.cam-probe; sleep 1; done; echo "/sdcard is not writable" >&2; exit 1' &&
        SDCARD_READY=1
}

start_user() {
    boot
    # what a flash leaves when its window is closed, see there
    flash_clean
    quick_adb shell wm density 374 || true
    local f failed=() state=0 err=0
    # every file gets its turn: an APK refused over the old Manager is no reason to skip a module
    for f in "$@"; do
        echo "> $f"
        case "$f" in
        # no -g: the Manager asks for its permissions, as after a customer's first install
        *.[aA][pP][kK]) emu_adb install -r "$(cygpath -w "$f")" ;;
        # a module zip or anything else lands where the Manager's file picker opens
        *) wait_sdcard && emu_adb push "$(cygpath -w "$f")" /sdcard/Download/ ;;
        esac || failed+=("$f")
    done
    # the files are in: a slow flush is no reason to report a failure
    quick_adb shell sync || true
    # what booted, not what config.ini names
    camsu_loaded || state=$?
    if [ $state = 0 ]; then
        echo "booted $AVD, camsu loaded"
    elif [ $state != 1 ]; then
        echo "booted $AVD, adb could not tell whether camsu is loaded" >&2
    elif [ -f "$RAMDISK" ]; then
        echo "booted $AVD, but camsu is not loaded although cam-ramdisk.img is flashed" >&2
        err=1
    else
        echo "booted $AVD, stock ramdisk"
    fi
    if [ ${#failed[@]} -gt 0 ]; then
        echo "not done:" >&2
        printf '  %s\n' "${failed[@]}" >&2
        err=1
    fi
    [ $err = 0 ] || exit 1
}

# the temporary files of `user flash` on the device; a failure here costs nothing, the next start
# or flash cleans up too
flash_clean_device() {
    quick_adb shell rm -f $DEV_TMP/ramdisk-stock.img $DEV_TMP/cam-ramdisk.img >/dev/null 2>&1 || true
}
flash_clean() {
    flash_clean_device
    rm -f "$RAMDISK.new"
}

# the running kernel is the one in the system image config.ini names, which the SDK can have
# replaced under a running AVD; adb saying nothing is for the next step to report
kernel_current() {
    local release
    release=$(quick_adb shell uname -r | tr -d '\r')
    [ -z "$release" ] || grep -aqF "$release" "$(dirname "$(stock_ramdisk)")/kernel-ranchu"
}

# what a user does in the Manager (patch a boot image) and with fastboot, done as the shell user
# with the camd of the installed Manager, so an OTA or an older APK patches with its own LKM. No
# --kmi: camd reads it from the running kernel, so that has to be the current image's
flash_user() {
    boot
    if ! kernel_current; then
        echo "$AVD runs an older system image: restarting it" >&2
        stop_emu
        boot
    fi
    local stock apk sha
    stock=$(stock_ramdisk)
    # before the push: the image the patch comes from, whatever the SDK does while it runs
    sha=$(sha256sum "$stock" | cut -d' ' -f1)
    # look before pushing: nothing may stay behind in /data/local/tmp of a customer-like device
    apk=$(emu_adb shell pm path $PKG | tr -d '\r' | sed -n '1s/^package://p')
    if [ -z "$apk" ]; then
        echo "$PKG is not installed" >&2
        exit 1
    fi
    # not after a failure or Ctrl+C either. Closing may-ao.bat's window ends bash before a trap can
    # run (`kill -HUP` does not show this, a real close does): the next start or flash cleans up
    trap flash_clean EXIT
    flash_clean
    emu_adb push "$(cygpath -w "$stock")" $DEV_TMP/ramdisk-stock.img >/dev/null
    emu_adb shell "cd $DEV_TMP && ${apk%/base.apk}/lib/x86_64/libcamd.so boot-patch --ramdisk \
        -b ramdisk-stock.img -o $DEV_TMP --out-name cam-ramdisk.img"
    emu_adb pull $DEV_TMP/cam-ramdisk.img "$(cygpath -w "$RAMDISK.new")" >/dev/null
    trap 'rm -f "$RAMDISK.new"' EXIT
    flash_clean_device
    # the emulator reads the ramdisk once at launch: a reboot inside it would boot the old one
    stop_emu
    mv -f "$RAMDISK.new" "$RAMDISK"
    echo "$sha" >"$RAMDISK_SRC"
    trap - EXIT
    start_user
}

if [ -n "$USER_MODE" ]; then
    case "${1:-}" in
    create) create ;;
    start)
        shift
        start_user "$@"
        ;;
    drop)
        # what follows may-ao.bat's name on cmd's raw line: the quote closing its path, then
        # names split by spaces, in quotes only when they hold one, then cmd's closing quote
        line=${CAM_DROP:-}
        mapfile -t files < <(printf '%s' "${line#\"}" | grep -aoE '"[^"]+"|[^" ]+' | tr -d '"')
        start_user "${files[@]}"
        ;;
    flash) flash_user ;;
    unflash)
        stop_emu
        rm -f "$RAMDISK" "$RAMDISK_SRC"
        start_user
        ;;
    stop) stop_emu ;;
    *) usage ;;
    esac
    exit 0
fi

case "${1:-}" in
create) create ;;
start)
    shift
    boot
    for apk in "$@"; do
        emu_adb install -r -g "$(cygpath -w "$apk")"
    done
    quick_adb shell sync || true
    # it was up already: late-load would run the module stages again and force-stop the Manager
    if camsu_loaded; then
        quick_adb shell wm density 374 || true
        echo "camsu is loaded already, \`load\` runs the module stages again"
    else
        load
    fi
    ;;
load) load ;;
stop) stop_emu ;;
*) usage ;;
esac
