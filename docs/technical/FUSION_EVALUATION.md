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
order. The app uses the current design. Widening ALA's name matching in #76 left Top-1 unchanged.

## The rule

For each of Pl@ntNet's first five candidates `s`:

```text
support(s) = ln(1 + min(n(s), 50)) / ln(51)    n(s): ALA records within r of the capture,
                                               0 when ALA cannot match the name to a species or below
L(s)       = 1 + c * support(s)                1 for every candidate if any lookup failed
F(s)       = f if Pl@ntNet sees a flower (score >= 0.5) and every VicFlora flowering month of s
             is more than one month from the capture month, otherwise 1
w(s)       = imageScore(s) * L(s) * F(s)
score(s)   = w(s) / sum of w over the five candidates
```

| | First design, hand-set | First design, trained | Current design, trained |
|---|---|---|---|
| `r`, ALA radius (searched: 2, 8, 25 km) | 8 km | 8 km | 8 km |
| `c`, location cap | 0.15 | 0.50 (searched 0–0.50) | 20.5 (searched 0–60; kept after #76) |
| `f`, out-of-season factor | 0.85 | 1.00 (searched 0.50–1.00) | 1.00 (fixed) |
| A name ALA cannot match | withholds support from every candidate | same | counts as zero records |
| What counts as a match | a species | same | a species, or since #76 a subspecies, variety or cultivar entry for the same plant |

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
- **Counts added after #76:** the candidates that the widened rule newly accepts, in 8 photos, were
  counted on 2026-10-06 with the same queries; the earlier rule had skipped them.

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
- **Rounds.** The first round trained the first design and was tested once; it changed no
  Top-1 answer. Looking only at the training pool, the reason was the unmatched-name rule (see
  below), so the second round changed that rule, widened the search for `c` and was tested once.
  A third run followed #76, which widened ALA name matching. The test set has therefore been used
  three times; no parameter value was chosen with it.
- **Cap after #76.** The refit moved `c` from 20.5 to 38.5, but on the training pool every cap from
  15 to 45 gives the same Top-1 (284/339), and the mean log score differs by 0.003 between 20.5
  (−0.2070) and 38.5 (−0.2043). The objective is flat there, so the app keeps 20.5, which gives
  location less weight against the image model: one nearby record multiplies a candidate by 4.6
  rather than 7.8.
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
| Current design, 2 km | −0.241 | 282/339 (83%) | 23 / 5 | 18–25 |
| **Current design, 8 km** | **−0.209** | **284/339 (84%)** | **23 / 3** | 25.5–55 |
| Current design, 25 km | −0.210 | 284/339 (84%) | 22 / 2 | 32–60 |
| Current design, 8 km, without iNaturalist records | −0.282 | 279/339 (82%) | 19 / 4 | 7–10 |
| Ablation: first design, `c` up to 60 | −0.363 | 266/339 (78%) | 2 / 0 | 2–4.5 |
| Ablation: current design, `c` up to 0.50 | −0.329 | 269/339 (79%) | 6 / 1 | 0.50 |

These rows use #76's matching. 8 km scored best. Refitted on the whole pool, `c` = 38.5 (20.5 before #76),
or 9.0 without iNaturalist records; the app keeps 20.5 (see Method). The
ablations show both changes are needed: a larger cap alone gains nothing, because unmatched names
still withhold support from most photos, and counting them as zero under the old cap gains little.

### Test

| Model | Mean log score (n = 147) | Top-1 | Top-3 | Top-1 gained / lost vs image only | Sign test p |
|---|---|---|---|---|---|
| Image only | −0.390 | 127/156 (81%) | 146/156 (94%) | – | – |
| First design, hand-set | −0.387 | 127/156 (81%) | 146/156 (94%) | 0 / 0 | 1 |
| First design, trained | −0.384 | 127/156 (81%) | 146/156 (94%) | 0 / 0 | 1 |
| **Current design, as adopted (8 km, `c` = 20.5)** | **−0.198** | **138/156 (88%)** | 146/156 (94%) | **13 / 2** | **0.007** |
| Current design, refitted (8 km, `c` = 38.5) | −0.199 | 138/156 (88%) | 146/156 (94%) | 13 / 2 | 0.007 |
| Current design, without iNaturalist records (8 km, `c` = 9.0) | −0.250 | 137/156 (88%) | 147/156 (94%) | 11 / 1 | 0.006 |

| Stratum | Image only | Current design | Without iNaturalist records |
|---|---|---|---|
| Wild, flowering | 45/53 (85%) | 50/53 (94%) | 49/53 (92%) |
| Wild, other | 43/55 (78%) | 46/55 (84%) | 47/55 (85%) |
| Cultivated | 39/48 (81%) | 42/48 (88%) | 41/48 (85%) |

In all 13 gained answers, Pl@ntNet's first choice had no records within 8 km: ALA could not match
9 of them (for example *Romulea arnaudii* and *Rubus spectabilis*), and 4 had zero records. The 2 lost answers were correct species that ALA could not match:

- *Chenopodium parabolicum* (image score 0.25), which ALA only fuzzy-matches to *Rhagodia
  parabolica*, lost to *Chenopodium vulvaria* (0.07, one record).
- The cultivated *Mandevilla sanderi* (0.72), which ALA matches only to its genus, lost to
  *Mandevilla laxa* (0.22, one record).

The generated report lists every test photo whose correct species moved.

### Why the first design could not help

Measured on the training pool only, at 8 km, with #76's matching:

| Candidate | Candidates | No ALA match | At least one record nearby | Mean support |
|---|---|---|---|---|
| Correct species | 308 | 16 (5%) | 286 (93%) | 0.76 |
| Other candidates | 1215 | 526 (43%) | 373 (31%) | 0.19 |

- **The location evidence is strong.** Correct species almost always have nearby records; most wrong
  candidates have none. In 44 training photos Pl@ntNet ranked the correct species 2nd to 5th, and in
  33 of them the correct species had more nearby records than Pl@ntNet's first choice.
- **But the first design rarely let it act.** Pl@ntNet's world-flora candidates often include names
  ALA cannot match to a species, such as the Korean fir *Abies koreana*, which it matches only to the
  genus. One such name among five withheld support for the whole photo: in 33 of those 44 photos,
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
counts a name ALA cannot match as zero records. ALA matches at species level or below, including a
subspecies, variety or cultivar entry for the same plant (#76). `ReliableAlaSpeciesContextRepository` reports a
context as live when every lookup returned a count or no species match; a failed lookup still makes
it partial or unavailable, which withholds support. Observations record the rule as
`ala-positive-support-v3-unmatched-zero-cap20.5-saturation50+flowering-mismatch-v1-x1.00-tolerance1-flower0.5+vicflora-2026-09-25`.
The results card shows when a name counts as zero, and still shows the VicFlora flowering statement
for reference.

## Limitations

- **The test set was used three times:** once per design and once after #76. The redesign was
  prompted by the first test result, and keeping `c` = 20.5 was decided after the third run showed
  identical Top-1, although the parameters themselves came from the training pool only.
- **Unmatched correct species lose.** A correct species that ALA cannot match, through a naming
  difference or as a cultivated plant absent from ALA, now loses to recorded candidates; both lost
  test answers are of this kind. #76 fixed one common case, London plane, which ALA files as a
  cultivar. Because support is log-scaled, one nearby record already multiplies
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

## Latency on a phone (#53)

Live captures measured with the `FloraGuide-Latency` log ([procedure](../testing/LATENCY_FIELD_TEST.md), [timings](../testing/latency-results.csv)): 20 on Wi-Fi with a Pixel 10a (Android 16) on the Parkville campus on 2026-10-06 and 2026-10-07, and 21 on mobile data (carrier not recorded) with a OnePlus PGP110 (Android 15) in Bentleigh East on 2026-10-07. Each time runs from the start of upload, after the user agrees to send the photo. No capture failed, timed out or needed a retry, and every Pl@ntNet and ALA request returned HTTP 200. One Wi-Fi capture was abandoned after its upload and is not counted.

| Stage | Wi-Fi P50 | Wi-Fi P95 | Mobile data P50 | Mobile data P95 |
|---|---|---|---|---|
| Firebase upload | 8.2 s | 20.6 s | 10.1 s | 12.2 s |
| Firebase download | 3.6 s | 13.9 s | 3.2 s | 4.0 s |
| Pl@ntNet request | 4.5 s | 5.3 s | 7.4 s | 12.1 s |
| ALA lookup | 0.3 s | 1.1 s | 0.3 s | 0.5 s |
| To the image-only result | 16.4 s | 29.5 s | 21.2 s | 25.4 s |
| To the fused result | 16.7 s | 29.7 s | 21.5 s | 25.8 s |

Percentiles are nearest-rank, so P95 is the slowest or second-slowest capture. The Wi-Fi Firebase rows cover 16 captures, because the four earliest predate the Firebase stage timings; the other rows cover all 20 Wi-Fi or 21 mobile-data captures.

- **The Firebase round trip dominates.** Uploading the 1.3–2.2 MB photo and downloading it again takes a median 12.3 s on Wi-Fi and 13.8 s on mobile data, 74% and 62% of the total wait. Pl@ntNet takes a median 4.5 s on Wi-Fi and 7.4 s on mobile data, and ALA under half a second.
- **Mobile data was slower at the median but had no very slow captures.** Its median fused result came 4.8 s later. Most of the gap is in the two stages that send the photo out: the Firebase upload (+1.9 s) and the Pl@ntNet request, which uploads the photo again (+2.9 s). The download back was slightly faster. Every mobile-data capture finished within 19.0–26.6 s, whereas two Wi-Fi captures took 35.2 s and 29.7 s because of slow transfers: the first capture of a session (upload 15.6 s, download 13.9 s) and one with a 20.6 s upload.
- **On Wi-Fi, context changed the first choice in 2 of the 16 captures on 2026-10-07, both plane trees, and was right for the wrong reason.** The University's [Parkville tree inventory](https://uom.maps.arcgis.com/home/item.html?id=f034a3c2ea664ad2920ec3e623682895) lists only Oriental planes (*Platanus orientalis*) within 50 m of where the photos were taken, the nearest 11 m away. *P. orientalis* became the first choice in both photos, but only because ALA files London plane (*Platanus × hispanica*, also a candidate) as a cultivar. The species-only rule rejected that entry, so London plane's 163 nearby records counted as zero. The app now counts those records (#76), and replaying the photos under that rule puts London plane first in both. The location evidence then favours the plane recorded most around the city (163 records against 6) over the one actually standing there. ALA counts within 8 km cannot tell neighbouring trees apart; the inventory, which records 17 Oriental and 64 London planes on campus, could, although it covers trees only and states no licence.
- **On mobile data, context changed the first choice in 2 of the 21 captures:** *Hydrangea* spp. became *H. macrophylla*, and *Clivia × cyrtanthiflora* became *C. miniata*. ALA could not match either of Pl@ntNet's first choices to a species: one is a genus-level label, and ALA returns the hybrid under a differently written name, which the rule does not accept as the same name. Both counted as zero records, so a species of the same genus with nearby records moved ahead. The tester did not record whether either answer was right.
- **Garden plants are poorly covered.** In four of the five photos on 2026-10-06, only one of the five candidates matched a species in ALA. On mobile data, where Pl@ntNet's first choices were all garden or indoor plants, ALA matched 37 of the 95 candidate names, and 9 of the 21 captures had at most one match. All these captures used the species-only matching from before #76.

Each network was tested once, on a different phone, in a different place and with different plants, and the mobile-data photos were about a tenth larger (median 1.65 MB against 1.48 MB). The gap between the columns is therefore indicative, not a controlled network comparison. With about 20 captures per network, P95 rests on the slowest two captures. The first capture of a session can include connection set-up.

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

```bash
python3 tools/build-evaluation-set.py --fill-missing-counts app/src/test/resources/evaluation/pilot.json --fill-missing-counts app/src/test/resources/evaluation/train.json
```

Training and testing need no network and write `app/build/reports/evaluation/fusion-training.md`:

```bash
./gradlew testDebugUnitTest --tests '*FusionParameterTraining*'
```
