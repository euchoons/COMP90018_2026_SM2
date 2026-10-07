package au.edu.unimelb.floraguide.ui

import au.edu.unimelb.floraguide.domain.model.ContextDataSource
import au.edu.unimelb.floraguide.domain.model.GeoPoint
import au.edu.unimelb.floraguide.domain.model.Habitat
import au.edu.unimelb.floraguide.domain.model.ImageSource
import au.edu.unimelb.floraguide.domain.model.Observation
import au.edu.unimelb.floraguide.domain.model.Species
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionProgressTest {
    private val twoSpecies = FloraGuideUiState(observations = listOf(saved("a"), saved("a"), saved("b")))

    @Test fun repeatsAndGuidedDemoSavesDoNotCountTowardsTheMission() {
        assertEquals(2, twoSpecies.uniqueSpeciesCount)
        assertEquals(2, twoSpecies.copy(observations = twoSpecies.observations + saved("c", ImageSource.DEMO_ADAPTER)).uniqueSpeciesCount)
    }

    @Test fun onlyTheSaveThatReachesTheGoalCompletesTheMission() {
        assertTrue(twoSpecies.completesMission(saved("c")))
        assertFalse(twoSpecies.completesMission(saved("a")))
        assertFalse(twoSpecies.completesMission(saved("c", ImageSource.DEMO_ADAPTER)))
        // A fourth species after completion does not celebrate again.
        assertFalse(twoSpecies.copy(observations = twoSpecies.observations + saved("c")).completesMission(saved("d")))
    }

    companion object {
        fun saved(species: String, source: ImageSource = ImageSource.PLANTNET_LIVE) = Observation(
            id = "$species-$source", species = Species(species, species, species, emptySet(), emptyMap(), 0),
            observedAt = Instant.EPOCH, coarseLocation = GeoPoint(-37.798, 144.961), habitat = Habitat.LAWN,
            photoPath = null, headingDegrees = null, relativeScore = 0.9, contextSource = ContextDataSource.ALA_LIVE,
            imageSource = source,
        )
    }
}
