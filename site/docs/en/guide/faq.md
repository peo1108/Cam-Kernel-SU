# FAQ

## Can I use it together with KernelSU, Magisk or APatch?

No. Use one root method at a time. The SU Kernel app does not work with the official KernelSU kernel either. Remove the old root (flash the stock image) first.

## Will it work on my phone?

You need a GKI kernel 5.10 or newer and an unlocked bootloader. Run `adb shell uname -r`: `android12` to `android17` with kernel 5.10 or newer is fine.

## The app says "not installed" after flashing

- Check you flashed the right partition: phones that launched with Android 13+ usually use `init_boot`, older ones `boot`.
- The image must be the stock image of the **exact** ROM you run.
- The app must be the official build from [Download](/en/download). Self-built or differently signed apps get no root.

## Other apps cannot call `su`

On the first boot after flashing, open SU Kernel once so it installs `/data/adb/camd`. Then grant root in the Superuser tab or with `camd allow add <package>`.

## A module is installed but its files are not in /system

You have no metamodule. Install **one** (Magic Mount-rs or Hybrid Mount) from the [module library](/en/modules) and reboot.

## A banking app still detects root

1. Turn on unmount in that app's profile (and do not grant it root).
2. Open the [Root hiding check](./hiding) and see what leaks and which module causes it.
3. If nothing leaks and the app still refuses, it most likely uses **key attestation**: the hardware-signed certificate says the bootloader is unlocked, and no software can change that.

## Do I reflash after every app update?

No. The app updates itself. Only when the home screen shows the **reinstall LKM** button (the kernel part changed) do you reinstall, and **Direct install** does it on the phone.

## Source?

Send a request on the [Source](/en/source) page.
