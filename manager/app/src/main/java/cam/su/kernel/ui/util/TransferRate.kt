package cam.su.kernel.ui.util

/**
 * Speed and time left of a job that reports how far it got: measured over the last
 * [windowNanos], so a burst or a short stall does not swing the estimate.
 */
class TransferRate(private val windowNanos: Long = 4_000_000_000L) {
    private val times = ArrayDeque<Long>()
    private val dones = ArrayDeque<Long>()

    /** [done] units at [nowNanos]; a smaller [done] than before is a new job and starts over. */
    fun add(done: Long, nowNanos: Long) {
        if (dones.isNotEmpty() && done < dones.last()) {
            times.clear()
            dones.clear()
        }
        times.addLast(nowNanos)
        dones.addLast(done)
        // keep one sample at or before the window's start, so the window is always spanned
        while (times.size > 2 && times[1] <= nowNanos - windowNanos) {
            times.removeFirst()
            dones.removeFirst()
        }
    }

    /** Units per second, or null until samples span [MIN_SPAN_NANOS]. */
    fun perSecond(): Double? {
        if (times.size < 2) return null
        val span = times.last() - times.first()
        if (span < MIN_SPAN_NANOS) return null
        return (dones.last() - dones.first()) * 1e9 / span
    }

    /** Whole seconds (rounded up) until [total] at the current speed; null when it cannot be told. */
    fun secondsLeft(done: Long, total: Long): Long? {
        if (total <= 0) return null
        val left = total - done
        if (left <= 0) return 0
        val speed = perSecond()?.takeIf { it > 0 } ?: return null
        return kotlin.math.ceil(left / speed).toLong()
    }

    private companion object {
        const val MIN_SPAN_NANOS = 500_000_000L
    }
}
