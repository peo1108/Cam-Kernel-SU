# sfs branch: GKI + KernelSU + SUSFS

`main` stays LKM. This branch builds a GKI kernel with KernelSU built in
and [SUSFS](https://gitlab.com/simonpunk/susfs4ksu) patched in, shipped as an
AnyKernel3 zip.

Supported: GKI 6.1, 6.6 and 6.12 (6.18 once susfs4ksu has a branch for it).

**KMI generation matters.** A GKI kernel only boots with the vendor modules of
its own KMI generation (the `android16-5` in `6.12.30-android16-5-...`). Flashing
an `android16-6` kernel on an `android16-5` device bootloops: the vendor modules
fail to load before anything is logged. So each KMI is pinned to a GKI release
branch of the right generation (`android16-6.12` → `common-android16-6.12-2025-09`,
generation 5, pinned further to `1750f757fabe`, the exact 6.12.30 commit those
devices ship so every stock module including system_dlkm's matches), zip names carry the generation
(`KernelSU-SFS-android16-5-6.12-<ksu>-susfs-<ver>.zip`), the zip refuses a
running kernel of another generation, and the Manager only offers matching builds.
Older kernels are refused at build time (`kernel/core/init.c`) and by the zip.

## User flow

1. Root with LKM as usual: the Install screen stays LKM only.
2. Manager → Features → **KernelSU GKI** → **GKI install** opens a page laid
   out like the LKM installer:
   - **Direct install**: the project's release builds for the device's KMI
     (newest preselected, tap *Version* to pick another), downloaded and flashed.
   - **Local AnyKernel3 file**: a zip picked on the device.
   - **Install to inactive slot (after OTA)**: a project build or a local zip
     (*Source*), flashed to the other slot (`SLOT_SELECT=inactive`), which then
     becomes active.
   - *Advanced options*: back up boot before flashing (on by default), and
     **SUSFS settings** (below).
   - *Restore kernel*: write a backup back — the previous kernel, or the
     original one kept from before the very first flash.
3. Reboot. The same card shows the SUSFS version and enabled features.

## SUSFS settings (no module needed)

GKI install → *Advanced options* → **SUSFS settings** covers what the
susfs4ksu module (simonpunk's demo and sidex15's WebUI module) does, so the
module is not needed: SUSFS log, AVC log spoofing, hide sus mounts (always /
until boot completed), uname spoofing (stock build date helper, post-fs-data or
boot-completed), fake bootconfig (auto: locked/green from the device's own, or
custom), hide loops / vendor sepolicy / compat matrix / custom ROM (levels 1-5)
/ GApps / ReVanced / LSPosed, emulate vold app data, auto try_umount with legit
mounts, the verified boot props, and the sus_path, sus_path_loop, sus_map,
try_umount, open_redirect and sus_kstat lists (same line formats as the
module's txt files). Settings export to / import from JSON.

ksud keeps them in `/data/adb/ksu/susfs.json` and applies them at the module's
stages: post-fs-data, service, and boot-completed (forked, it waits for
`/sdcard/Android/data` before the sus paths). Saving applies what SUSFS can
take now; removing a hidden path or dropping the fake bootconfig needs a
reboot, and the page offers one. What each boot did is in
`/data/adb/ksu/log/susfs.log` (`ksud susfs log`, or *Log* on the page).
Without a saved file the defaults still apply: log on, hide sus mounts on.
If the susfs4ksu module is installed and enabled, ksud leaves SUSFS to it,
and nothing is applied in safe mode.
Full details (Vietnamese): [SUSFS_SETTINGS.md](SUSFS_SETTINGS.md).

Releases (`release.yml`, tag on this branch) attach
`KernelSU-SFS-<kmi>-<ksu version>-susfs-<susfs version>.zip`; the Manager reads
them from `SFS_RELEASE_REPO` in `ui/util/SfsReleases.kt`. The LKM bundled in a
release of this branch is built from main's `kernel/` (see `ddk-lkm.yml`).

The LKM patch in `init_boot` can stay: `ksuinit` skips loading
`kernelsu.ko` when KernelSU is already in the kernel. Allowlist, app profiles
and modules live in `/data/adb` and carry over.

`ksud flash-ak3` backs up the live boot partition to
`/data/adb/ksu/ak3_backup/boot<slot>.img` before flashing. If the new kernel
does not boot: `fastboot flash boot boot<slot>.img`.

## What differs from main

- **Kernel**: susfs4ksu's `10_enable_susfs_for_ksu.patch`, ported to this fork.
  SUSFS hooks live in the GKI source, so the tracepoint hook manager, symbol
  resolver and LKM/late-load paths are not built. `KSU_GET_INFO_FLAG_SUSFS`
  tells userspace SUSFS is present.
- **ksud**: `ksud susfs info [--json]` and `ksud flash-ak3 <zip> [--no-backup] [--inactive]` and `ksud ak3-backup list|restore <file>`.
  `ksud susfs config|set-config <file>|bootconfig|log` back the SUSFS settings page,
  which replaces simonpunk's `ksu_susfs` tool and the susfs4ksu module.
- **Manager**: the Install screen stays LKM only. Everything GKI lives in the
  **KernelSU GKI** card on the Features tab: kernel and KernelSU mode, SUSFS
  version and features, and flashing an AnyKernel3 zip (GKI 6.1+).
  This branch carries `feat/boot-guard-extras` for the Features tab.
- **CI**: `.github/workflows/build-sfs-kernel.yml` syncs GKI, applies
  `50_add_susfs_in_gki-<kmi>.patch`, copies `kernel/` in, builds with Kleaf and
  packs `sfs/anykernel3/anykernel.sh` with the Image.

## Updating SUSFS

Diff the new `kernel_patches/KernelSU/10_enable_susfs_for_ksu.patch` against
the previous one and port the changes; it targets upstream KernelSU, not this
fork, so it does not apply cleanly as is.

## Before flashing a new build

`sfs/build_local.sh` builds the same zip as CI on a local Linux/WSL box. Try a
new kernel with `fastboot boot` first (nothing is written): repack the device's
own boot image with the new `Image` and boot it once. GKI module protection is
turned off (`protected_module_names_list`), since the device keeps its stock
system_dlkm modules, signed with Google's key rather than ours.
