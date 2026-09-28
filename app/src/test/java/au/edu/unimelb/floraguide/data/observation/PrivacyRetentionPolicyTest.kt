package au.edu.unimelb.floraguide.data.observation

import au.edu.unimelb.floraguide.data.local.ObservationEntity
import au.edu.unimelb.floraguide.data.local.SyncState
import au.edu.unimelb.floraguide.domain.model.CaptureLocationSource
import au.edu.unimelb.floraguide.domain.model.CaptureSnapshot
import au.edu.unimelb.floraguide.domain.model.GeoPoint
import au.edu.unimelb.floraguide.domain.model.Habitat
import au.edu.unimelb.floraguide.domain.model.RankedCandidate
import au.edu.unimelb.floraguide.domain.model.Species
import au.edu.unimelb.floraguide.domain.model.EvidenceBreakdown
import au.edu.unimelb.floraguide.domain.usecase.CreateObservationUseCase
import java.time.Instant
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyRetentionPolicyTest {

    private val createObservation = CreateObservationUseCase()

    @Test
    fun `capture location is coarsened to exactly three decimal places`() {
        val preciseLatitude = -37.7963888889
        val preciseLongitude = 144.9614111111

        val capture = CaptureSnapshot(
            observationId = "obs-privacy-1",
            capturedAt = Instant.now(),
            location = GeoPoint(preciseLatitude, preciseLongitude, accuracyMetres = 5.0f),
            locationSource = CaptureLocationSource.DEVICE,
            headingDegrees = 180.0f,
        )

        val candidateSpecies = Species(
            id = "eucalyptus_camaldulensis",
            commonName = "River red gum",
            scientificName = "Eucalyptus camaldulensis",
            preferredMonths = setOf(1, 2, 12),
            habitatAffinity = mapOf(Habitat.WATER_EDGE to 1.0),
            demoNearbyCount = 10,
        )

        val rankedCandidate = RankedCandidate(
            species = candidateSpecies,
            relativeScore = 0.95,
            imageRank = 1,
            finalRank = 1,
            nearbyRecordCount = 15,
            evidence = EvidenceBreakdown(0.9, 1.0, 1.0, 1.0, 1.15),
        )

        val observation = createObservation(
            capture = capture,
            selected = rankedCandidate,
            ranking = listOf(rankedCandidate),
            habitat = Habitat.WATER_EDGE,
            photoPath = "/local/photos/test.jpg",
            cloudPhotoUri = "gs://bucket/plant_photos/user1/digest.jpg",
            imageSource = null,
            context = null,
            confirmedAt = Instant.now(),
        )

        assertEquals(-37.796, observation.coarseLocation.latitude, 0.000001)
        assertEquals(144.961, observation.coarseLocation.longitude, 0.000001)

        val latDiff = abs(preciseLatitude - observation.coarseLocation.latitude)
        val lonDiff = abs(preciseLongitude - observation.coarseLocation.longitude)

        assertTrue("Latitude delta must be within 0.001 degrees grid", latDiff <= 0.001)
        assertTrue("Longitude delta must be within 0.001 degrees grid", lonDiff <= 0.001)
    }

    @Test
    fun `entity encoding preserves coarse coordinates and deletion states`() {
        val capture = CaptureSnapshot(
            observationId = "obs-privacy-2",
            capturedAt = Instant.ofEpochMilli(1700000000000L),
            location = GeoPoint(-37.8136, 144.9631, accuracyMetres = 10.0f),
            locationSource = CaptureLocationSource.DEVICE,
            headingDegrees = 90.0f,
        )

        val species = Species("blackwood", "Blackwood", "Acacia melanoxylon", emptySet(), emptyMap(), 0)
        val candidate = RankedCandidate(species, 0.88, 1, 1, 5, EvidenceBreakdown(0.8, 1.0, 1.0, 1.0, 1.0))

        val obs = createObservation(
            capture = capture,
            selected = candidate,
            ranking = listOf(candidate),
            habitat = Habitat.LAWN,
            photoPath = "/tmp/photo.jpg",
            cloudPhotoUri = "gs://bucket/plant_photos/uid123/file.jpg",
            imageSource = null,
            context = null,
            confirmedAt = Instant.ofEpochMilli(1700000005000L),
        )

        val entity = obs.toEntity("uid123")
        assertEquals(-37.814, entity.coarseLatitude, 0.000001)
        assertEquals(144.963, entity.coarseLongitude, 0.000001)
        assertEquals(SyncState.PENDING_UPLOAD, entity.syncState)
    }
}
