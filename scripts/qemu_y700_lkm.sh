#!/usr/bin/env bash
# Boots a real arm64 kernel in QEMU with caminit as /init, the way a patched ramdisk does, so
# the arm64 hooks of camsu.ko run on the tablet's own kernel without touching the tablet.
# There is no Android and no SELinux policy: it proves the LKM loads and that the supercall and
# syscall hooks do not crash (CFI included). It says nothing about selinux_hide, su domains,
# or timing (the CPU is emulated). About 15 s.
#
#   scripts/qemu_y700_lkm.sh BOOT_IMG CAMSU_KO CAMINIT [TIMEOUT_SECONDS]
#
# BOOT_IMG: the ROM's boot.img, whose kernel must match the KMI of the .ko
# CAMSU_KO, CAMINIT: the CI artifacts aarch64-android16-6.12-lkm and caminit-aarch64
# Run it in WSL, as root (mknod). Needs qemu-system-arm, gcc-aarch64-linux-gnu, cpio.
# Exit 0 only when the test program finished and the kernel log shows no crash.
set -eu

BOOT_IMG=$1
CAMSU_KO=$2
CAMINIT=$3
TIMEOUT=${4:-300}
REPO=$(cd "$(dirname "$0")/.." && pwd)

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
RD=$WORK/rd
mkdir -p "$RD/proc" "$RD/sys" "$RD/dev" "$RD/system/bin"

# boot image header: page size 4096 up to v4, kernel size at byte 8, kernel in the next page
python3 -I - "$BOOT_IMG" "$WORK/Image" <<'PY'
import struct, sys
image = open(sys.argv[1], 'rb').read()
kernel_size = struct.unpack_from('<I', image, 8)[0]
kernel = image[4096:4096 + kernel_size]
assert image[:8] == b'ANDROID!' and kernel[56:60] == b'ARM\x64', 'not an arm64 boot image'
open(sys.argv[2], 'wb').write(kernel)
PY

aarch64-linux-gnu-gcc -static -O1 -w -I"$REPO" -I"$REPO/uapi" "$REPO/scripts/qemu_lkm_test.c" -o "$RD/init.real"
install -m 755 "$CAMINIT" "$RD/init"
chmod 755 "$RD/init.real"
cp "$CAMSU_KO" "$RD/camsu.ko"
mknod "$RD/dev/console" c 5 1
mknod "$RD/dev/null" c 1 3
mknod "$RD/dev/kmsg" c 1 11
(cd "$RD" && find . | cpio -o -H newc --quiet >"$WORK/init.cpio")

LOG=$WORK/boot.log
timeout "$TIMEOUT" qemu-system-aarch64 -M virt,gic-version=3 -cpu max,pauth-impdef=on,sve=off \
    -accel tcg,thread=multi -smp 4 -m 2048 -nographic -no-reboot \
    -kernel "$WORK/Image" -initrd "$WORK/init.cpio" \
    -append "console=ttyAMA0 earlycon=pl011,0x09000000 loglevel=7 panic=-1 rdinit=/init" \
    >"$LOG" 2>&1 || echo "qemu exit $?"

grep -E '(^|[^A-Z])CT |Kernel panic|CFI failure|Unable to handle|Internal error|WARNING:|BUG:' "$LOG" | cut -c1-160
if grep -qE 'Kernel panic|CFI failure|Unable to handle|Internal error|BUG:' "$LOG" || ! grep -q 'CT DONE' "$LOG"; then
    echo "FAIL (last lines of the log follow)"
    tail -n 30 "$LOG" | cut -c1-160
    exit 1
fi
echo PASS
