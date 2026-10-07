package au.edu.unimelb.floraguide.evaluation

import au.edu.unimelb.floraguide.data.ala.parseTaxon
import au.edu.unimelb.floraguide.data.catalog.FLOWERING_TABLE_ASSET
import au.edu.unimelb.floraguide.data.catalog.parseFloweringTable
import au.edu.unimelb.floraguide.domain.model.ContextDataSource
import au.edu.unimelb.floraguide.domain.model.FloweringCheck
import au.edu.unimelb.floraguide.domain.model.ImagePrediction
import au.edu.unimelb.floraguide.domain.model.NearbyContext
import au.edu.unimelb.floraguide.domain.model.PredictedOrgan
import au.edu.unimelb.floraguide.domain.model.RankedCandidate
import au.edu.unimelb.floraguide.domain.model.Species
import au.edu.unimelb.floraguide.domain.usecase.RankSpeciesCandidatesUseCase
import java.io.File
import java.time.LocalDate
import java.util.Locale
import kotlin.math.ln
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #20: fits the fusion parameters on cached cases and reports them on held-out test cases. Everything replays
 * through the app's own name matching and ranking, with no network calls. tools/build-evaluation-set.py
 * collects the data; the report goes to build/reports/evaluation/fusion-training.md, and
 * docs/technical/FUSION_EVALUATION.md records the results the app adopted.
 *
 * Splits: train.json plus pilot.json's dev half form the training pool, used with five-fold cross-validation
 * to fit the location cap and choose the radius; pilot.json's holdout half is the test set.
 */
class FusionParameterTraining {
    private val datasets = listOf("evaluation/pilot.json", "evaluation/train.json").map {
        JSONObject(requireNotNull(javaClass.classLoader?.getResource(it)) { "Missing $it" }.readText())
    }
    private val alaNames = datasets.flatMap { d -> d.getJSONObject("alaNames").let { n -> n.keys().asSequence().map { it to n.getJSONObject(it) }.toList() } }.toMap()
    private val cases = datasets.flatMap { it.getJSONArray("cases").objects() }.map { parseCase(it) }
    private val pool = cases.filter { it.split == "train" || it.split == "dev" }
    private val test = cases.filter { it.split == "holdout" }
    private val table = parseFloweringTable(File("src/main/assets/$FLOWERING_TABLE_ASSET").readText())
    private val imageOnly = RankSpeciesCandidatesUseCase()

    /** FIRST is the #19 rule, where one unmatched name withholds support; CURRENT counts it as zero records. */
    private enum class Design { FIRST, CURRENT }

    /** ALL is what the app queries; WITHOUT_INATURALIST checks that the gain is not iNaturalist scoring itself. */
    private enum class Counts { ALL, WITHOUT_INATURALIST }

    private inner class Params(
        val radius: Int, val cap: Double, val season: Double = 1.0,
        val design: Design = Design.CURRENT, val counts: Counts = Counts.ALL,
    ) {
        val ranker = RankSpeciesCandidatesUseCase(maximumLiveBoost = cap, outOfSeasonMultiplier = season, floweringRecords = table)
        fun rank(case: Case) = ranker.live(case.candidates, context(case, radius, design, counts), case.month, case.organ)
        override fun toString() = String.format(Locale.US, "%d km, cap %.2f, season ×%.2f", radius, cap, season)
    }

    private class Case(
        val id: Long, val stratum: String, val split: String, val labels: Set<String>, val label: String,
        val month: Int, val organ: PredictedOrgan?, val predictions: List<ImagePrediction>,
        val counts: Map<Counts, Map<String, Map<Int, Int>>>,
    ) {
        /** The app ranks and checks only the first five Pl@ntNet results. */
        val candidates = predictions.take(CANDIDATES)
        val fold = Math.floorMod(id, FOLDS.toLong()).toInt()
    }

    /** Mean log-likelihood is taken over cases whose label is among the candidates, the only ones ranking can move. */
    private class Score(val n: Int, val top1: Int, val top3: Int, val logLikelihood: Double, val scored: Int) {
        val meanLogLikelihood get() = if (scored == 0) 0.0 else logLikelihood / scored
        operator fun plus(o: Score) = Score(n + o.n, top1 + o.top1, top3 + o.top3, logLikelihood + o.logLikelihood, scored + o.scored)
    }

