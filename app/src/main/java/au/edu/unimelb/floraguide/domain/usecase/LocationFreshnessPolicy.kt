package au.edu.unimelb.floraguide.domain.usecase

import au.edu.unimelb.floraguide.domain.model.GeoPoint

/** Prototype settings, not Android or ALA requirements. Uses monotonic time, not wall-clock time. */
class LocationFreshnessPolicy(
    private val maxAgeMillis: Long = 60_000L,
    private val maxAccuracyMetres: Float = 2_000f,
) {
    init {
        require(maxAgeMillis > 0 && maxAccuracyMetres.isFinite() && maxAccuracyMetres > 0)
    }
    fun isUsable(point: GeoPoint, fixElapsedNanos: Long, nowElapsedNanos: Long): Boolean {
        if (!point.hasValidCoordinates() || fixElapsedNanos <= 0 || nowElapsedNanos < fixElapsedNanos) return false
        val accuracy = point.accuracyMetres ?: return false
        if (!accuracy.isFinite() || accuracy < 0 || accuracy > maxAccuracyMetres) return false
        return (nowElapsedNanos - fixElapsedNanos) / 1_000_000L <= maxAgeMillis
    }

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
    ): Boolean {
        if (candidateFixNanos < currentFixNanos) return false
        if (sameProvider || !isUsable(current, currentFixNanos, nowElapsedNanos)) return true
        return (candidate.accuracyMetres ?: Float.MAX_VALUE) <= (current.accuracyMetres ?: Float.MAX_VALUE)
    }
}
