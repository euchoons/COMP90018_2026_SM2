package au.edu.unimelb.floraguide.data.catalog

import au.edu.unimelb.floraguide.domain.model.Habitat
import au.edu.unimelb.floraguide.domain.model.Species

/**
 * Small demonstration catalogue, not an ecological authority. Month and habitat weights are
 * prototype heuristics used to expose the data-fusion mechanism in the UI.
 */
object DemoSpeciesCatalog {
    val all: List<Species> = listOf(
        Species(
            id = "london_plane",
            commonName = "London plane",
            scientificName = "Platanus × acerifolia",
            preferredMonths = setOf(10, 11, 12, 1, 2, 3),
            habitatAffinity = mapOf(
                Habitat.TREE_CANOPY to 0.72,
                Habitat.LAWN to 0.38,
                Habitat.GARDEN_BED to 0.30,
                Habitat.WATER_EDGE to 0.18,
            ),
            demoNearbyCount = 7,
        ),
        Species(
            id = "river_red_gum",
            commonName = "River red gum",
            scientificName = "Eucalyptus camaldulensis",
            preferredMonths = setOf(6, 7, 8, 9, 10),
            habitatAffinity = mapOf(
                Habitat.TREE_CANOPY to 0.94,
                Habitat.LAWN to 0.68,
                Habitat.GARDEN_BED to 0.42,
                Habitat.WATER_EDGE to 1.0,
            ),
            demoNearbyCount = 184,
        ),
        Species(
            id = "blackwood",
            commonName = "Blackwood",
            scientificName = "Acacia melanoxylon",
            preferredMonths = setOf(7, 8, 9, 10),
            habitatAffinity = mapOf(
                Habitat.TREE_CANOPY to 0.90,
                Habitat.LAWN to 0.42,
                Habitat.GARDEN_BED to 0.62,
                Habitat.WATER_EDGE to 0.56,
            ),
            demoNearbyCount = 123,
        ),
        Species(
            id = "silver_wattle",
            commonName = "Silver wattle",
            scientificName = "Acacia dealbata",
            preferredMonths = setOf(7, 8, 9, 10),
            habitatAffinity = mapOf(
                Habitat.TREE_CANOPY to 0.80,
                Habitat.LAWN to 0.44,
                Habitat.GARDEN_BED to 0.71,
                Habitat.WATER_EDGE to 0.38,
            ),
            demoNearbyCount = 86,
        ),
        Species(
            id = "white_clover",
            commonName = "White clover",
            scientificName = "Trifolium repens",
            preferredMonths = setOf(8, 9, 10, 11, 12, 1, 2, 3),
            habitatAffinity = mapOf(
                Habitat.TREE_CANOPY to 0.22,
                Habitat.LAWN to 1.0,
                Habitat.GARDEN_BED to 0.60,
                Habitat.WATER_EDGE to 0.32,
            ),
            demoNearbyCount = 52,
        ),
        Species(
            id = "dandelion",
            commonName = "Dandelion",
            scientificName = "Taraxacum officinale",
            preferredMonths = setOf(8, 9, 10, 11, 12, 1, 2, 3),
            habitatAffinity = mapOf(
                Habitat.TREE_CANOPY to 0.18,
                Habitat.LAWN to 0.90,
                Habitat.GARDEN_BED to 0.74,
                Habitat.WATER_EDGE to 0.20,
            ),
            demoNearbyCount = 31,
        ),
        Species(
            id = "bottlebrush",
            commonName = "Crimson bottlebrush",
            scientificName = "Callistemon citrinus",
            preferredMonths = setOf(9, 10, 11, 12, 1, 2),
            habitatAffinity = mapOf(
                Habitat.TREE_CANOPY to 0.64,
                Habitat.LAWN to 0.31,
                Habitat.GARDEN_BED to 1.0,
                Habitat.WATER_EDGE to 0.48,
            ),
            demoNearbyCount = 22,
        ),
        Species(
            id = "coast_banksia",
            commonName = "Coast banksia",
            scientificName = "Banksia integrifolia",
            preferredMonths = setOf(1, 2, 3, 4, 5, 6, 7),
            habitatAffinity = mapOf(
                Habitat.TREE_CANOPY to 0.70,
                Habitat.LAWN to 0.28,
                Habitat.GARDEN_BED to 0.82,
                Habitat.WATER_EDGE to 0.35,
            ),
            demoNearbyCount = 16,
        ),
    )

    fun byId(id: String): Species? = all.firstOrNull { it.id == id }
}
