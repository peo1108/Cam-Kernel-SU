package cam.su.kernel.hiding

/**
 * Duck Detector's SELinux hide timing probe (`attr_timing_probe.cpp`, nightly b77fef8d):
 * an app writes its own context (A) and the same bytes with a leading newline (B) to
 * /proc/thread-self/attr/current. Both are refused, but a kernel hook that parses the
 * context before the permission check refuses A later. [NativeProbe.attrTiming] measures,
 * this decides, with Duck's thresholds so both apps agree.
 */
object AttrTiming {

    /** Duck's CANDIDATE: the median A-B gap over this, in both halves of the run. */
    private const val GAP_NS = 400L

    sealed interface Result

    /** The probe could not run here; [reason] is one of the native failure codes. */
    data class Unavailable(val reason: Long) : Result

    data class Measured(
        val pairs: Int,
        val aMedianNs: Long,
        val bMedianNs: Long,
        val gapMedianNs: Long,
        val firstHalfNs: Long,
        val secondHalfNs: Long,
        /** pairs where A took longer */
        val aSlower: Int,
    ) : Result {
        val leaks: Boolean
            get() = gapMedianNs > GAP_NS && firstHalfNs > GAP_NS && secondHalfNs > GAP_NS && aSlower >= pairs * 9 / 10

        fun describe(): String =
            "A median $aMedianNs ns, B median $bMedianNs ns, gap $gapMedianNs ns ($firstHalfNs/$secondHalfNs), A slower in $aSlower/$pairs"
    }

    /** [samples]: A and B times interleaved, or a single negative failure code. */
    fun evaluate(samples: LongArray?): Result {
        if (samples == null || samples.isEmpty()) return Unavailable(0)
        if (samples.size == 1) return Unavailable(samples[0])
        val a = LongArray(samples.size / 2) { samples[it * 2] }
        val b = LongArray(samples.size / 2) { samples[it * 2 + 1] }
        val gaps = LongArray(a.size) { a[it] - b[it] }
        val half = gaps.size / 2
        return Measured(
            pairs = gaps.size,
            aMedianNs = median(a),
            bMedianNs = median(b),
            gapMedianNs = median(gaps),
            firstHalfNs = median(gaps.copyOfRange(0, half)),
            secondHalfNs = median(gaps.copyOfRange(half, gaps.size)),
            aSlower = gaps.count { it > 0 },
        )
    }

    /** The lower median, as Duck takes it. */
    private fun median(values: LongArray): Long = values.sortedArray()[(values.size - 1) / 2]
}
