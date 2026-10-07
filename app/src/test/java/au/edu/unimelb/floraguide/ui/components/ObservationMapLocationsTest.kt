package au.edu.unimelb.floraguide.ui.components

import au.edu.unimelb.floraguide.domain.model.ContextDataSource
import au.edu.unimelb.floraguide.domain.model.GeoPoint
import au.edu.unimelb.floraguide.domain.model.Habitat
import au.edu.unimelb.floraguide.domain.model.Observation
import au.edu.unimelb.floraguide.domain.model.Species
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class ObservationMapLocationsTest {
    private fun observation(id: String, latitude: Double, longitude: Double) = Observation(
        id = id,
        species = Species("blackwood", "Blackwood", "Acacia melanoxylon", setOf(2), mapOf(Habitat.LAWN to 0.7), 2),
        observedAt = Instant.ofEpochMilli(1000),
        coarseLocation = GeoPoint(latitude, longitude),
        habitat = Habitat.LAWN,
        photoPath = null,
        headingDegrees = null,
        relativeScore = 0.8,
        contextSource = ContextDataSource.ALA_LIVE,
    )

    @Test
    fun pinsSitOnTheStorageGridEvenForFinerCoordinates() {
        val pins = observationMapLocations(
            listOf(
                observation("saved", -37.797, 144.961),
                observation("legacy", -37.7968912, 144.9614567),
                observation("invalid", 91.0, 144.961),
            ),
        )
        assertEquals(
            mapOf(ObservationMapPoint(-37.797, 144.961) to listOf("saved", "legacy")),
            pins.mapValues { (_, plants) -> plants.map { it.id } },
        )
    }
}
