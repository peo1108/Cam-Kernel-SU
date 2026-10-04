# sfs branch: GKI + KernelSU + SUSFS

`main` stays LKM. This branch builds a GKI kernel with KernelSU built in
and [SUSFS](https://gitlab.com/simonpunk/susfs4ksu) patched in, shipped as an
AnyKernel3 zip.

Supported: GKI 6.1, 6.6 and 6.12 (6.18 once susfs4ksu has a branch for it).
Older kernels are refused at build time (`kernel/core/init.c`) and by the zip.

## User flow

1. Root with LKM as usual (`main` builds).
2. Manager → Features → **KernelSU GKI** → **Flash AnyKernel3 zip**, pick the
   zip from this branch's CI.
3. Reboot. The same card shows the SUSFS version and enabled features.

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
- **ksud**: `ksud susfs info [--json]` and `ksud flash-ak3 <zip> [--no-backup]`.
  SUSFS itself is driven by simonpunk's `ksu_susfs` tool / module as usual.
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
