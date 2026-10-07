package au.edu.unimelb.floraguide.domain.usecase

import au.edu.unimelb.floraguide.domain.model.GeoPoint

/** Prototype settings, not Android or ALA requirements. Uses monotonic time, not wall-clock time. */
class LocationFreshnessPolicy(
    private val maxAgeMillis: Long = 60_000L,
    private val maxAccuracyMetres: Float = 2_000f,
    /**
     * Android sends approximate-only apps about one fix every 10 minutes, already blurred to a
     * grid of about 2 km, so those fixes stay usable for longer. See LOCATION_VALIDATION.md.
     */
    private val approximateMaxAgeMillis: Long = 15 * 60_000L,
) {
    init {
        require(maxAgeMillis > 0 && approximateMaxAgeMillis >= maxAgeMillis)
        require(maxAccuracyMetres.isFinite() && maxAccuracyMetres > 0)
    }
    fun isUsable(point: GeoPoint, fixElapsedNanos: Long, nowElapsedNanos: Long, approximate: Boolean = false): Boolean {
        if (!point.hasValidCoordinates() || fixElapsedNanos <= 0 || nowElapsedNanos < fixElapsedNanos) return false
        val accuracy = point.accuracyMetres ?: return false
        if (!accuracy.isFinite() || accuracy < 0 || accuracy > maxAccuracyMetres) return false
        return (nowElapsedNanos - fixElapsedNanos) / 1_000_000L <= maxAge(approximate)
    }

    /** Milliseconds until [isUsable] fails on age alone; zero once the fix is at or past the limit. */
    fun millisUntilStale(fixElapsedNanos: Long, nowElapsedNanos: Long, approximate: Boolean = false): Long =
        (maxAge(approximate) - (nowElapsedNanos - fixElapsedNanos) / 1_000_000L).coerceAtLeast(0L)

    /**
     * A newer fix from the same provider always wins. Across providers (GPS vs network) a coarser
     * fix only replaces a more accurate one once that one is no longer usable.
     */
    fun shouldReplace(
        current: GeoPoint,
        currentFixNanos: Long,
        candidate: GeoPoint,
        candidateFixNanos: Long,
        sameProvider: Boolean,
        nowElapsedNanos: Long,
        approximate: Boolean = false,
    ): Boolean {
        if (candidateFixNanos < currentFixNanos) return false
        if (sameProvider || !isUsable(current, currentFixNanos, nowElapsedNanos, approximate)) return true
        return (candidate.accuracyMetres ?: Float.MAX_VALUE) <= (current.accuracyMetres ?: Float.MAX_VALUE)
    }

    private fun maxAge(approximate: Boolean) = if (approximate) approximateMaxAgeMillis else maxAgeMillis
}
