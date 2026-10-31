#!/usr/bin/env bash
# Android emulator shaped like a Lenovo Y700 Gen5 for Cam Kernel SU: Android 16, KMI
# android16-6.12, 1904x3040 at the 374 dpi the tablet runs at (440 physical). Same KMI, but
# x86_64 and no ZUI, so it cannot replace the tablet for the arm64 hooks, flashing init_boot,
# boot guard or hiding timing. A soft reboot with an app on screen takes the whole emulator down
# (null read in libgfxstream_backend.dll as surfaceflinger is killed): reboot it instead.
#
#   scripts/emu_y700.sh create        # (re)create the AVD, wipes its data
#   scripts/emu_y700.sh start [APK]   # boot, install APK if given, load the LKM
#   scripts/emu_y700.sh load          # load the LKM again on the running emulator
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
#   scripts/emu_y700.sh user create        # (re)create it, wipes its data and the patched ramdisk
#   scripts/emu_y700.sh user start [FILE]  # boot, install an APK, copy any other file (module
#                                          # zip) to Download
#   scripts/emu_y700.sh user flash         # patch the ramdisk with the installed Manager's camd,
#                                          # then restart: `fastboot flash init_boot` for the AVD
#   scripts/emu_y700.sh user unflash       # back to the stock ramdisk, then restart
#   scripts/emu_y700.sh user stop          # power Android off, as from its power menu
#
# Once flashed, config.ini names cam-ramdisk.img, so the AVD boots it however it is launched
# (Android Studio too). A newer system image in the SDK takes it away, as an OTA replaces
# init_boot: `user start` says so and boots the stock ramdisk, then run `user flash` again.
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
    PORT=5556
else
    AVD=CamY700Gen5
    IMAGE="system-images;android-36.1;google_apis;x86_64"
    PORT=5554
fi
PKG=cam.su.kernel

: "${LOCALAPPDATA:?run this from Git Bash on Windows}"
SDK=$(cygpath -u "$LOCALAPPDATA")/Android/Sdk
emu_adb() { "$SDK/platform-tools/adb.exe" -s emulator-$PORT "$@"; } # the tablet may be attached too
AVD_DIR=$(cygpath -u "$USERPROFILE")/.android/avd/$AVD.avd
CONFIG=$AVD_DIR/config.ini
# what `user flash` writes and config.ini boots; the stock one stays in the SDK
RAMDISK=$AVD_DIR/cam-ramdisk.img
# sha256 of the stock ramdisk it was patched from
RAMDISK_SRC=$RAMDISK.src
export MSYS_NO_PATHCONV=1

# Both AVDs need these.
# Without GLDirectMem this image has no ReadColorBufferDma: surfaceflinger aborts in
# mapper.ranchu every 5 s and system_server goes with it, even with a software GPU.
# Without GLESDynamicVersion it reports GLES 2.0: the Filament slime material has no
# package for that level, so the Manager aborts in Material::Builder::build on launch.
# -screen: emulator 37.2 starts these AVDs with hw.screen=touch whatever config.ini says, and
# then the guest has no touch device at all, so the mouse does nothing in the window.
EMU_FLAGS=(-no-boot-anim -gpu host -screen multi-touch
    -feature "GLDMA,GLDirectMem,HasSharedSlotsHostMemoryAllocator,GLESDynamicVersion")

usage() {
    sed -n '2,/^# shellcheck/{/^# shellcheck/!p}' "$0"
    exit 2
}

