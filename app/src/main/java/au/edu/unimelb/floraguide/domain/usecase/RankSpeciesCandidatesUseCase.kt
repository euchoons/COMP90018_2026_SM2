package au.edu.unimelb.floraguide.domain.usecase

import au.edu.unimelb.floraguide.domain.model.ContextDataSource
import au.edu.unimelb.floraguide.domain.model.EvidenceBreakdown
import au.edu.unimelb.floraguide.domain.model.Habitat
import au.edu.unimelb.floraguide.domain.model.ImagePrediction
import au.edu.unimelb.floraguide.domain.model.NearbyContext
import au.edu.unimelb.floraguide.domain.model.RankedCandidate
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
) {
    init {
        require(maximumLiveBoost in 0.0..0.5 && liveCountSaturation > 0)
        require(locationSmoothing > 0.0)
    }

    /**
     * Coursework heuristic, not a trained or calibrated probability model.
     * support = log(1 + min(count, 50)) / log(51)
     * weight = originalImageScore * (1 + 0.15 * support)
     * Normalise within the candidate set. Zero records apply no penalty.
     * Incomplete context keeps the entire image-only order, not just the failed candidates.
     */
    fun live(predictions: List<ImagePrediction>, context: NearbyContext): List<RankedCandidate> {
        val baseline = imageOnly(predictions)
        if (baseline.isEmpty()) return baseline
        val complete = context.source == ContextDataSource.ALA_LIVE && predictions.all {
            (context.countsBySpeciesId[it.species.id] ?: -1) >= 0
        }
        val components = baseline.map { candidate ->
            val count = context.countsBySpeciesId[candidate.species.id]?.takeIf { it >= 0 }
            val support = if (complete && count != null) {
                ln1p(count.coerceAtMost(liveCountSaturation).toDouble()) / ln1p(liveCountSaturation.toDouble())
            } else 0.0
            val multiplier = 1.0 + maximumLiveBoost * support
            val value = candidate.evidence.imagePrior * multiplier
            candidate.copy(
                nearbyRecordCount = count,
                evidence = candidate.evidence.copy(
                    locationPrior = support,
                    locationMultiplier = multiplier,
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
        const val LIVE_RULE_VERSION = "ala-positive-support-v1-cap0.15-saturation50"
        const val IMAGE_ONLY_RULE_VERSION = "image-only-normalised-v1"
        const val DEMO_RULE_VERSION = "synthetic-ecology-demo-v1"
    }
}
