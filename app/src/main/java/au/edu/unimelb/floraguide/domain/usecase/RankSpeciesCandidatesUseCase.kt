package au.edu.unimelb.floraguide.domain.usecase

import au.edu.unimelb.floraguide.domain.model.EvidenceBreakdown
import au.edu.unimelb.floraguide.domain.model.Habitat
import au.edu.unimelb.floraguide.domain.model.ImagePrediction
import au.edu.unimelb.floraguide.domain.model.RankedCandidate
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.ln

/**
 * Explainable log-linear fusion model.
 *
 * raw(s) = α log(image) + β log(location) + γ log(season) + δ log(habitat)
 * final scores are a softmax over the candidate set. They are relative ranking scores, not
 * calibrated probabilities.
 */
class RankSpeciesCandidatesUseCase(
    private val imageWeight: Double = 1.0,
    private val locationWeight: Double = 0.75,
    private val seasonWeight: Double = 0.35,
    private val habitatWeight: Double = 0.45,
    private val locationSmoothing: Double = 3.0,
) {
    operator fun invoke(
        predictions: List<ImagePrediction>,
        nearbyCounts: Map<String, Int>,
        habitat: Habitat,
        date: LocalDate,
    ): List<RankedCandidate> {
        if (predictions.isEmpty()) return emptyList()

        val epsilon = 1e-8
        val candidateCount = predictions.size
        val totalNearby = predictions.sumOf { prediction ->
            (nearbyCounts[prediction.species.id] ?: 0).coerceAtLeast(0)
        }.toDouble()
        val locationDenominator = totalNearby + locationSmoothing * candidateCount

        val components = predictions.map { prediction ->
            val count = (nearbyCounts[prediction.species.id] ?: 0).coerceAtLeast(0)
            val locationPrior = (count + locationSmoothing) / locationDenominator
            val seasonalPrior = prediction.species.seasonalPrior(date.monthValue)
            val habitatPrior = prediction.species.habitatPrior(habitat)
            val rawScore =
                imageWeight * ln(prediction.score.coerceAtLeast(epsilon)) +
                    locationWeight * ln(locationPrior.coerceAtLeast(epsilon)) +
                    seasonWeight * ln(seasonalPrior.coerceAtLeast(epsilon)) +
                    habitatWeight * ln(habitatPrior.coerceAtLeast(epsilon))

            CandidateComponents(
                prediction = prediction,
                nearbyCount = count,
                rawScore = rawScore,
                evidence = EvidenceBreakdown(
                    imagePrior = prediction.score,
                    locationPrior = locationPrior,
                    seasonalPrior = seasonalPrior,
                    habitatPrior = habitatPrior,
                ),
            )
        }

        // Numerically stable softmax.
        val maximum = components.maxOf { it.rawScore }
        val exponentials = components.map { exp(it.rawScore - maximum) }
        val denominator = exponentials.sum().coerceAtLeast(epsilon)

        return components
            .zip(exponentials)
            .sortedByDescending { (_, exponential) -> exponential }
            .mapIndexed { index, (component, exponential) ->
                RankedCandidate(
                    species = component.prediction.species,
                    relativeScore = exponential / denominator,
                    imageRank = component.prediction.rank,
                    finalRank = index + 1,
                    nearbyRecordCount = component.nearbyCount,
                    evidence = component.evidence,
                )
            }
    }

    fun imageOnly(predictions: List<ImagePrediction>): List<RankedCandidate> {
        val total = predictions.sumOf { it.score }.coerceAtLeast(1e-8)
        return predictions.sortedByDescending { it.score }.mapIndexed { index, prediction ->
            RankedCandidate(
                species = prediction.species,
                relativeScore = prediction.score / total,
                imageRank = prediction.rank,
                finalRank = index + 1,
                nearbyRecordCount = 0,
                evidence = EvidenceBreakdown(
                    imagePrior = prediction.score,
                    locationPrior = 0.0,
                    seasonalPrior = 0.0,
                    habitatPrior = 0.0,
                ),
            )
        }
    }

    private data class CandidateComponents(
        val prediction: ImagePrediction,
        val nearbyCount: Int,
        val rawScore: Double,
        val evidence: EvidenceBreakdown,
    )
}
