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
# shellcheck disable=SC2016 # single-quoted commands run in the device's shell
set -eu

AVD=CamY700Gen5
IMAGE="system-images;android-36.1;google_apis;x86_64"
PKG=cam.su.kernel

: "${LOCALAPPDATA:?run this from Git Bash on Windows}"
SDK=$(cygpath -u "$LOCALAPPDATA")/Android/Sdk
emu_adb() { "$SDK/platform-tools/adb.exe" -s emulator-5554 "$@"; } # the tablet may be attached too
CONFIG=$(cygpath -u "$USERPROFILE")/.android/avd/$AVD.avd/config.ini
export MSYS_NO_PATHCONV=1

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
    echo "created $AVD"
}

wait_boot() {
    emu_adb wait-for-device
    emu_adb shell 'until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 1; done'
}

load() {
    emu_adb root >/dev/null
    emu_adb wait-for-device
    wait_boot
    # allow_shell: adb shell can use su, as scripts/device_*.sh expect
    emu_adb shell "P=\$(pm path $PKG | sed 's/package://'); \${P%/base.apk}/lib/x86_64/libcamd.so late-load --allow-shell"
    emu_adb shell 'for i in $(seq 1 30); do [ -x /data/adb/camd ] && grep -q camsu /proc/modules && exit 0; sleep 1; done; echo "camsu did not load" >&2; exit 1'
    emu_adb shell wm density 374
    emu_adb shell '/data/adb/camd debug info | grep "^version"; uname -r'
}

case "${1:-}" in
create) create ;;
start)
    [ -f "$CONFIG" ] || create
    # Without GLDirectMem this image has no ReadColorBufferDma: surfaceflinger aborts in
    # mapper.ranchu every 5 s and system_server goes with it, even with a software GPU.
    # Without GLESDynamicVersion it reports GLES 2.0: the Filament slime material has no
    # package for that level, so the Manager aborts in Material::Builder::build on launch.
    # -screen: emulator 37.2 starts this AVD with hw.screen=touch whatever config.ini says, and
    # then the guest has no touch device at all, so the mouse does nothing in the window.
    nohup "$SDK/emulator/emulator.exe" -avd "$AVD" -no-snapshot-load -no-boot-anim -gpu host -screen multi-touch \
        -feature GLDMA,GLDirectMem,HasSharedSlotsHostMemoryAllocator,GLESDynamicVersion </dev/null >/dev/null 2>&1 &
    wait_boot
    if [ -n "${2:-}" ]; then
        emu_adb install -r -g "$2"
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
*)
    sed -n '2,13p' "$0"
    exit 2
    ;;
esac
