package cam.su.kernel.ui.component.skeleton

/** A first load faster than this shows no skeleton at all (no flash of placeholder cards). */
internal const val SKELETON_DELAY_MS = 150L

/** Once shown, the skeleton stays at least this long, so it does not blink away. */
internal const val SKELETON_MIN_SHOW_MS = 300L

/**
 * Whether the skeleton is on screen at [now] for a first load that started at [startedAt] and
 * finished at [finishedAt] (null while it runs). Times in milliseconds on one clock.
 */
internal fun skeletonShown(
    startedAt: Long,
    finishedAt: Long?,
    now: Long,
    delayMs: Long = SKELETON_DELAY_MS,
    minShowMs: Long = SKELETON_MIN_SHOW_MS,
): Boolean {
    val appearAt = startedAt + delayMs
    if (finishedAt != null && finishedAt <= appearAt) return false
    if (now < appearAt) return false
    if (finishedAt == null) return true
    return now < maxOf(finishedAt, appearAt + minShowMs)
}

/**
 * When [skeletonShown] next changes on its own, or null when only the load finishing can change
 * it (or nothing will).
 */
internal fun skeletonNextChange(
    startedAt: Long,
    finishedAt: Long?,
    now: Long,
    delayMs: Long = SKELETON_DELAY_MS,
    minShowMs: Long = SKELETON_MIN_SHOW_MS,
): Long? {
    val appearAt = startedAt + delayMs
    if (finishedAt != null && finishedAt <= appearAt) return null
    if (now < appearAt) return appearAt
    if (finishedAt == null) return null
    val hideAt = maxOf(finishedAt, appearAt + minShowMs)
    return if (now < hideAt) hideAt else null
}
