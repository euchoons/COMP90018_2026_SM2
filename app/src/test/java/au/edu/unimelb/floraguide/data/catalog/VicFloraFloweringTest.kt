package au.edu.unimelb.floraguide.data.catalog

import au.edu.unimelb.floraguide.domain.model.ContextDataSource
import au.edu.unimelb.floraguide.domain.model.FloweringRecord
import au.edu.unimelb.floraguide.domain.model.ImagePrediction
import au.edu.unimelb.floraguide.domain.model.NearbyContext
import au.edu.unimelb.floraguide.domain.model.PredictedOrgan
import au.edu.unimelb.floraguide.domain.model.Species
import au.edu.unimelb.floraguide.domain.usecase.RankSpeciesCandidatesUseCase
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VicFloraFloweringTest {
    // Unit tests run from the module directory, so this is the file the APK bundles.
    private val tsv = File("src/main/assets/$FLOWERING_TABLE_ASSET").readText()

    @Test fun bundledTableKeepsOnlyDocumentedMonths() {
        val table = parseFloweringTable(tsv)
        assertEquals(
            FloweringRecord(setOf(12, 1, 2), "Flowers summer.",
                "https://vicflora.rbg.vic.gov.au/flora/taxon/b81ef7c6-89a0-45d7-9b2b-cebb16c7033a"),
            table["Eucalyptus camaldulensis"],
        )
        assertEquals(setOf(8, 9, 10), table["Acacia pycnantha"]?.months) // "Flowers Aug.–Oct."
        // No VicFlora flowering statement, or no VicFlora taxon: unknown, never "out of season".
        assertNull(table["Acacia dealbata"])
        assertNull(table["Platanus × acerifolia"])
        // The ranker rejects empty or impossible months, so bad data fails here rather than at app start.
        RankSpeciesCandidatesUseCase(floweringRecords = table)
    }

    @Test fun februaryWattleFlowerKeepsTheSummerSpeciesAndLowersASpringOne() {
        val ranker = RankSpeciesCandidatesUseCase(floweringRecords = parseFloweringTable(tsv))
        val wattles = listOf("Acacia implexa", "Acacia mearnsii", "Acacia dealbata").mapIndexed { index, name ->
            ImagePrediction(Species(name, name, name, emptySet(), emptyMap(), 0), 0.3, index + 1)
        }
        val factors = ranker.live(wattles, NearbyContext(emptyMap(), ContextDataSource.NOT_REQUESTED, 8), 2,
            PredictedOrgan("flower", 0.9)).associate { it.species.id to it.evidence.seasonMultiplier }
        // Dec.–Mar. is in season, Sep.–Nov. is not, and no statement is unknown.
        assertEquals(mapOf("Acacia implexa" to 1.0, "Acacia mearnsii" to 0.85, "Acacia dealbata" to 1.0), factors)
    }

    @Test fun savedRuleVersionNamesTheBundledRetrievalDate() {
        val rows = tsv.lines().filter { it.isNotBlank() }.map { it.split('\t') }
        val retrieved = rows.drop(1).map { it[rows.first().indexOf("retrieved")] }.toSet().single()
        assertEquals("vicflora-$retrieved", RankSpeciesCandidatesUseCase.LIVE_RULE_VERSION.substringAfterLast('+'))
    }
}
