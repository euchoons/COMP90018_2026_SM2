# Fusion parameter training (#20)

Pl@ntNet ranks the photo; the live rule then adjusts its first five candidates with ALA
occurrence records near the capture ([missing-context policy](MISSING_CONTEXT_POLICY.md)).
#20 trained the rule on 339 iNaturalist photos from around Parkville and tested it on 156 others.

**Result.** The rule inherited from #19 changed no Top-1 answer on the test photos, even after
training. Its location evidence was informative, but one candidate name that ALA could not match
withheld support from three quarters of the photos. The current design counts such a name as zero
records and trains the location cap over a wider range. On the 156 test photos it raises Top-1
accuracy from 81% to 88% (13 answers gained, 2 lost; sign test p = 0.007), and the gain holds with
iNaturalist's own records removed from ALA. The flowering check is shown but does not change the
order. The app uses the current design.

## The rule

For each of Pl@ntNet's first five candidates `s`:

```text
support(s) = ln(1 + min(n(s), 50)) / ln(51)    n(s): ALA records within r of the capture,
                                               0 when ALA cannot match the name to a species
L(s)       = 1 + c * support(s)                1 for every candidate if any lookup failed
F(s)       = f if Pl@ntNet sees a flower (score >= 0.5) and every VicFlora flowering month of s
             is more than one month from the capture month, otherwise 1
w(s)       = imageScore(s) * L(s) * F(s)
score(s)   = w(s) / sum of w over the five candidates
```

| | First design, hand-set | First design, trained | Current design, trained |
|---|---|---|---|
| `r`, ALA radius (searched: 2, 8, 25 km) | 8 km | 8 km | 8 km |
| `c`, location cap | 0.15 | 0.50 (searched 0–0.50) | 20.5 (searched 0–60) |
| `f`, out-of-season factor | 0.85 | 1.00 (searched 0.50–1.00) | 1.00 (fixed) |
| A name ALA cannot match | withholds support from every candidate | same | counts as zero records |

Fixed: the 50-record saturation, the one-month tolerance, the 0.5 flower score and the five
candidates. With `c` = 20.5, a candidate's multiplier runs from 1 (no records nearby) to 21.5
(50 or more); a single record already gives 4.6.

## Data

Collected on 2026-09-30 by `tools/build-evaluation-set.py` and cached in
`app/src/test/resources/evaluation/` (`pilot.json`, `train.json`):

- **Photos and labels:** iNaturalist plant observations within 8 km of Parkville, identified to
  species, with open coordinates accurate to 2 km and a CC0, CC BY or CC BY-NC photo. The label is
  the iNaturalist community identification, never Pl@ntNet's. It is also looked up in WCVP, so
  Pl@ntNet's name for the same species counts as correct. The files keep each photo's URL, licence
  and attribution, not the image.
- **Three strata of equal size**, because the app meets all three: wild plants annotated as
  flowering (research grade), other wild plants (research grade, not annotated as flowering) and
  cultivated plants (at least two agreeing identifications).
- **Sampling:** a fixed hash order, at most two observations per species and one per observer and
  species. The second collection excluded the first one's observations and their observers' other
  records of the same species. 495 photos of 309 species.
- **Evidence:** one Pl@ntNet identification per photo (`organs=auto`, eight results, iNaturalist's
  1024-pixel "large" image). ALA name matching for every candidate, and ALA record counts at 2, 8 and
  25 km around the coordinates rounded to three decimals, as the app does, excluding the
  observation's own iNaturalist record.
- **Robustness counts:** the 8 km counts again, without any iNaturalist record
  (`fq=-dataResourceUid:dr1411`), because iNaturalist supplies both the photos and many ALA
  records: about 60% of the records counted around these photos.

| Set | Photos | Wild, flowering | Wild, other | Cultivated |
|---|---|---|---|---|
| Training pool (`train.json` and the dev half of `pilot.json`) | 339 | 112 | 110 | 117 |
| Test (the holdout half of `pilot.json`) | 156 | 53 | 55 | 48 |

The first collection (300 photos) was split in half by a hash of the observation ID; the second
(195 photos) went entirely to training. Pl@ntNet's free quota of 500 identifications a day limited
the total.

## Method

`FusionParameterTraining`, a JVM unit test, replays every photo through the app's own ALA name
matching and `RankSpeciesCandidatesUseCase.live()`, without network calls.

- **Objective:** the mean log of the correct species' final score, over photos whose correct
  species is among the five candidates (the only ones reranking can move). Unlike Top-1 accuracy,
  it rewards raising the correct species' score even when the order stays the same.
- **Fitting:** a grid search over `c` in steps of 0.5; the first round also searched `f`. On a tie
  the neutral value wins, so a parameter the data cannot inform stays neutral.
