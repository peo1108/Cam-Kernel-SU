# Granting root

## In the app

Open the **Superuser** tab, pick an app and turn root on. Each app has its own **app profile**:

- **Root:** the UID, groups, capabilities and SELinux context the app gets when it calls `su`. The defaults suit most apps.
- **Non-root:** whether to **unmount modules** for that app. Turn it on so banking apps and games do not see module files.

The profile page also has **Info** (paths, tap to copy), **Storage** and **Manage** cards: back up the APK, clear cache or data, freeze, uninstall (system apps too).

## From adb or Termux

From a root shell:

```sh
camd allow add com.termux        # grant root
camd allow remove com.termux     # revoke root
camd allow list                  # apps that have root
```

## While patching

**Apps to grant root** during install gives apps root on the very first boot (see [Installation](./install#first-install-patch-the-boot-image)).

## Root for SU Kernel itself

The kernel always roots the `cam.su.kernel` package, **only if** it is signed with the project's official key. It reads the signature Android verified at install time (`/data/system/packages.xml`) and never opens the APK.

- Uninstalling and reinstalling the app keeps root.
- A fake app with the same package name gets no root; if it takes over a UID that had root, root is revoked.
- Self-built apps or apps signed with another key get **no** root.
