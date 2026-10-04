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
## boot shell variables
block=boot;
is_slot_device=auto;
ramdisk_compression=auto;
patch_vbmeta_flag=auto;
no_magisk_check=1;

# import functions/variables and setup patching - see for reference (DO NOT REMOVE)
. tools/ak3-core.sh;

# This Image only boots on the GKI branch it was built for
expected="@KERNEL_VERSION@";
running="$(uname -r)";
case "$running" in
  "$expected".*) ui_print " " "Kernel $running matches $expected";;
  *) abort "Kernel $running is not $expected, refusing to flash this Image";;
esac;

if [ ! -e /data/adb/ksud ]; then
  ui_print " " "Warning: KernelSU (LKM) is not installed." "Root with LKM first, then flash this zip.";
fi;

# boot install: replace only the kernel, keep the ramdisk (and any LKM patch) as is
split_boot;
flash_boot;
## end boot install