    private inner class CrossValidation(
        val radius: Int, val counts: Counts, val design: Design = Design.CURRENT, val caps: List<Double> = CAPS,
    ) {
        val folds = (0 until FOLDS).map { k -> fit(pool.filter { it.fold != k }, radius, counts, design, caps) }
        /** Every pooled case is scored once, by parameters fitted without it. */
        val score = pool.groupBy { it.fold }.map { (k, cases) -> score(cases, folds[k]::rank) }.reduce(Score::plus)
        val flips = pool.groupBy { it.fold }.map { (k, cases) -> flips(cases, folds[k]::rank) }
            .reduce { a, b -> a.first + b.first to a.second + b.second }
    }

    @Test fun trainValidateAndTest() {
        assertTrue("need training and test cases", pool.isNotEmpty() && test.isNotEmpty())
        // Counts without iNaturalist were collected at the baseline radius only.
        val validation = mapOf(
            Counts.ALL to RADII_KM.map { CrossValidation(it, Counts.ALL) },
            Counts.WITHOUT_INATURALIST to listOf(CrossValidation(BASELINE_KM, Counts.WITHOUT_INATURALIST)),
        )
        // The baseline radius is listed first, so it wins ties.
        val chosen = validation.mapValues { (counts, runs) -> fit(pool, runs.maxBy { it.score.meanLogLikelihood }.radius, counts) }
        val handSet = Params(BASELINE_KM, 0.15, 0.85, Design.FIRST)
        val firstTrained = Params(BASELINE_KM, 0.50, 1.0, Design.FIRST)
        // The app's cap, which may differ from the refit when the objective is flat (#76).
        val adopted = Params(BASELINE_KM, RankSpeciesCandidatesUseCase.LOCATION_CAP)
        val report = buildString {
            appendLine("# Fusion parameter training (#20)\n")
            data()
            recall()
            crossValidationSection(validation, handSet, firstTrained, chosen)
            diagnostics(chosen.getValue(Counts.ALL).radius)
            testing(listOf(
                "First design, hand-set ($handSet)" to handSet,
                "First design, trained ($firstTrained)" to firstTrained,
                "Current design, refitted (${chosen.getValue(Counts.ALL)})" to chosen.getValue(Counts.ALL),
                "Current design, as adopted by the app ($adopted)" to adopted,
                "Current design, trained without iNaturalist records (${chosen.getValue(Counts.WITHOUT_INATURALIST)})" to
                    chosen.getValue(Counts.WITHOUT_INATURALIST),
            ))
            activation(adopted)
            helpedAndHarmed(adopted)
        }
        File("build/reports/evaluation").apply { mkdirs() }.resolve("fusion-training.md").writeText(report)
        println(report)
    }

    /** Grid search on mean log-likelihood. Zero comes first, so a cap the data cannot inform stays neutral. */
    private fun fit(cases: List<Case>, radius: Int, counts: Counts, design: Design = Design.CURRENT, caps: List<Double> = CAPS) =
        caps.map { Params(radius, it, design = design, counts = counts) }.maxBy { score(cases, it::rank).meanLogLikelihood }

    private fun StringBuilder.data() {
        appendLine("| Set | Cases | Wild, flowering | Wild, other | Cultivated |")
        appendLine("|---|---|---|---|---|")
        for ((name, subset) in splits()) {
            appendLine("| $name | ${subset.size} | ${subset.count { it.stratum == "wild_flowering" }} | " +
                "${subset.count { it.stratum == "wild_other" }} | ${subset.count { it.stratum == "cultivated" }} |")
        }
        appendLine("\nLabels are iNaturalist community identifications; one Pl@ntNet identification per photo.\n")
    }

    private fun StringBuilder.recall() {
        appendLine("## Pl@ntNet candidate recall\n")
        appendLine("| Set | Label in top 5 (what the app reranks) | Label in top 8 (returned) |")
        appendLine("|---|---|---|")
        for ((name, subset) in splits()) {
            appendLine("| $name | ${pct(subset.count { c -> c.candidates.any { it.species.id in c.labels } }, subset.size)} | " +
                "${pct(subset.count { c -> c.predictions.any { it.species.id in c.labels } }, subset.size)} |")
        }
        appendLine("\nReranking cannot fix a case whose label is missing from the top 5.\n")
    }

