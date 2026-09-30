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
 * docs/FUSION_EVALUATION.md records the results the app adopted.
 *
 * Splits: train.json plus pilot.json's dev half form the training pool, used with five-fold cross-validation
 * to fit the parameters and choose the radius; pilot.json's holdout half is the test set.
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

    private inner class Params(val radius: Int, val cap: Double, val season: Double) {
        val ranker = RankSpeciesCandidatesUseCase(maximumLiveBoost = cap, outOfSeasonMultiplier = season, floweringRecords = table)
        fun rank(case: Case) = ranker.live(case.candidates, context(case, radius), case.month, case.organ)
        override fun toString() = String.format(Locale.US, "%d km, location cap %.2f, season ×%.2f", radius, cap, season)
    }

    private class Case(
        val id: Long, val stratum: String, val split: String, val labels: Set<String>, val label: String,
        val month: Int, val organ: PredictedOrgan?, val predictions: List<ImagePrediction>,
        val counts: Map<String, Map<Int, Int>>,
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

    private inner class CrossValidation(val radius: Int) {
        val folds = (0 until FOLDS).map { k -> fit(pool.filter { it.fold != k }, radius) }
        /** Every pooled case is scored once, by parameters fitted without it. */
        val score = pool.groupBy { it.fold }.map { (k, cases) -> score(cases, folds[k]::rank) }.reduce(Score::plus)
    }

    @Test fun trainValidateAndTest() {
        assertTrue("need training and test cases", pool.isNotEmpty() && test.isNotEmpty())
        val crossValidation = RADII_KM.map { CrossValidation(it) }
        val radius = crossValidation.maxBy { it.score.meanLogLikelihood }.radius // baseline listed first wins ties
        val chosen = fit(pool, radius)
        val handSet = Params(BASELINE_KM, 0.15, 0.85)
        val report = buildString {
            appendLine("# Fusion parameter training (#20)\n")
            data()
            recall()
            crossValidationSection(crossValidation, handSet, chosen)
            diagnostics(chosen.radius)
            testing(handSet, chosen)
            activation(chosen)
            helpedAndHarmed(chosen)
        }
        File("build/reports/evaluation").apply { mkdirs() }.resolve("fusion-training.md").writeText(report)
        println(report)
    }

    /** Grid search on mean log-likelihood. Neutral values come first, so a parameter the data cannot inform stays neutral. */
    private fun fit(cases: List<Case>, radius: Int) =
        CAPS.flatMap { cap -> SEASONS.map { season -> Params(radius, cap, season) } }.maxBy { score(cases, it::rank).meanLogLikelihood }

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

    private fun StringBuilder.crossValidationSection(crossValidation: List<CrossValidation>, handSet: Params, chosen: Params) {
        appendLine("## Training: five-fold cross-validation on the training pool\n")
        appendLine("Grid: location cap 0–0.50 and season factor 0.50–1.00 in steps of 0.05, fitted by mean " +
            "log-likelihood on four folds and scored on the fifth.\n")
        appendLine("| Model | Cross-validated mean log-likelihood | Top-1 | Top-3 | Fitted per fold (cap / season) |")
        appendLine("|---|---|---|---|---|")
        val baseline = score(pool) { imageOnly.imageOnly(it.candidates) }
        appendLine("| Image only | ${ll(baseline)} | ${pct(baseline.top1, baseline.n)} | ${pct(baseline.top3, baseline.n)} | – |")
        val hand = score(pool, handSet::rank)
        appendLine("| Hand-set ($handSet) | ${ll(hand)} | ${pct(hand.top1, hand.n)} | ${pct(hand.top3, hand.n)} | not fitted |")
        for (cv in crossValidation) {
            appendLine("| Trained, ${cv.radius} km | ${ll(cv.score)} | ${pct(cv.score.top1, cv.score.n)} | " +
                "${pct(cv.score.top3, cv.score.n)} | ${cv.folds.joinToString(", ") { "${fmt(it.cap)} / ${fmt(it.season)}" }} |")
        }
        appendLine("\nChosen radius ${chosen.radius} km; refitted on the whole pool: $chosen.\n")
    }

    private fun StringBuilder.testing(handSet: Params, chosen: Params) {
        appendLine("## Test: held-out set, run once\n")
        appendLine("| Model | Mean log-likelihood | Top-1 | Top-3 | Top-1 gained / lost vs image only | Sign test p |")
        appendLine("|---|---|---|---|---|---|")
        row(test, "Image only") { imageOnly.imageOnly(it.candidates) }
        for ((name, p) in listOf("Hand-set ($handSet)" to handSet, "Trained ($chosen)" to chosen)) {
            val s = score(test, p::rank)
            val (gained, lost) = flips(test, p::rank)
            appendLine("| $name | ${ll(s)} | ${pct(s.top1, s.n)} | ${pct(s.top3, s.n)} | $gained / $lost | " +
                "${String.format(Locale.US, "%.2f", signTest(gained, lost))} |")
        }
        appendLine("\nTop-1 by stratum (test):\n")
        appendLine("| Stratum | Image only | Hand-set | Trained |")
        appendLine("|---|---|---|---|")
        for ((stratum, subset) in test.groupBy { it.stratum }) {
            appendLine("| $stratum | ${pct(score(subset) { imageOnly.imageOnly(it.candidates) }.top1, subset.size)} | " +
                "${pct(score(subset, handSet::rank).top1, subset.size)} | ${pct(score(subset, chosen::rank).top1, subset.size)} |")
        }
        appendLine()
    }

    private fun StringBuilder.activation(chosen: Params) {
        val contexts = test.map { context(it, chosen.radius) }
        val rankings = test.map(chosen::rank)
        appendLine("## How often each cue could act (test set, ${chosen.radius} km)\n")
        appendLine("- Complete ALA context, so geographic support applied: " +
            pct(contexts.count { it.source == ContextDataSource.ALA_LIVE }, test.size))
        appendLine("- Blocked by at least one unresolved candidate name: " +
            pct(contexts.zip(test).count { (context, case) -> context.countsBySpeciesId.size < case.candidates.size }, test.size))
        appendLine("- Flower photo, so the flowering cue applied: " +
            pct(rankings.count { r -> r.any { it.evidence.floweringCheck != FloweringCheck.NOT_APPLIED } }, test.size))
        appendLine("- Flowering cue found at least one candidate out of season: " +
            pct(rankings.count { r -> r.any { it.evidence.floweringCheck == FloweringCheck.OUT_OF_SEASON } }, test.size))
        appendLine()
    }

    /** What each cue says about correct and wrong candidates; training pool only, so the test set stays unseen. */
    private fun StringBuilder.diagnostics(radius: Int) {
        val ranked = pool.map { it to Params(radius, 0.0, 1.0).rank(it) } // neutral, but the evidence is filled in
        appendLine("## Why training chose these values (training pool, $radius km)\n")
        val behind = ranked.filter { (case, r) -> (correctRank(case, r) ?: 1) > 1 }
        val complete = behind.filter { (case, _) -> context(case, radius).source == ContextDataSource.ALA_LIVE }
        val reachable = complete.count { (case, r) ->
            r[0].evidence.imagePrior <= (1 + CAPS.last()) * r.first { it.species.id in case.labels }.evidence.imagePrior
        }
        appendLine("- Correct species ranked 2–5 by the image model: ${behind.size}; with complete ALA context: " +
            "${complete.size}; close enough for the largest location boost (×${fmt(1 + CAPS.last())}) to overtake: $reachable\n")
        val located = ranked.filter { (case, _) -> context(case, radius).source == ContextDataSource.ALA_LIVE }
            .flatMap { (case, r) -> r.map { (it.species.id in case.labels) to it.evidence.locationPrior } }
        appendLine("| Candidate, complete ALA context | Candidates | Mean support | Saturated (≥ 50 records) |")
        appendLine("|---|---|---|---|")
        for ((name, correct) in listOf("Correct species" to true, "Other candidates" to false)) {
            val support = located.filter { it.first == correct }.map { it.second }
            appendLine("| $name | ${support.size} | ${fmt(support.average())} | ${pct(support.count { it >= 1.0 }, support.size)} |")
        }
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

    private fun StringBuilder.helpedAndHarmed(chosen: Params) {
        appendLine("## Test cases where the trained fusion moved the correct species\n")
        appendLine("| Observation | Stratum | Label | Image rank → final rank | Multipliers on the label |")
        appendLine("|---|---|---|---|---|")
        for (case in test) {
            val before = correctRank(case, imageOnly.imageOnly(case.candidates)) ?: continue
            val ranking = chosen.rank(case)
            val after = correctRank(case, ranking) ?: continue
            if (before == after) continue
            val evidence = ranking.first { it.species.id in case.labels }.evidence
            appendLine("| [${case.id}](https://www.inaturalist.org/observations/${case.id}) | ${case.stratum} | *${case.label}* | " +
                "$before → $after | location ×${fmt(evidence.locationMultiplier)}, season ×${fmt(evidence.seasonMultiplier)} |")
        }
        appendLine()
    }

    private fun StringBuilder.row(subset: List<Case>, name: String, rank: (Case) -> List<RankedCandidate>) {
        val s = score(subset, rank)
        appendLine("| $name | ${ll(s)} | ${pct(s.top1, s.n)} | ${pct(s.top3, s.n)} |")
    }

    private fun splits() = listOf("Training pool" to pool, "Test" to test)

    private val contexts = HashMap<Pair<Long, Int>, NearbyContext>()

    /** Rebuilds what ReliableAlaSpeciesContextRepository would report, using the app's own name matching. */
    private fun context(case: Case, radius: Int): NearbyContext = contexts.getOrPut(case.id to radius) {
        val counts = case.candidates.mapNotNull { prediction ->
            val name = prediction.species.id
            val match = alaNames[name] ?: return@mapNotNull null
            runCatching { parseTaxon(match.toString(), name) }.getOrNull()?.let {
                name to requireNotNull(case.counts[name]?.get(radius)) { "No cached ALA count for $name at $radius km" }
            }
        }.toMap()
        val source = when (counts.size) {
            0 -> ContextDataSource.ALA_UNAVAILABLE
            case.candidates.size -> ContextDataSource.ALA_LIVE
            else -> ContextDataSource.ALA_PARTIAL
        }
        NearbyContext(counts, source, radius)
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
        val CAPS = (0..10).map { it * 0.05 }
        val SEASONS = (20 downTo 10).map { it * 0.05 }

        fun fmt(value: Double) = String.format(Locale.US, "%.2f", value)
        fun ll(score: Score) = String.format(Locale.US, "%.3f (n=%d)", score.meanLogLikelihood, score.scored)
        fun pct(count: Int, total: Int) =
            if (total == 0) "–" else String.format(Locale.US, "%d/%d (%.0f%%)", count, total, 100.0 * count / total)

        /** Exact two-sided sign test on discordant Top-1 outcomes; a small test set rarely reaches significance. */
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
            val counts = json.getJSONObject("alaCounts").let { all ->
                all.keys().asSequence().associateWith { name ->
                    all.getJSONObject(name).let { byRadius -> byRadius.keys().asSequence().associate { it.toInt() to byRadius.getInt(it) } }
                }
            }
            return Case(
                id = json.getLong("id"), stratum = json.getString("stratum"), split = json.getString("split"),
                labels = setOf(json.getString("label"), json.getString("labelWcvp")), label = json.getString("label"),
                month = LocalDate.parse(json.getString("observedOn")).monthValue, organ = organ,
                predictions = predictions, counts = counts,
            )
        }
    }
}
