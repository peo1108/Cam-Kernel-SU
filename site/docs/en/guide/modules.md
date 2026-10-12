# Modules and metamodules

A module is a ZIP that adds or replaces system files, runs scripts at boot, or ships its own web interface (WebUI). Install it from the **Modules** tab: tap install and pick the ZIP, or get one from **Repos**.

## Metamodules

Cam Kernel SU (like KernelSU) **does not mount** modules into `/system` by itself. A **metamodule** does that: a special module with `metamodule=1` in `module.prop`.

- Install **one** metamodule. camd keeps a single one active and links it at `/data/adb/metamodule`.
- Without a metamodule, module scripts still run but module files do not show up in `/system`.
- Two choices in the [library](/en/modules): **Magic Mount-rs** or **Hybrid Mount**. Pick one, not both.

## Module library

The [module library](/en/modules) is a hand-picked list: mounting metamodules, Zygisk (Zygisk Next or ReZygisk, pick one), Play Integrity Fix, bindhosts... It is the same list as **Repos** in the app.

## Notes for module authors

- The data path is `/data/adb/cam`. Modules that hard-code `/data/adb/ksu/...` or `/data/adb/ksud` break.
- Both `KSU*` and `CAM_*` environment variables exist; WebUI has both `ksu` and `cam` bridges.
- A `sepolicy.rule` naming the `ksu` domain applies to `ksu` only, not to `cam`.

## When a module misbehaves

- **Bootloop:** see [Bootloop guard](./features#bootloop-guard).
- **One module breaks another:** see [Conflict finder](./features#conflict-finder).
- **A module update broke something:** the previous version is kept; the **Previous: (version)** badge (or `camd module restore <id>`) brings it back.
- **A module leaks root:** on the first boot after installing or updating a module the app scans once and tells you which module leaks what ([Root hiding check](./hiding)).
