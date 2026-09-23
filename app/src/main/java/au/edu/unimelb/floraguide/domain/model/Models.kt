package au.edu.unimelb.floraguide.domain.model

import au.edu.unimelb.floraguide.domain.sensor.MotionStabilityEstimator
import java.time.Instant
import kotlin.math.abs
import kotlin.math.min

/** Framework-independent domain types shared by camera, Pl@ntNet, ALA and persistence. */
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

/** Where a candidate list came from. Fallbacks must stay visible in the UI. */
enum class ImageSource(val label: String) {
    PLANTNET_LIVE("Pl@ntNet cloud model"),
    DEMO_ADAPTER("Prototype image adapter"),
}

data class ImageClassification(
    val predictions: List<ImagePrediction>,
    val source: ImageSource,
    val elapsedMillis: Long? = null,
)

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val accuracyMetres: Float? = null,
) {
    fun hasValidCoordinates(): Boolean =
        latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
}

enum class CaptureLocationSource(val label: String) {
    DEVICE("Device location at capture"),
    GUIDED_DEMO("Guided demo location"),
    UNAVAILABLE("No usable capture location"),
    LEGACY_UNKNOWN("Legacy location; origin not recorded"),
}

/** Snapshot created when camera capture completes. Retries reuse the same time/location. */
data class CaptureSnapshot(
    val observationId: String,
    val capturedAt: Instant,
    val location: GeoPoint?,
    val locationSource: CaptureLocationSource,
    val headingDegrees: Float?,
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
    val compassNeedsCalibration: Boolean = false,
    val availability: SensorAvailability = SensorAvailability(),
) {
    /** The stability score only moves once both motion sensors report. */
    val canMeasureStability: Boolean
        get() = availability.accelerometer && availability.gyroscope

    val isStable: Boolean get() = MotionStabilityEstimator.isStable(stability)

    /** Heading worth recording with an observation; null while the compass is uncalibrated. */
    val reliableHeadingDegrees: Float?
        get() = headingDegrees.takeUnless { compassNeedsCalibration }

    // ponytail: the compass models the rear camera only; omit front-camera bearings until supported.
    fun headingForCapture(isRearCamera: Boolean): Float? = reliableHeadingDegrees.takeIf { isRearCamera }

    val lightCondition: LightCondition
        get() = when {
            lightLux == null -> LightCondition.UNAVAILABLE
            lightLux < LOW_LIGHT_LUX -> LightCondition.LOW
            lightLux < VERY_BRIGHT_LUX -> LightCondition.USABLE
            else -> LightCondition.VERY_BRIGHT
        }

    // Prototype thresholds pending field calibration; see docs/HARDWARE_ADAPTERS_VERIFICATION.md.
    companion object {
        /** Dimmer than a typical living room; handheld shots need long exposures. */
        const val LOW_LIGHT_LUX = 25f

        /** Inside the 10,000–25,000 lux band of full daylight; direct sun reads higher. */
        const val VERY_BRIGHT_LUX = 20_000f
    }
}

/**
 * Ambient light around the phone. The sensor sits beside the front display, so this describes
 * the light falling on the user rather than exposure of the scene the rear camera sees.
 */
enum class LightCondition {
    UNAVAILABLE,
    LOW,
    USABLE,
    VERY_BRIGHT,
}

enum class ContextDataSource(val label: String) {
    ALA_LIVE("Live ALA records"),
    ALA_PARTIAL("Partial ALA results; missing counts unknown"),
    ALA_UNAVAILABLE("ALA unavailable; image-only ranking"),
    NOT_REQUESTED("ALA not queried"),
    LEGACY_UNVERIFIED("Legacy context; provenance unverified"),
    DEMO_FALLBACK("Explicit offline demo records"),
}

data class NearbyContext(
    /** Successful results only. Missing key means unknown; value 0 means a successful zero. */
    val countsBySpeciesId: Map<String, Int>,
    val source: ContextDataSource,
    val radiusKm: Int,
    val lookupElapsedMillis: Long? = null,
    val successfulRequestCount: Int = 0,
    val requestCount: Int = 0,
    val httpStatusCodes: Set<Int> = emptySet(),
    val warning: String? = null,
    val failuresBySpeciesId: Map<String, String> = emptyMap(),
    val attemptsBySpeciesId: Map<String, Int> = emptyMap(),
    val queriedAt: Instant? = null,
    val retryNotBefore: Instant? = null,
)

data class EvidenceBreakdown(
    val imagePrior: Double,
    val locationPrior: Double,
    val seasonalPrior: Double,
    val habitatPrior: Double,
    val locationMultiplier: Double = 1.0,
)

data class RankedCandidate(
    val species: Species,
    val relativeScore: Double,
    val imageRank: Int,
    val finalRank: Int,
    val nearbyRecordCount: Int?,
    val evidence: EvidenceBreakdown,
)

data class CandidateEvidenceRecord(
    val scientificName: String,
    val imageScore: Double,
    val finalRelativeScore: Double,
    val nearbyRecordCount: Int?,
    val lookupFailure: String? = null,
)

data class Observation(
    val id: String,
    val species: Species,
    val observedAt: Instant,
    /** Kept non-null to remain compatible with the current Room/Firestore schema. */
    val coarseLocation: GeoPoint,
    val habitat: Habitat,
    val photoPath: String?,
    val headingDegrees: Float?,
    val relativeScore: Double,
    val contextSource: ContextDataSource,
    val cloudPhotoUri: String? = null,
    val imageScore: Double? = null,
    val imageSource: ImageSource? = null,
    val locationSource: CaptureLocationSource = CaptureLocationSource.LEGACY_UNKNOWN,
    val confirmedAt: Instant? = null,
    val nearbyRecordCount: Int? = null,
    val contextRadiusKm: Int? = null,
    val contextQueriedAt: Instant? = null,
    val rankingRule: String? = null,
    val candidateEvidence: List<CandidateEvidenceRecord> = emptyList(),
    val verificationStatus: String = "USER_SELECTED_UNVERIFIED",
)

enum class AppScreen {
    HOME,
    SCAN,
    RESULTS,
    COLLECTION,
    ACCOUNT,
}