    private fun StringBuilder.crossValidationSection(
        validation: Map<Counts, List<CrossValidation>>, handSet: Params, firstTrained: Params, chosen: Map<Counts, Params>,
    ) {
        appendLine("## Training: five-fold cross-validation on the training pool\n")
        appendLine("Current design: a name ALA cannot match counts as zero records. The cap is fitted over " +
            "0–${fmt(CAPS.last())} in steps of ${fmt(CAPS[1])} by mean log-likelihood on four folds and scored on the " +
            "fifth; the season factor stays 1.00. First-design rows use fixed values and are not refitted. The " +
            "ablations change one of the two at 8 km.\n")
        appendLine("| Model | Mean log-likelihood | Top-1 | Top-3 | Top-1 gained / lost vs image only | Cap per fold |")
        appendLine("|---|---|---|---|---|---|")
        val baseline = score(pool) { imageOnly.imageOnly(it.candidates) }
        appendLine("| Image only | ${ll(baseline)} | ${pct(baseline.top1, baseline.n)} | ${pct(baseline.top3, baseline.n)} | – | – |")
        for ((name, p) in listOf("First design, hand-set ($handSet)" to handSet, "First design, trained ($firstTrained)" to firstTrained)) {
            val s = score(pool, p::rank)
            val (gained, lost) = flips(pool, p::rank)
            appendLine("| $name | ${ll(s)} | ${pct(s.top1, s.n)} | ${pct(s.top3, s.n)} | $gained / $lost | fixed |")
        }
        val ablations = listOf(
            "Ablation: first design, cap up to ${fmt(CAPS.last())}" to CrossValidation(BASELINE_KM, Counts.ALL, Design.FIRST),
            "Ablation: current design, cap up to 0.50" to
                CrossValidation(BASELINE_KM, Counts.ALL, caps = CAPS.filter { it <= 0.5 + 1e-9 }),
        )
        val rows = validation.flatMap { (counts, runs) ->
            val records = if (counts == Counts.ALL) "all ALA records" else "without iNaturalist records"
            runs.map { "Current design, ${it.radius} km, $records" to it }
        } + ablations
        for ((name, cv) in rows) {
            appendLine("| $name | ${ll(cv.score)} | ${pct(cv.score.top1, cv.score.n)} | ${pct(cv.score.top3, cv.score.n)} | " +
                "${cv.flips.first} / ${cv.flips.second} | ${cv.folds.joinToString(", ") { fmt(it.cap) }} |")
        }
        appendLine()
        for ((counts, p) in chosen) {
            appendLine("- Refitted on the whole pool, ${if (counts == Counts.ALL) "all ALA records" else "without iNaturalist records"}: $p")
        }
        appendLine()
    }

    /** What the location evidence says about correct and wrong candidates; training pool only, so the test set stays unseen. */
    private fun StringBuilder.diagnostics(radius: Int) {
        val ranked = pool.map { it to Params(radius, 0.0).rank(it) } // neutral, but the evidence is filled in
        appendLine("## What the evidence says (training pool, $radius km)\n")
        appendLine("| Candidate | Candidates | No ALA species match | At least one record nearby | Mean support |")
        appendLine("|---|---|---|---|---|")
        for ((name, correct) in listOf("Correct species" to true, "Other candidates" to false)) {
            val rows = ranked.flatMap { (case, r) -> r.filter { (it.species.id in case.labels) == correct }.map { case to it } }
            appendLine("| $name | ${rows.size} | " +
                "${pct(rows.count { (case, c) -> context(case, radius, Design.CURRENT, Counts.ALL).isUnmatched(c.species.id) }, rows.size)} | " +
                "${pct(rows.count { (_, c) -> (c.nearbyRecordCount ?: 0) > 0 }, rows.size)} | ${fmt(rows.map { it.second.evidence.locationPrior }.average())} |")
        }
        val behind = ranked.filter { (case, r) -> (correctRank(case, r) ?: 1) > 1 }
        val blocked = behind.count { (case, _) -> context(case, radius, Design.FIRST, Counts.ALL).source != ContextDataSource.ALA_LIVE }
        val moreRecords = behind.count { (case, r) ->
            (r.first { it.species.id in case.labels }.nearbyRecordCount ?: 0) > (r[0].nearbyRecordCount ?: 0)
        }
        appendLine("\n- Correct species ranked 2–5 by the image model: ${behind.size}. It has more nearby records than " +
            "the image model's first choice in $moreRecords; the first design withheld support in $blocked of them.")
        val flowers = ranked.flatMap { (case, r) -> r.map { (it.species.id in case.labels) to it.evidence.floweringCheck } }
            .filter { it.second != FloweringCheck.NOT_APPLIED }
        appendLine("\n| Candidate, flower photo | Candidates | In season | Out of season | No VicFlora months |")
        appendLine("|---|---|---|---|---|")
        for ((name, correct) in listOf("Correct species" to true, "Other candidates" to false)) {
            val checks = flowers.filter { it.first == correct }.map { it.second }
            appendLine("| $name | ${checks.size} | ${pct(checks.count { it == FloweringCheck.IN_SEASON }, checks.size)} | " +
                "${pct(checks.count { it == FloweringCheck.OUT_OF_SEASON }, checks.size)} | " +
                "${pct(checks.count { it == FloweringCheck.NO_DATA }, checks.size)} |")
        }
        appendLine("\nA cue helps only if it separates the correct species from the others.\n")
    }

