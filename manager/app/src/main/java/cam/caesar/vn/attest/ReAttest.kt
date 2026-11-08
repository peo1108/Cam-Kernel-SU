package cam.caesar.vn.attest

import cam.caesar.vn.Cam

/**
 * After a config push, make the target apps re-attest through the TA — an app that cached a real
 * hardware attest key would otherwise keep serving it. [installedTargets] picks only the configured
 * uids that are actually installed, never an unrelated uid, so the purge can never touch a key that
 * is not a target's (the safety invariant).
 *
 * The purge itself ([reAttest]) restarts keystore2: the daemon re-injects and targets re-attest on
 * next use. A per-owner key delete (as TEESimulator does, via seteuid → keystore2) is a refinement
 * for apps that still serve a stale key afterwards — verified when a real keybox is available.
 */
fun installedTargets(configured: List<Int>, installed: Set<Int>): List<Int> =
    configured.filter { it in installed }.distinct()

/** Ask the root service to make the given installed targets re-attest. Returns how many it acted for. */
fun reAttest(targets: List<Int>): Int =
    if (targets.isEmpty()) 0 else Cam.purgeTargetAttestKeys(targets.toIntArray())
