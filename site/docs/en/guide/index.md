# Introduction

**Cam Kernel SU** puts root inside the Android kernel. Its app is called **SU Kernel**.

## In the kernel

Root lives in a kernel module (`camsu.ko`) loaded from your `init_boot` or `boot` image.

- `/system` is never modified. Modules are mounted at boot.
- The kernel decides which apps get root, from the **app profiles** you set.
- Apps without root do not see modules.

## What's in it

- A glass interface, with slimes roaming the app.
- [Bootloop guard](./features#bootloop-guard) and [conflict finder](./features#conflict-finder).
- [Bootloader hiding](./features#bootloader-hiding).
- [Root hiding check](./hiding): look at the phone the way a plain app does.
- Updates itself.

The daemon is `camd`, data lives in `/data/adb/cam`. Modules written for KernelSU still work.

::: warning One root at a time
Remove KernelSU, Magisk or APatch first.
:::

## Supported devices

- **GKI kernel 5.10 or newer**: android12-5.10, android13-5.10, android13-5.15, android14-5.15, android14-6.1, android15-6.6, android16-6.12, android17-6.18.
- `aarch64` and `x86_64`.
- An unlocked bootloader.

Next: [Installation](./install).