- **Radius:** five-fold cross-validation on the training pool (folds by observation ID mod 5) for
  each radius. The best cross-validated score picks the radius (8 km wins ties), then `c` is
  refitted on the whole pool.
- **Two rounds.** The first round trained the first design and was tested once; it changed no
  Top-1 answer. Looking only at the training pool, the reason was the unmatched-name rule (see
  below), so the second round changed that rule, widened the search for `c` and was tested once.
  The test set has therefore been used twice, once per design; no parameter value was chosen with it.
- **Exploration** between the rounds used the training pool only. Besides the ablations below, an
  uncapped prior, `imageScore * (n + 1)^a`, was tried; it let a candidate with an image score of
  0.03 overturn one at 0.48 on its records alone, so the 50-record saturation stays.
- **Test:** image-only ranking, both first-design settings and the current design on the 156 test
  photos. An exact sign test compares the Top-1 answers each gains and loses against image-only.

## Results

### Candidate recall

| Set | Correct species in the top 5 (what the app reranks) | In the top 8 (returned) |
|---|---|---|
| Training pool | 308/339 (91%) | 312/339 (92%) |
| Test | 147/156 (94%) | 147/156 (94%) |

### Training: five-fold cross-validation

| Model | Mean log score (n = 308) | Top-1 | Top-1 gained / lost | Fitted `c` per fold |
|---|---|---|---|---|
| Image only | −0.371 | 264/339 (78%) | – | – |
| First design, hand-set (8 km, 0.15, 0.85) | −0.370 | 266/339 (78%) | 2 / 0 | – |
| First design, trained (8 km, 0.50, 1.00) | −0.367 | 266/339 (78%) | 2 / 0 | – |
| Current design, 2 km | −0.262 | 284/339 (84%) | 22 / 2 | 13.5–17.5 |
| **Current design, 8 km** | **−0.240** | **284/339 (84%)** | **23 / 3** | 16–28.5 |
| Current design, 25 km | −0.244 | 283/339 (83%) | 21 / 2 | 16.5–37.5 |
| Current design, 8 km, without iNaturalist records | −0.282 | 279/339 (82%) | 19 / 4 | 7–10 |
| Ablation: first design, `c` up to 60 | −0.363 | 266/339 (78%) | 2 / 0 | 2–4.5 |
| Ablation: current design, `c` up to 0.50 | −0.333 | 269/339 (79%) | 6 / 1 | 0.50 |

8 km scored best. Refitted on the whole pool, `c` = 20.5, or 9.0 without iNaturalist records. The
ablations show both changes are needed: a larger cap alone gains nothing, because unmatched names
still withhold support from most photos, and counting them as zero under the old cap gains little.

### Test

| Model | Mean log score (n = 147) | Top-1 | Top-3 | Top-1 gained / lost vs image only | Sign test p |
|---|---|---|---|---|---|
| Image only | −0.390 | 127/156 (81%) | 146/156 (94%) | – | – |
| First design, hand-set | −0.387 | 127/156 (81%) | 146/156 (94%) | 0 / 0 | 1 |
| First design, trained | −0.384 | 127/156 (81%) | 146/156 (94%) | 0 / 0 | 1 |
| **Current design (8 km, `c` = 20.5)** | **−0.219** | **138/156 (88%)** | 146/156 (94%) | **13 / 2** | **0.007** |
| Current design, without iNaturalist records (8 km, `c` = 9.0) | −0.250 | 137/156 (88%) | 147/156 (94%) | 11 / 1 | 0.006 |

| Stratum | Image only | Current design | Without iNaturalist records |
|---|---|---|---|
| Wild, flowering | 45/53 (85%) | 50/53 (94%) | 49/53 (92%) |
| Wild, other | 43/55 (78%) | 46/55 (84%) | 47/55 (85%) |
| Cultivated | 39/48 (81%) | 42/48 (88%) | 41/48 (85%) |

In all 13 gained answers, Pl@ntNet's first choice had no records within 8 km: ALA could not match
10 of them to a species (for example *Romulea arnaudii*, *Medicago × varia* and *Rubus spectabilis*),
and 3 had zero records. The 2 lost answers were correct species that ALA could not match:

- *Chenopodium parabolicum* (image score 0.25), which ALA only fuzzy-matches to *Rhagodia
  parabolica*, lost to *Chenopodium vulvaria* (0.07, one record).
- The cultivated *Mandevilla sanderi* (0.72), which ALA matches only to its genus, lost to
  *Mandevilla laxa* (0.22, one record).

The generated report lists every test photo whose correct species moved.

### Why the first design could not help

Measured on the training pool only, at 8 km:

