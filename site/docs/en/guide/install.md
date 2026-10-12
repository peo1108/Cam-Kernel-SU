# Installation

::: danger Read this first
Flashing the wrong boot image can leave the phone unable to boot. Always keep the **stock image** of the exact ROM you are running so you can flash it back, and back up your data first.
:::

## Before you start

- An **unlocked** bootloader.
- [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools) (`adb`, `fastboot`) on your computer.
- The stock **init_boot** image (phones that launched with Android 13+) or **boot** image (older phones) from the firmware of the **exact ROM you are running**.
- The latest SU Kernel app from the [Download](/en/download) page.

### Check your KMI

```sh
adb shell uname -r
```

Output like `5.15.148-android13-8-...` means KMI `android13-5.15`. You rarely need it: the app picks the right kernel module when it patches.

## First install: patch the boot image

1. Install the SU Kernel APK and open it.
2. On the home screen tap **Tap to install**, then **Select a file and patch**.
3. Pick the `init_boot.img` (or `boot.img`) you copied to the phone.
4. **Apps to grant root** opens. SU Kernel is always included; add other apps that should have root on the first boot.
5. Optional: **Block kernel modules** stops some kernel modules from loading at boot (for example a vendor anti-root module). The app pre-fills suspicious loaded modules; edit or clear them.
6. Tap **Next**. The app writes `camsu_patched_<date>_<time>.img` to your Download folder.
7. Copy it to the computer, reboot the phone to fastboot and flash:

```sh
adb reboot bootloader
fastboot flash init_boot camsu_patched_xxx.img   # older phones: fastboot flash boot ...
fastboot reboot
```

8. After boot, **open SU Kernel once**. It installs `/data/adb/camd` the first time; until then other apps cannot call `su`.

::: tip Patching on a computer
`camd` for Windows, Linux and macOS is on the Download page:

```sh
camd boot-patch -b init_boot.img --kmi android14-6.1
```

The patched image lands in the current folder. SU Kernel still gets root because the kernel always roots the `cam.su.kernel` package signed with the official key.
:::

## Already rooted: direct install

When the phone is already rooted (LKM update, or moving from another root method), choose **Direct install (Recommended)**: the app patches the current image and flashes it, no computer needed.

- Before flashing, the app **disables every enabled module** so the first boot on the new version is clean. If disabling one fails, it stops and does not flash.
- On phones with a **self-signed AVB key** (avbroot, `yellow` state) both direct options are hidden: an image patched on the phone is not signed and a locked bootloader would refuse it.

## After an OTA update

An OTA writes the new system to the inactive slot, without root. Once the OTA has installed (**before rebooting**), open SU Kernel and choose **Install to inactive slot (After OTA)**, then reboot.

## Updating the app and the kernel module

- The app checks for releases every 12 hours, notifies you, verifies the signature and installs with root.
- The **reinstall LKM** button on the home screen only shows when the kernel part actually changed. No button, no reflash.

## Uninstall or recover

- **Module bootloop:** after 3 failed boots in a row camd disables the newest modules ([Features tab](./features#bootloop-guard)). If it fails before `post-fs-data`, use safe mode (hold a volume key while booting).
- **Go back:** each flash keeps the previous image in `/data/adb/cam/previous/<partition>.img`. For the first install use your stock image:

```sh
fastboot flash init_boot init_boot.img
```

- **Remove completely:** flash the stock image and uninstall SU Kernel.
