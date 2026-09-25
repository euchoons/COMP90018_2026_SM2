package au.edu.unimelb.floraguide.domain.usecase

import au.edu.unimelb.floraguide.domain.model.ContextDataSource
import au.edu.unimelb.floraguide.domain.model.EvidenceBreakdown
import au.edu.unimelb.floraguide.domain.model.Habitat
import au.edu.unimelb.floraguide.domain.model.ImagePrediction
import au.edu.unimelb.floraguide.domain.model.NearbyContext
import au.edu.unimelb.floraguide.domain.model.PredictedOrgan
import au.edu.unimelb.floraguide.domain.model.RankedCandidate
import au.edu.unimelb.floraguide.domain.model.circularMonthDistance
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p

/** Live evidence and the existing synthetic demo use deliberately separate entry points. */
class RankSpeciesCandidatesUseCase(
    private val imageWeight: Double = 1.0,
    private val locationWeight: Double = 0.75,
    private val seasonWeight: Double = 0.35,
    private val habitatWeight: Double = 0.45,
    private val locationSmoothing: Double = 3.0,
    private val maximumLiveBoost: Double = 0.15,
    private val liveCountSaturation: Int = 50,
    /** Documented flowering months by exact scientific name; empty until #16 supplies sourced data. */
    private val floweringMonths: Map<String, Set<Int>> = emptyMap(),
    private val outOfSeasonMultiplier: Double = 0.85,
    private val minimumFlowerScore: Double = 0.5,
) {
    init {
        require(maximumLiveBoost in 0.0..0.5 && liveCountSaturation > 0)
        require(locationSmoothing > 0.0)
        require(outOfSeasonMultiplier in 0.5..1.0 && minimumFlowerScore in 0.0..1.0)
        // An empty set would read as "never flowers" and lower every flower photo.
        require(floweringMonths.values.all { months -> months.isNotEmpty() && months.all { it in 1..12 } })
    }

    /**
     * Coursework heuristic, not a trained or calibrated probability model.
     * support = log(1 + min(count, 50)) / log(51)
     * weight = originalImageScore * (1 + 0.15 * support) * season
     * Normalise within the candidate set. Zero records apply no penalty.
     * Incomplete ALA context disables geographic support for every candidate, not just the failed ones.
     * season is 0.85 only for a photographed flower more than a month outside the candidate's
     * documented flowering months; other organs, a missing date and unlisted species stay neutral.
     */
    fun live(
        predictions: List<ImagePrediction>,
        context: NearbyContext,
        captureMonth: Int? = null,
        organ: PredictedOrgan? = null,
    ): List<RankedCandidate> {
        require(captureMonth == null || captureMonth in 1..12) { "Invalid capture month" }
        val baseline = imageOnly(predictions)
        if (baseline.isEmpty()) return baseline
        val complete = context.source == ContextDataSource.ALA_LIVE && predictions.all {
            (context.countsBySpeciesId[it.species.id] ?: -1) >= 0
        }
        // Flowering months say nothing about a leaf, bark or whole-plant photo.
        val flowerMonth = captureMonth.takeIf {
            organ != null && organ.organ.equals("flower", ignoreCase = true) && organ.score >= minimumFlowerScore
        }
        val components = baseline.map { candidate ->
            val count = context.countsBySpeciesId[candidate.species.id]?.takeIf { it >= 0 }
            val support = if (complete && count != null) {
                ln1p(count.coerceAtMost(liveCountSaturation).toDouble()) / ln1p(liveCountSaturation.toDouble())
            } else 0.0
            val multiplier = 1.0 + maximumLiveBoost * support
            val season = seasonMultiplier(candidate.species.scientificName, flowerMonth)
            val value = candidate.evidence.imagePrior * multiplier * season
            candidate.copy(
                nearbyRecordCount = count,
                evidence = candidate.evidence.copy(
                    locationPrior = support,
                    locationMultiplier = multiplier,
                    seasonMultiplier = season,
                ),
            ) to value
        }
        val total = components.sumOf { it.second }
        return components.sortedWith(
            compareByDescending<Pair<RankedCandidate, Double>> { it.second }.thenBy { it.first.imageRank },
        ).mapIndexed { index, (candidate, value) ->
            candidate.copy(relativeScore = value / total, finalRank = index + 1)
        }
    }

    /** Only a documented mismatch lowers a candidate; unlisted species count the same as in season. */
    private fun seasonMultiplier(scientificName: String, flowerMonth: Int?): Double {
        val months = floweringMonths[scientificName] ?: return 1.0
        if (flowerMonth == null || months.any { circularMonthDistance(flowerMonth, it) <= 1 }) return 1.0
        return outOfSeasonMultiplier
    }

    fun imageOnly(predictions: List<ImagePrediction>): List<RankedCandidate> {
        if (predictions.isEmpty()) return emptyList()
        require(predictions.all { it.score.isFinite() && it.score >= 0.0 }) { "Invalid image scores" }
        val total = predictions.sumOf { it.score }
        require(total.isFinite() && total > 0.0) { "No positive image scores were returned" }
        return predictions.sortedWith(compareByDescending<ImagePrediction> { it.score }.thenBy { it.rank })
            .mapIndexed { index, prediction ->
                RankedCandidate(
                    species = prediction.species,
                    relativeScore = prediction.score / total,
                    imageRank = prediction.rank,
                    finalRank = index + 1,
                    nearbyRecordCount = null,
                    evidence = EvidenceBreakdown(prediction.score, 0.0, 1.0, 1.0),
                )
            }
    }

    /** Existing ecology demonstration only. Never call this entry point for a real capture. */
    operator fun invoke(
        predictions: List<ImagePrediction>,
        nearbyCounts: Map<String, Int>,
        habitat: Habitat,
        date: LocalDate,
    ): List<RankedCandidate> {
        if (predictions.isEmpty()) return emptyList()
        val totalNearby = predictions.sumOf { (nearbyCounts[it.species.id] ?: 0).coerceAtLeast(0).toDouble() }
        val denominator = totalNearby + locationSmoothing * predictions.size
        val components = predictions.map { prediction ->
            val count = (nearbyCounts[prediction.species.id] ?: 0).coerceAtLeast(0)
            val location = (count + locationSmoothing) / denominator
            val season = prediction.species.seasonalPrior(date.monthValue)
            val habitatPrior = prediction.species.habitatPrior(habitat)
            val raw = imageWeight * ln(prediction.score.coerceAtLeast(1e-8)) +
                locationWeight * ln(location.coerceAtLeast(1e-8)) +
                seasonWeight * ln(season.coerceAtLeast(1e-8)) +
                habitatWeight * ln(habitatPrior.coerceAtLeast(1e-8))
            RankedCandidate(
                species = prediction.species, relativeScore = 0.0,
                imageRank = prediction.rank, finalRank = 0, nearbyRecordCount = count,
                evidence = EvidenceBreakdown(prediction.score, location, season, habitatPrior),
            ) to raw
        }
        val maximum = components.maxOf { it.second }
        val weighted = components.map { it.first to exp(it.second - maximum) }
        val sum = weighted.sumOf { it.second }
        return weighted.sortedByDescending { it.second }.mapIndexed { index, (candidate, value) ->
            candidate.copy(relativeScore = value / sum, finalRank = index + 1)
        }
    }

    companion object {
        const val LIVE_RULE_VERSION =
            "ala-positive-support-v1-cap0.15-saturation50+flowering-mismatch-v1-x0.85-tolerance1-flower0.5"
        const val IMAGE_ONLY_RULE_VERSION = "image-only-normalised-v1"
        const val DEMO_RULE_VERSION = "synthetic-ecology-demo-v1"
    }
}
