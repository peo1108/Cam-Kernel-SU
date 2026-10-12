# Root hiding check

The **Root hiding check** page (from the Features tab, or from any app's profile) shows how a banking app or game sees your phone, and names the **module** behind each leak.

It only scans when you tap, never in the background. Each scan is compared with the previous one: **New**, **Unchanged**, **Hidden**.

## Three views

1. **As root** (`camd hiding-audit`): odd mounts, `su` paths, props, SELinux, app profiles, each with the module that caused it.
2. **As a plain app:** a scan in an isolated process, which the kernel always unmounts modules for. It reads files with raw syscalls and compares with libc to catch modules that hook libc. This is what a non-root app really sees.
3. **Per app:** from an app profile, look through that app's own running processes (the app must be open).

The page also lists what is **hidden well**, checks **profiles that skip unmounting**, and runs a **TEE attestation** to explain why an app may still refuse when nothing leaks.

## Quick fix

The **Quick fix** switch turns on kernel unmount, SELinux hiding and bootloader hiding together. Items caused by a module get a button to disable it.

## Automatic check after module changes

Modules only mount on the next boot, so on the first boot after installing, updating or re-enabling one, the app scans once and reports which module leaks what. Tap the notification to open the page. The switch is under **Automatic** (on by default).

## Detection rules

What it looks for comes from a **signed** rule file (ECDSA P-256). Opening the page fetches a newer one at most every 6 hours, accepted only with a valid signature and a higher version. Rules are data, never code.

They follow [Duck Detector](https://github.com/eltavine/Duck-Detector-Refactoring), a widely used root detector; the page shows which Duck Detector commit they track.

## SELinux timing

Some apps time writes to `/proc/self/attr/current` to guess root. The Cam Kernel SU kernel checks permission first, like stock SELinux, so there is no gap. The page has a probe that measures it the way Duck Detector does (results vary on a busy phone).
