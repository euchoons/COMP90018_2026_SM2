package au.edu.unimelb.floraguide.data.observation

import au.edu.unimelb.floraguide.data.local.ObservationEntity
import au.edu.unimelb.floraguide.data.local.SyncState
import au.edu.unimelb.floraguide.domain.model.ContextDataSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ObservationEntityCodecTest {
    private fun rowWithoutJson(source: String) = ObservationEntity(
        id = "legacy", userId = "anonymous_user", speciesId = "blackwood", scientificName = "Acacia melanoxylon",
        commonName = "Blackwood", observedAtEpochMs = 0L, coarseLatitude = -37.796, coarseLongitude = 144.961,
        habitatName = "TREE_CANOPY", localPhotoPath = null, remotePhotoUrl = null, headingDegrees = null,
        relativeScore = 0.5, contextSource = source, syncState = SyncState.SYNCED,
    )

    @Test fun `column fallback relabels old partial and demo context like the JSON codec`() {
        assertEquals(ContextDataSource.LEGACY_UNVERIFIED, rowWithoutJson("ALA_PARTIAL").toObservation().contextSource)
        assertEquals(ContextDataSource.LEGACY_UNVERIFIED, rowWithoutJson("DEMO_FALLBACK").toObservation().contextSource)
        assertEquals(ContextDataSource.ALA_LIVE, rowWithoutJson("ALA_LIVE").toObservation().contextSource)
    }
}