create() {
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

# emulator.exe and its qemu child for this AVD, however it was launched; adb loses the device
# before they exit and let go of the AVD's lock and disk images
emu_pids() {
    powershell.exe -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name like 'emulator%' or Name like 'qemu-system%'\" |
        Where-Object { \$_.CommandLine -match '-avd $AVD( |\$)' } | ForEach-Object { \$_.ProcessId }" | tr -d '\r'
}
emu_running() { [ -n "$(emu_pids)" ]; }

wait_exit() {
    local deadline=$((SECONDS + $1))
    while emu_running; do
        [ $SECONDS -lt $deadline ] || return 1
        sleep 1
    done
}

# which AVD answers on the console of emulator-$PORT (no adb transport needed)
emu_name() { emu_adb emu avd name 2>/dev/null | head -n 1 | tr -d '\r'; }

# another AVD can sit on emulator-$PORT: never install, flash or stop that one
own_port() {
    local name
    name=$(emu_name)
    [ "$name" != "$AVD" ] || return 0
    if [ -n "$name" ]; then
        echo "emulator-$PORT is $name, not $AVD" >&2
    elif emu_running; then
        echo "$AVD runs on another port (started from Android Studio?): close it first" >&2
    else
        echo "$AVD is not running" >&2
    fi
    exit 1
}

wait_boot() {
    # one short adb call per try, not wait-for-device: adbd restarts during the first boot of the
    # user image and drops the shell (exit 255), and an emulator that died must not hang this
    local deadline=$((SECONDS + 600)) out
    until out=$(emu_adb shell getprop sys.boot_completed 2>&1 | tr -d '\r'); [ "$out" = 1 ]; do
        if [ $SECONDS -ge $deadline ]; then
            echo "$AVD did not finish booting in 10 minutes${out:+ (adb: $out)}" >&2
            exit 1
        fi
        # anything but an empty getprop is an adb error ("device 'emulator-5556' not found",
        # "device offline"): fine while it boots, only a gone emulator ends the wait early
        if [ -n "$out" ] && ! emu_running; then
            echo "$AVD exited while booting (adb: $out)" >&2
            exit 1
        fi
        sleep 2
    done
    own_port
}

load() {
    own_port
    emu_adb root >/dev/null
    wait_boot
    # allow_shell: adb shell can use su, as scripts/device_*.sh expect
    emu_adb shell "P=\$(pm path $PKG | sed 's/package://'); \${P%/base.apk}/lib/x86_64/libcamd.so late-load --allow-shell"
    emu_adb shell 'for i in $(seq 1 30); do [ -x /data/adb/camd ] && grep -q camsu /proc/modules && exit 0; sleep 1; done; echo "camsu did not load" >&2; exit 1'
    emu_adb shell wm density 374
    emu_adb shell '/data/adb/camd debug info | grep "^version"; uname -r'
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

# set a key of config.ini, or drop it when the value is empty
set_config() {
    sed -i "/^${1//./\\.}=/d" "$CONFIG"
    [ -z "$2" ] || printf '%s=%s\n' "$1" "$2" >>"$CONFIG"
}

user_config() {
    local sha path=
    sha=$(stock_ramdisk)
    sha=$(sha256sum "$sha" | cut -d' ' -f1)
    if [ -f "$RAMDISK" ] && [ "$(cat "$RAMDISK_SRC" 2>/dev/null)" != "$sha" ]; then
        # its init and first-stage modules belong to the old image
        rm -f "$RAMDISK" "$RAMDISK_SRC"
        echo "cam-ramdisk.img is not from the current system image: stock ramdisk now, flash again" >&2
    fi
    [ ! -f "$RAMDISK" ] || path=$(cygpath -w "$RAMDISK")
    set_config disk.ramdisk.path "$path"
    # a launch from Android Studio cold boots too
    set_config fastboot.forceColdBoot yes
    set_config fastboot.forceFastBoot no
}

start_user() {
    [ -f "$CONFIG" ] || create
    if emu_running; then
        # already up (may-ao.bat with a file dropped on it): only install
        own_port
    else
        local name
        name=$(emu_name)
        if [ -n "$name" ]; then
            echo "emulator-$PORT is taken by $name: stop it first" >&2
            exit 1
        fi
        user_config
        # -no-snapshot: every start is a cold boot, as on the tablet
        local quoted='' a
        for a in -avd "$AVD" -port $PORT -no-snapshot "${EMU_FLAGS[@]}"; do quoted+=" \"$a\""; done
        # Start-Process, not nohup: a child of this console dies when may-ao.bat's window is closed
        powershell.exe -NoProfile -Command "Start-Process -WindowStyle Hidden \
            -FilePath '$(cygpath -w "$SDK/emulator/emulator.exe")' -ArgumentList '${quoted# }'"
    fi
    wait_boot
    emu_adb shell wm density 374
    case "${1:-}" in
    '') ;;
    *.[aA][pP][kK]) emu_adb install -r -g "$(cygpath -w "$1")" ;;
    # a module zip or anything else lands where the Manager's file picker opens; /sdcard refuses
    # writes for a few seconds after boot_completed ("Operation not permitted")
    *)
        emu_adb shell 'for i in $(seq 1 30); do touch /sdcard/Download/.cam-probe 2>/dev/null &&
            exec rm /sdcard/Download/.cam-probe; sleep 1; done; echo "/sdcard is not writable" >&2; exit 1'
        emu_adb push "$(cygpath -w "$1")" /sdcard/Download/
        ;;
    esac
    emu_adb shell sync
    if [ -f "$RAMDISK" ]; then echo "booted $AVD with the patched ramdisk"; else echo "booted $AVD, stock ramdisk"; fi
}

