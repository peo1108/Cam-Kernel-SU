# Reporting Security Issues

Cam Kernel SU is a fork of [KernelSU](https://github.com/tiann/KernelSU) maintained separately. Please report security problems in this fork privately through GitHub's ["Report a vulnerability"](https://github.com/peo1108/Cam-Kernel-SU/security/advisories/new) form, not in a public issue.

Useful to include: the Cam Kernel SU version (Manager and kernel, both shown on the Home page), the device and its KMI, and the steps to reproduce.

Areas that are specific to this fork and most worth a report:

- the kernel granting root by package name (`kernel/policy/pkg_tracker.c`: the patch-time seed and the Manager pin),
- the root hiding check and its signed rules (`manager/app/src/main/assets/hiding-rules.json`),
- anything that lets an app without root detect or reach root.

If the problem is also in upstream KernelSU, report it to KernelSU as well, through [its advisory form](https://github.com/tiann/KernelSU/security/advisories/new).

You will get an answer saying what happens next; reports are handled by one maintainer, so it may take a few days.
