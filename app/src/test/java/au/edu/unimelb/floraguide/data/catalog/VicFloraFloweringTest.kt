package au.edu.unimelb.floraguide.data.catalog

import au.edu.unimelb.floraguide.data.plantnet.parsePlantNetResults
import au.edu.unimelb.floraguide.data.plantnet.parsePredictedOrgan
import au.edu.unimelb.floraguide.domain.model.ContextDataSource
import au.edu.unimelb.floraguide.domain.model.FloweringCheck
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
            FloweringRecord("Eucalyptus camaldulensis", setOf(12, 1, 2), "Flowers summer.",
                "https://vicflora.rbg.vic.gov.au/flora/taxon/b81ef7c6-89a0-45d7-9b2b-cebb16c7033a"),
            table["Eucalyptus camaldulensis"],
        )
        assertEquals(setOf(8, 9, 10), table["Acacia pycnantha"]?.months) // "Flowers Aug.–Oct."
        // No VicFlora flowering statement, or no VicFlora taxon: unknown, never "out of season".
        assertNull(table["Acacia dealbata"])
        assertNull(table["Platanus × acerifolia"])
        // Pl@ntNet's WCVP name reaches VicFlora's row; ALA calls E. oblonga only a pro parte synonym.
        assertEquals("Callistemon citrinus", table["Melaleuca citrina"]?.sourceName)
        assertNull(table["Eucalyptus oblonga"])
        // The ranker rejects empty or impossible months, so bad data fails here rather than at app start.
        RankSpeciesCandidatesUseCase(floweringRecords = table)
    }

    @Test fun februaryWattleFlowerIsInSeasonOnlyForTheSummerSpecies() {
        val ranker = RankSpeciesCandidatesUseCase(floweringRecords = parseFloweringTable(tsv))
        val wattles = listOf("Acacia implexa", "Acacia mearnsii", "Acacia dealbata").mapIndexed { index, name ->
            ImagePrediction(Species(name, name, name, emptySet(), emptyMap(), 0), 0.3, index + 1)
        }
        val checks = ranker.live(wattles, NearbyContext(emptyMap(), ContextDataSource.NOT_REQUESTED, 8), 2,
            PredictedOrgan("flower", 0.9)).associate { it.species.id to it.evidence.floweringCheck }
        // Dec.–Mar. is in season, Sep.–Nov. is not, and no statement is unknown.
        assertEquals(mapOf("Acacia implexa" to FloweringCheck.IN_SEASON, "Acacia mearnsii" to FloweringCheck.OUT_OF_SEASON,
            "Acacia dealbata" to FloweringCheck.NO_DATA), checks)
    }

    @Test fun liveBottlebrushIdentificationFindsVicFloraUnderItsWcvpName() {
        // Recorded 2026-09-27 for iNaturalist observation 58486559 (photo: Paul Whitington, CC BY).
        val body = requireNotNull(javaClass.classLoader?.getResource("plantnet/identify-melaleuca-citrina.json")).readText()
        val top = parsePlantNetResults(body, maxResults = 8).first()
        assertEquals("Melaleuca citrina", top.scientificName)
        assertEquals("flower", parsePredictedOrgan(body)?.organ)
        assertEquals("Callistemon citrinus", parseFloweringTable(tsv)[top.scientificName]?.sourceName)
    }

    @Test fun wcvpNamesReachTheSourceRecordButNeverReplaceAnotherSpecies() {
        val table = parseFloweringTable(listOf(
            "scientific_name\twcvp_name\tstatus\tflowering_months\tsource_text\tsource_url",
            "Callistemon citrinus\tMelaleuca citrina\tdocumented\t11,12\tFlowers Nov.–Dec\thttps://example.org/a",
            "Acacia one\tAcacia two\tdocumented\t1\tFlowers Jan.\thttps://example.org/b",
            "Acacia two\t\tdocumented\t6\tFlowers Jun.\thttps://example.org/c",
        ).joinToString("\n"))
        assertEquals("Callistemon citrinus", table["Melaleuca citrina"]?.sourceName)
        assertEquals(setOf(6), table["Acacia two"]?.months)
    }

    @Test fun savedRuleVersionNamesTheBundledRetrievalDate() {
        val rows = tsv.lines().filter { it.isNotBlank() }.map { it.split('\t') }
        val retrieved = rows.drop(1).map { it[rows.first().indexOf("retrieved")] }.toSet().single()
        assertEquals("vicflora-$retrieved", RankSpeciesCandidatesUseCase.LIVE_RULE_VERSION.substringAfterLast('+'))
    }
}