stop_user() {
    if ! emu_running; then
        echo "$AVD is not running"
        return
    fi
    if [ "$(emu_name)" = "$AVD" ]; then
        # power off inside Android, as the tablet does: /data is flushed and no crash report
        # dialog is left as after `emu kill`; adb exits 255 as the device goes
        if [ "$(emu_adb get-state 2>/dev/null | tr -d '\r')" = device ]; then
            # qemu hangs in about one shutdown out of four (2 of 8 on 2026-10-31) and gets
            # killed below: sync first so /data keeps the last writes anyway
            emu_adb shell sync >/dev/null 2>&1 || true
            emu_adb shell svc power shutdown >/dev/null 2>&1 || true
            wait_exit 45 && return
        fi
        # boot loop or adb offline. Not waited for: when qemu itself hangs (it did once in a
        # shutdown, the console took the connection and never answered) `emu kill` never returns;
        # it ends when the process below is killed
        emu_adb emu kill >/dev/null 2>&1 &
        wait_exit 15 && return
    fi
    # no console either, or launched on another port
    echo "killing $AVD" >&2
    powershell.exe -NoProfile -Command "Stop-Process -Force -Id $(emu_pids | paste -sd,)" || true
    wait_exit 30 || {
        echo "$AVD is still running" >&2
        exit 1
    }
}

# what a user does in the Manager (patch a boot image) and with fastboot, done as the shell user
# with the camd of the installed Manager, so an OTA or an older APK patches with its own LKM
flash_user() {
    emu_running || start_user
    own_port
    local tmp=/data/local/tmp stock apk ok=
    stock=$(stock_ramdisk)
    # look before pushing: nothing may stay behind in /data/local/tmp of a customer-like device
    apk=$(emu_adb shell pm path $PKG | tr -d '\r' | sed -n '1s/^package://p')
    if [ -z "$apk" ]; then
        echo "$PKG is not installed" >&2
        exit 1
    fi
    rm -f "$RAMDISK.new"
    if emu_adb push "$(cygpath -w "$stock")" $tmp/ramdisk-stock.img >/dev/null &&
        emu_adb shell "cd $tmp && ${apk%/base.apk}/lib/x86_64/libcamd.so boot-patch --ramdisk \
            -b ramdisk-stock.img --kmi android16-6.12 -o $tmp --out-name cam-ramdisk.img" &&
        emu_adb pull $tmp/cam-ramdisk.img "$(cygpath -w "$RAMDISK.new")" >/dev/null; then
        ok=1
    fi
    emu_adb shell rm -f $tmp/ramdisk-stock.img $tmp/cam-ramdisk.img || true
    if [ -z "$ok" ]; then
        rm -f "$RAMDISK.new"
        exit 1
    fi
    # the emulator reads the ramdisk once at launch: a reboot inside it would boot the old one
    stop_user
    mv -f "$RAMDISK.new" "$RAMDISK"
    sha256sum "$stock" | cut -d' ' -f1 >"$RAMDISK_SRC"
    start_user
}

if [ -n "$USER_MODE" ]; then
    case "${1:-}" in
    create) create ;;
    start) start_user "${2:-}" ;;
    flash) flash_user ;;
    unflash)
        stop_user
        rm -f "$RAMDISK" "$RAMDISK_SRC"
        start_user
        ;;
    stop) stop_user ;;
    *) usage ;;
    esac
    exit 0
fi

case "${1:-}" in
create) create ;;
start)
    [ -f "$CONFIG" ] || create
    # -port: left to itself the emulator takes the first free port, maybe the user AVD's 5556
    nohup "$SDK/emulator/emulator.exe" -avd "$AVD" -port $PORT -no-snapshot-load "${EMU_FLAGS[@]}" \
        </dev/null >/dev/null 2>&1 &
    wait_boot
    if [ -n "${2:-}" ]; then
        emu_adb install -r -g "$(cygpath -w "$2")"
        emu_adb shell sync
    fi
    load
    ;;
load) load ;;
stop)
    # not `emu kill`: it can leave the "closed unexpectedly" crash report dialog behind
    emu_adb root >/dev/null
    emu_adb wait-for-device
    emu_adb shell reboot -p
    ;;
*) usage ;;
esac
