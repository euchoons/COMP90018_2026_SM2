package au.edu.unimelb.floraguide.domain.usecase

import au.edu.unimelb.floraguide.domain.model.CandidateEvidenceRecord
import au.edu.unimelb.floraguide.domain.model.CaptureSnapshot
import au.edu.unimelb.floraguide.domain.model.ContextDataSource
import au.edu.unimelb.floraguide.domain.model.GeoPoint
import au.edu.unimelb.floraguide.domain.model.Habitat
import au.edu.unimelb.floraguide.domain.model.ImageSource
import au.edu.unimelb.floraguide.domain.model.NearbyContext
import au.edu.unimelb.floraguide.domain.model.Observation
import au.edu.unimelb.floraguide.domain.model.RankedCandidate
import java.time.Instant
import kotlin.math.round

/** Builds a saved record from the capture snapshot rather than the later Save-button state. */
class CreateObservationUseCase {
    operator fun invoke(
        capture: CaptureSnapshot,
        selected: RankedCandidate,
        ranking: List<RankedCandidate>,
        habitat: Habitat,
        photoPath: String?,
        cloudPhotoUri: String?,
        imageSource: ImageSource?,
        context: NearbyContext?,
        confirmedAt: Instant,
    ): Observation {
        val captureLocation = requireNotNull(capture.location) {
            "A usable capture location is required before saving an observation."
        }
        val source = context?.source ?: ContextDataSource.NOT_REQUESTED
        val rule = when {
            imageSource == ImageSource.DEMO_ADAPTER -> RankSpeciesCandidatesUseCase.DEMO_RULE_VERSION
            source == ContextDataSource.ALA_LIVE -> RankSpeciesCandidatesUseCase.LIVE_RULE_VERSION
            else -> RankSpeciesCandidatesUseCase.IMAGE_ONLY_RULE_VERSION
        }
        return Observation(
            id = capture.observationId,
            species = selected.species,
            observedAt = capture.capturedAt,
            coarseLocation = GeoPoint(
                round(captureLocation.latitude * 1000.0) / 1000.0,
                round(captureLocation.longitude * 1000.0) / 1000.0,
            ),
            habitat = habitat,
            photoPath = photoPath,
            cloudPhotoUri = cloudPhotoUri,
            headingDegrees = capture.headingDegrees,
            relativeScore = selected.relativeScore,
            contextSource = source,
            imageScore = selected.evidence.imagePrior,
            imageSource = imageSource,
            locationSource = capture.locationSource,
            confirmedAt = confirmedAt,
            nearbyRecordCount = selected.nearbyRecordCount,
            contextRadiusKm = context?.radiusKm,
            contextQueriedAt = context?.queriedAt,
            rankingRule = rule,
            candidateEvidence = ranking.map { candidate ->
                CandidateEvidenceRecord(
                    scientificName = candidate.species.scientificName,
                    imageScore = candidate.evidence.imagePrior,
                    finalRelativeScore = candidate.relativeScore,
                    nearbyRecordCount = candidate.nearbyRecordCount,
                    lookupFailure = context?.failuresBySpeciesId?.get(candidate.species.id),
                )
            },
        )
    }
}