| Candidate | Candidates | No ALA species match | At least one record nearby | Mean support |
|---|---|---|---|---|
| Correct species | 308 | 19 (6%) | 283 (92%) | 0.75 |
| Other candidates | 1215 | 528 (43%) | 372 (31%) | 0.19 |

- **The location evidence is strong.** Correct species almost always have nearby records; most wrong
  candidates have none. In 44 training photos Pl@ntNet ranked the correct species 2nd to 5th, and in
  33 of them the correct species had more nearby records than Pl@ntNet's first choice.
- **But the first design rarely let it act.** Pl@ntNet's world-flora candidates often include names
  ALA cannot match to a species, such as the Korean fir *Abies koreana*, which it matches only to the
  genus. One such name among five withheld support for the whole photo: in 34 of those 44 photos,
  and in 74% of the test photos.
- **Its cap was also too small.** At ×1.5 it could not close most gaps: Pl@ntNet's first choice
  usually leads by more.

### Flowering

| Candidates in flower photos (training pool) | Candidates | In season | Out of season | No VicFlora months |
|---|---|---|---|---|
| Correct species | 127 | 29 (23%) | 4 (3%) | 94 (74%) |
| Other candidates | 479 | 9 (2%) | 2 (0.4%) | 468 (98%) |

The first round set `f` to 1.00 in every fold: the check flagged 6 candidates, 4 of them the correct
species. The VicFlora table covers 103 species, the 100 most recorded around Parkville plus the demo
species, so wrong candidates, often not local, mostly have no data and are never lowered. A rule
that only lowers mismatches therefore mostly penalises local species, which are the likely answers.
VicFlora's months also describe plants across Victoria, while campus plants are often planted and
watered. An in-season boost helped slightly on its own in exploration but added nothing once
location was used, since being in the table largely means being local. The check found an
out-of-season candidate in 1 of the 156 test photos.

## What the app uses

`RankSpeciesCandidatesUseCase` defaults to `c` = 20.5 and `f` = 1.00, with the 8 km radius, and
counts a name ALA cannot match as zero records. `ReliableAlaSpeciesContextRepository` reports a
context as live when every lookup returned a count or no species match; a failed lookup still makes
it partial or unavailable, which withholds support. Observations record the rule as
`ala-positive-support-v2-unmatched-zero-cap20.5-saturation50+flowering-mismatch-v1-x1.00-tolerance1-flower0.5+vicflora-2026-09-25`.
The results card shows when a name counts as zero, and still shows the VicFlora flowering statement
for reference.

## Limitations

- **The test set was used twice.** The redesign was prompted by the first test result, although its
  parameters came from the training pool only.
- **Unmatched correct species lose.** A correct species that ALA cannot match, through a naming
  difference or as a cultivated plant absent from ALA, now loses to recorded candidates; both lost
  test answers are of this kind. Because support is log-scaled, one nearby record already multiplies
  a candidate by 4.6, so sparse records weigh heavily. The saturation and the log shape were not trained.
- **Location can override the image model** wherever image scores are within ×21.5. Unusual
  cultivated plants may be hurt more often than this sample shows.
- **Labels** are iNaturalist community identifications, not expert determinations. Cultivated
  observations cannot reach research grade, so their labels rest on at least two agreeing
  identifications.
- **Pl@ntNet does not disclose its training images,** so some of these public photos may have been
  seen in training, which would make image accuracy optimistic. All models share the same image
  scores, so the comparison between them is unaffected.
- **ALA includes research-grade iNaturalist records.** The observation's own record is excluded, and
  the robustness counts without any iNaturalist record give nearly the same test result.
- **Photos and model versions differ from the app.** iNaturalist photos are framed and sized
  differently from the app's captures, and Pl@ntNet's answers may change as its model is updated;
  the responses are cached as retrieved.
- **Scope:** 156 test photos from one area around Parkville. The result supports this design
  decision for the app; it does not show that the rule is better elsewhere or in general.

## Reproduce

The collector reads the Pl@ntNet key from `local.properties` and never writes it out. It queries
live APIs, so a new run samples and scores again; the committed files are the data behind this page.

```bash
python3 tools/build-evaluation-set.py
```

```bash
python3 tools/build-evaluation-set.py --per-stratum 65 --split train --exclude app/src/test/resources/evaluation/pilot.json --out app/src/test/resources/evaluation/train.json
```

```bash
python3 tools/build-evaluation-set.py --add-counts-without-inaturalist app/src/test/resources/evaluation/pilot.json --add-counts-without-inaturalist app/src/test/resources/evaluation/train.json
```

Training and testing need no network and write `app/build/reports/evaluation/fusion-training.md`:

```bash
./gradlew testDebugUnitTest --tests '*FusionParameterTraining*'
```
