package au.edu.unimelb.floraguide.domain.model

import java.time.Instant
import kotlin.math.abs
import kotlin.math.min

/** A small, explicit domain model keeps Android/framework details outside the ranking logic. */
data class Species(
    val id: String,
    val commonName: String,
    val scientificName: String,
    val preferredMonths: Set<Int>,
    val habitatAffinity: Map<Habitat, Double>,
    val demoNearbyCount: Int,
) {
    fun seasonalPrior(month: Int): Double {
        if (month in preferredMonths) return 1.0
        val nearest = preferredMonths.minOfOrNull { circularMonthDistance(month, it) } ?: 6
        return when (nearest) {
            1 -> 0.72
            2 -> 0.48
            else -> 0.25
        }
    }

    fun habitatPrior(habitat: Habitat): Double = habitatAffinity[habitat] ?: 0.2

    private fun circularMonthDistance(a: Int, b: Int): Int {
        val direct = abs(a - b)
        return min(direct, 12 - direct)
    }
}

enum class Habitat(val label: String, val shortLabel: String) {
    TREE_CANOPY("Under tree canopy", "Canopy"),
    LAWN("Open lawn", "Lawn"),
    GARDEN_BED("Garden bed", "Garden"),
    WATER_EDGE("Wetland or water edge", "Wet edge"),
}

data class ImagePrediction(
    val species: Species,
    val score: Double,
    val rank: Int,
)

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val accuracyMetres: Float? = null,
)

data class SensorAvailability(
    val accelerometer: Boolean = false,
    val gyroscope: Boolean = false,
    val magnetometer: Boolean = false,
    val ambientLight: Boolean = false,
)

data class SensorSnapshot(
    val stability: Double = 0.0,
    val lightLux: Float? = null,
    val headingDegrees: Float? = null,
    val availability: SensorAvailability = SensorAvailability(),
) {
    val isStable: Boolean get() = stability >= 0.6

    val lightAssessment: String
        get() = when (lightLux) {
            null -> "Light sensor unavailable"
            in 0f..<25f -> "Low light"
            in 25f..<20_000f -> "Light looks usable"
            else -> "Possible glare"
        }
}

enum class ContextDataSource(val label: String) {
    ALA_LIVE("Live ALA records"),
    ALA_PARTIAL("Live ALA + demo fallback"),
    DEMO_FALLBACK("Offline demo records"),
}

data class NearbyContext(
    val countsBySpeciesId: Map<String, Int>,
    val source: ContextDataSource,
    val radiusKm: Int,
    val lookupElapsedMillis: Long? = null,
    val successfulRequestCount: Int = 0,
    val requestCount: Int = 0,
    val httpStatusCodes: Set<Int> = emptySet(),
    val warning: String? = null,
)

data class EvidenceBreakdown(
    val imagePrior: Double,
    val locationPrior: Double,
    val seasonalPrior: Double,
    val habitatPrior: Double,
)

data class RankedCandidate(
    val species: Species,
    val relativeScore: Double,
    val imageRank: Int,
    val finalRank: Int,
    val nearbyRecordCount: Int,
    val evidence: EvidenceBreakdown,
)

data class Observation(
    val id: String,
    val species: Species,
    val observedAt: Instant,
    val coarseLocation: GeoPoint,
    val habitat: Habitat,
    val photoPath: String?,
    val headingDegrees: Float?,
    val relativeScore: Double,
    val contextSource: ContextDataSource,
)

enum class AppScreen {
    HOME,
    SCAN,
    RESULTS,
    COLLECTION,
}
