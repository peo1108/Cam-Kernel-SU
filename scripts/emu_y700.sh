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
# Start the AVD from here, not from Android Studio: its launch has none of EMU_FLAGS below
# (surfaceflinger aborts, no touch device). An AVD that is already up is used as it is, on
# whatever port it runs; a missing touch device gets a warning.
# shellcheck disable=SC2016 # single-quoted commands run in the device's shell
set -eu

AVD=CamY700Gen5
IMAGE="system-images;android-36.1;google_apis;x86_64"
EMU_PORT=5554
# the console port the commands below talk to; own_port moves it to where $AVD runs
PORT=$EMU_PORT
PKG=cam.caesar.vn

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
export MSYS_NO_PATHCONV=1

# Without GLDirectMem this image has no ReadColorBufferDma: surfaceflinger aborts in
# mapper.ranchu every 5 s and system_server goes with it, even with a software GPU.
# Without GLESDynamicVersion it reports GLES 2.0: the Filament slime material has no
# package for that level, so the Manager aborts in Material::Builder::build on launch.
# -screen: emulator 37.2 starts this AVD with hw.screen=touch whatever config.ini says, and
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

# another AVD can sit on emulator-$PORT: never install, load or stop that one
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
    # one short adb call per try, not wait-for-device: adbd can restart during a first boot and
    # drop the shell (exit 255), and an emulator that died must not hang this
    local deadline=$((SECONDS + 600)) look=0 out
    until out=$(quick_adb shell getprop sys.boot_completed 2>&1 | tr -d '\r'); [ "$out" = 1 ]; do
        if [ $SECONDS -ge $deadline ]; then
            echo "$AVD did not finish booting in 10 minutes${out:+ (adb: $out)}" >&2
            exit 1
        fi
        # anything but an empty getprop is an adb error ("device 'emulator-5554' not found",
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

# what a launch from elsewhere leaves out; the emulator rewrites hardware-qemu.ini every launch
warn_launch() {
    if grep -q '^hw\.screen = touch' "$AVD_DIR/hardware-qemu.ini" 2>/dev/null; then
        # no -screen, so most likely no -feature either
        echo "$AVD was not started with this script's flags: the mouse does nothing in its window, and without -feature it never finishes booting. Stop it and start it from here" >&2
    fi
}

# boot $AVD unless it runs already, then wait for Android
boot() {
    [ -f "$CONFIG" ] || create
    local found=0 name
    locate || found=$?
    case $found in
    0)
        # already up (started earlier or elsewhere): only install
        warn_launch
        wait_boot
        ;;
    1)
        # -port: left to itself the emulator takes the first free port
        PORT=$EMU_PORT
        name=$(emu_name)
        if [ -n "$name" ]; then
            echo "emulator-$PORT is taken by $name: stop it first" >&2
            exit 1
        fi
        # Start-Process, not nohup: a child of this console dies when its window is closed. No
        # argument has a space, so they go unquoted in one string
        powershell.exe -NoProfile -Command "Start-Process -WindowStyle Hidden \
            -FilePath '$(cygpath -w "$SDK/emulator/emulator.exe")' \
            -ArgumentList '-avd $AVD -port $PORT -no-snapshot-load ${EMU_FLAGS[*]}'"
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
            # qemu hangs in some shutdowns (1 of 15 on 2026-10-31) and gets killed below: sync
            # first so /data keeps the last writes anyway
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

# 0: camsu is loaded. 1: it is not. Anything else: adb could not tell
camsu_loaded() { quick_adb shell 'grep -q "^camsu " /proc/modules'; }

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
