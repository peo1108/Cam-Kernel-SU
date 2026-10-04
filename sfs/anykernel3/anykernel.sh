### AnyKernel3 Ramdisk Mod Script
## KernelSU SFS: built-in KernelSU + SUSFS for GKI @KMI@
## osm0sis @ xda-developers

### AnyKernel setup
# global properties
properties() { '
kernel.string=KernelSU SFS @KMI@ (@KSU_VERSION@, SUSFS @SUSFS_VERSION@)
do.devicecheck=0
do.modules=0
do.systemless=0
do.cleanup=1
do.cleanuponabort=0
device.name1=
supported.versions=
supported.patchlevels=
supported.vendorpatchlevels=
'; } # end properties

### AnyKernel install
# boot shell variables
# SLOT_SELECT comes from the environment: ksud flash-ak3 --inactive sets it
BLOCK=boot;
IS_SLOT_DEVICE=auto;
RAMDISK_COMPRESSION=auto;
PATCH_VBMETA_FLAG=auto;
NO_MAGISK_CHECK=1;

# import functions/variables and setup patching - see for reference (DO NOT REMOVE)
. tools/ak3-core.sh;

# This Image only boots on the GKI branch and KMI generation it was built for:
# with another generation (e.g. android16-6 on an android16-5 device) the vendor
# modules do not load and the device bootloops
expected="@KERNEL_VERSION@";
expected_kmi="@KMI_TAG@";
running="$(uname -r)";
case "$running" in
  "$expected".*) ;;
  *) abort "Kernel $running is not $expected, refusing to flash this Image";;
esac;
case "$running" in
  *-"$expected_kmi"-*|*-"$expected_kmi") ui_print " " "Kernel $running matches $expected ($expected_kmi)";;
  *) abort "Kernel $running is not KMI $expected_kmi, refusing to flash this Image";;
esac;

if [ ! -e /data/adb/ksud ]; then
  ui_print " " "Warning: KernelSU (LKM) is not installed." "Root with LKM first, then flash this zip.";
fi;

# boot install: replace only the kernel, keep the ramdisk (and any LKM patch) as is
split_boot;
flash_boot;
## end boot install
