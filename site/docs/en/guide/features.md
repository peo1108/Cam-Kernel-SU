# Features tab

The **Features** tab (between Modules and Settings) collects what SU Kernel adds.

## Bootloop guard

camd counts boots that never reach `boot-completed`. On the 3rd failure in a row it disables modules that were **just installed, updated or re-enabled**; if there are none, it disables every enabled module.

- The home screen shows a red card and a dialog to **Enable again** each module; module cards get a **Disabled by boot guard** badge.
- A system notification tells you when it stepped in.
- The card sets the failure count (1 to 4), whether to disable suspects first or everything, and lists disabled modules.

::: warning Limits
It cannot help with loops **before** `post-fs-data` (kernel, `init`, or a metamodule mounting too early). Use safe mode with a volume key then.
:::

## Conflict finder

camd finds files (whiteouts included), `.replace` folders and `system.prop` props that **several modules change**.

- Module cards show a **Conflict** badge; the install screen warns you.
- Toggle, install-time warnings, props, the list and a rescan button are in the card. From a shell: `camd module conflicts`.

A conflict is not an error; it explains why one module can break another. Which one wins depends on the metamodule's mount order.

## Bootloader hiding

At `post-fs-data` and `boot-completed`, camd sets props that reveal an unlocked bootloader (`ro.boot.verifiedbootstate`, `ro.boot.vbmeta.device_state`...) to locked values. If the bootloader leaves `ro.boot.vbmeta.digest` empty, the app fills in the real value computed from your own partitions.

- **Check attestation** creates a key in AndroidKeyStore and reads RootOfTrust, showing what an app reading certificates sees.

::: info Key attestation cannot be changed
Apps that use **key attestation** (a certificate signed by the secure hardware) still see an unlocked bootloader. Changing props cannot alter it.
:::

## Wireless ADB

Turns on adbd on port 5555 with root (like `adb tcpip`), no pairing. The card shows `adb connect <Wi-Fi IP>:5555` to copy.

- Turns itself off after 15, 30, 60, 120 minutes or never (default 30). Rebooting closes it too.
- While on, banking apps can see it (the `adb` item on the root hiding check page).

## Root hiding check

Opened from this tab: see [Root hiding check](./hiding).