    private fun StringBuilder.testing(models: List<Pair<String, Params>>) {
        appendLine("## Test: held-out set\n")
        appendLine("| Model | Mean log-likelihood | Top-1 | Top-3 | Top-1 gained / lost vs image only | Sign test p |")
        appendLine("|---|---|---|---|---|---|")
        val baseline = score(test) { imageOnly.imageOnly(it.candidates) }
        appendLine("| Image only | ${ll(baseline)} | ${pct(baseline.top1, baseline.n)} | ${pct(baseline.top3, baseline.n)} | – | – |")
        for ((name, p) in models) {
            val s = score(test, p::rank)
            val (gained, lost) = flips(test, p::rank)
            appendLine("| $name | ${ll(s)} | ${pct(s.top1, s.n)} | ${pct(s.top3, s.n)} | $gained / $lost | " +
                "${String.format(Locale.US, "%.3f", signTest(gained, lost))} |")
        }
        appendLine("\nTop-1 by stratum (test):\n")
        appendLine("| Stratum | Image only | ${models.joinToString(" | ") { it.first.substringBefore(" (") }} |")
        appendLine("|---|---|${models.joinToString("") { "---|" }}")
        for ((stratum, subset) in test.groupBy { it.stratum }) {
            appendLine("| $stratum | ${pct(score(subset) { imageOnly.imageOnly(it.candidates) }.top1, subset.size)} | " +
                "${models.joinToString(" | ") { (_, p) -> pct(score(subset, p::rank).top1, subset.size) }} |")
        }
        appendLine()
    }

    private fun StringBuilder.activation(chosen: Params) {
        val rankings = test.map(chosen::rank)
        appendLine("## How often each cue could act (test set, ${chosen.radius} km)\n")
        appendLine("- At least one candidate with no ALA species match, which withheld all support in the first design: " +
            pct(test.count { context(it, chosen.radius, Design.FIRST, Counts.ALL).source != ContextDataSource.ALA_LIVE }, test.size))
        appendLine("- Flower photo, so the flowering check ran: " +
            pct(rankings.count { r -> r.any { it.evidence.floweringCheck != FloweringCheck.NOT_APPLIED } }, test.size))
        appendLine("- Flowering check found at least one candidate out of season: " +
            pct(rankings.count { r -> r.any { it.evidence.floweringCheck == FloweringCheck.OUT_OF_SEASON } }, test.size))
        appendLine()
    }

    private fun StringBuilder.helpedAndHarmed(chosen: Params) {
        appendLine("## Test cases where the current design moved the correct species\n")
        appendLine("| Observation | Stratum | Label | Image rank → final rank | Label records / multiplier | Image's first choice, records |")
        appendLine("|---|---|---|---|---|---|")
        for (case in test) {
            val before = imageOnly.imageOnly(case.candidates)
            val beforeRank = correctRank(case, before) ?: continue
            val ranking = chosen.rank(case)
            val after = correctRank(case, ranking) ?: continue
            if (beforeRank == after) continue
            val label = ranking.first { it.species.id in case.labels }
            val leader = ranking.first { it.species.id == before[0].species.id }
            appendLine("| [${case.id}](https://www.inaturalist.org/observations/${case.id}) | ${case.stratum} | *${case.label}* | " +
                "$beforeRank → $after | ${label.nearbyRecordCount ?: "no match"} / ×${fmt(label.evidence.locationMultiplier)} | " +
                "*${leader.species.id}*, ${leader.nearbyRecordCount ?: "no match"} |")
        }
        appendLine()
    }

    private fun splits() = listOf("Training pool" to pool, "Test" to test)

    private data class Key(val id: Long, val radius: Int, val design: Design, val counts: Counts)
    private val contexts = HashMap<Key, NearbyContext>()

    /** Rebuilds what ReliableAlaSpeciesContextRepository would report, using the app's own name matching. */
    private fun context(case: Case, radius: Int, design: Design, counts: Counts): NearbyContext =
        contexts.getOrPut(Key(case.id, radius, design, counts)) {
            val found = HashMap<String, Int>()
            val unmatched = HashMap<String, String>()
            for (prediction in case.candidates) {
                val name = prediction.species.id
                if (alaNames[name]?.let { parseTaxon(it.toString(), name) } == null) {
                    unmatched[name] = NearbyContext.UNRESOLVED_TAXON
                } else {
                    found[name] = requireNotNull(case.counts.getValue(counts)[name]?.get(radius)) { "No cached ALA count for $name at $radius km" }
                }
            }
            // The first design treated an unmatched name as a missing count, which withholds support.
            val complete = design == Design.CURRENT || unmatched.isEmpty()
            NearbyContext(found, if (complete) ContextDataSource.ALA_LIVE else ContextDataSource.ALA_PARTIAL, radius,
                failuresBySpeciesId = unmatched)
        }

    private fun score(subset: List<Case>, rank: (Case) -> List<RankedCandidate>): Score {
        var top1 = 0
        var top3 = 0
        var logLikelihood = 0.0
        var scored = 0
        for (case in subset) {
            val hit = rank(case).firstOrNull { it.species.id in case.labels } ?: continue
            if (hit.finalRank == 1) top1++
            if (hit.finalRank <= 3) top3++
            logLikelihood += ln(hit.relativeScore)
            scored++
        }
        return Score(subset.size, top1, top3, logLikelihood, scored)
    }

    private fun correctRank(case: Case, ranking: List<RankedCandidate>) =
        ranking.firstOrNull { it.species.id in case.labels }?.finalRank

    private fun flips(subset: List<Case>, rank: (Case) -> List<RankedCandidate>): Pair<Int, Int> {
        val first = subset.map { case ->
            (correctRank(case, imageOnly.imageOnly(case.candidates)) == 1) to (correctRank(case, rank(case)) == 1)
        }
        return first.count { !it.first && it.second } to first.count { it.first && !it.second }
    }

    private companion object {
        const val CANDIDATES = 5
        const val FOLDS = 5
        const val BASELINE_KM = 8
        val RADII_KM = listOf(8, 2, 25) // baseline first, so it wins ties
        val CAPS = (0..120).map { it * 0.5 }

        fun fmt(value: Double) = String.format(Locale.US, "%.2f", value)
        fun ll(score: Score) = String.format(Locale.US, "%.3f (n=%d)", score.meanLogLikelihood, score.scored)
        fun pct(count: Int, total: Int) =
            if (total == 0) "–" else String.format(Locale.US, "%d/%d (%.0f%%)", count, total, 100.0 * count / total)

        /** Exact two-sided sign test on discordant Top-1 outcomes. */
        fun signTest(gained: Int, lost: Int): Double {
            val n = gained + lost
            if (n == 0) return 1.0
            var tail = 0.0
            var term = Math.pow(0.5, n.toDouble())
            for (i in 0..minOf(gained, lost)) {
                tail += term
                term = term * (n - i) / (i + 1)
            }
            return minOf(1.0, 2 * tail)
        }

        fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

        fun JSONObject.counts(key: String) = getJSONObject(key).let { all ->
            all.keys().asSequence().associateWith { name ->
                all.getJSONObject(name).let { byRadius -> byRadius.keys().asSequence().associate { it.toInt() to byRadius.getInt(it) } }
            }
        }

        fun parseCase(json: JSONObject): Case {
            val plantnet = json.getJSONObject("plantnet")
            // As PlantNetImageClassifier: one candidate per name, strongest first.
            val predictions = plantnet.getJSONArray("results").objects()
                .map { it.getString("name") to it.getDouble("score") }
                .distinctBy { it.first }.sortedByDescending { it.second }
                .mapIndexed { index, (name, score) ->
                    ImagePrediction(Species(name, name, name, emptySet(), emptyMap(), 0), score, index + 1)
                }
            val organ = plantnet.getJSONArray("organs").objects().maxByOrNull { it.getDouble("score") }
                ?.let { PredictedOrgan(it.getString("organ"), it.getDouble("score")) }
            return Case(
                id = json.getLong("id"), stratum = json.getString("stratum"), split = json.getString("split"),
                labels = setOf(json.getString("label"), json.getString("labelWcvp")), label = json.getString("label"),
                month = LocalDate.parse(json.getString("observedOn")).monthValue, organ = organ,
                predictions = predictions,
                counts = mapOf(Counts.ALL to json.counts("alaCounts"),
                    Counts.WITHOUT_INATURALIST to json.counts("alaCountsWithoutINaturalist")),
            )
        }
    }
}
