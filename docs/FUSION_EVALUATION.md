# Fusion parameter training (#20)

Pl@ntNet ranks the photo; the live rule then adjusts its first five candidates with ALA
occurrence records and VicFlora flowering months ([missing-context policy](MISSING_CONTEXT_POLICY.md)).
#20 fitted the rule's parameters on 339 iNaturalist photos from around Parkville and tested
them on 156 others.

**Result.** Training raised the location cap from 0.15 to its design bound of 0.50, set the
flowering factor to 1.00 (no effect) and kept the 8 km radius. On the test set, neither the
trained nor the hand-set rule changed a single Top-1 answer (81% for all three models); the
trained rule only raised the correct species' score slightly. The app now uses the trained values.

## The rule

For each of Pl@ntNet's first five candidates `s`:

```text
support(s) = ln(1 + min(n(s), 50)) / ln(51)    n(s): ALA records within r of the capture;
                                               support is 0 for every candidate unless all five resolve
L(s)       = 1 + c * support(s)
F(s)       = f if Pl@ntNet sees a flower (score >= 0.5) and every VicFlora flowering month of s
             is more than one month from the capture month, otherwise 1
w(s)       = imageScore(s) * L(s) * F(s)
score(s)   = w(s) / sum of w over the five candidates
```

| Parameter | Hand-set | Trained | Searched |
|---|---|---|---|
| `r`, ALA radius | 8 km | 8 km | 2, 8, 25 km |
| `c`, location cap | 0.15 | 0.50 | 0–0.50 in steps of 0.05 |
| `f`, out-of-season factor | 0.85 | 1.00 | 0.50–1.00 in steps of 0.05 |

Fixed: the 50-record saturation, the one-month tolerance, the 0.5 flower score, the five
candidates, and the rule that one unresolved candidate turns geographic support off. 0.50 is
the app's design bound for `c`, which keeps the image model in charge.

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
  it rewards raising the correct species' score even when the order stays the same, so it can
  compare parameters on few cases.
- **Fitting:** every combination of `c` and `f` on the grid. On a tie the neutral value (`c` = 0,
  `f` = 1) wins, so a parameter the data cannot inform stays neutral.
- **Radius:** five-fold cross-validation on the training pool (folds by observation ID mod 5) for
  each radius. The best cross-validated score picks the radius (8 km wins ties), then `c` and `f`
  are refitted on the whole pool.
- **Test:** the fitted rule, the hand-set rule and image-only ranking on the 156 test photos.
  Parameters were chosen on the training pool only; no value was chosen using the test set. An
  exact sign test compares the Top-1 answers each rule gains and loses against image-only.

## Results

### Candidate recall

| Set | Correct species in the top 5 (what the app reranks) | In the top 8 (returned) |
|---|---|---|
| Training pool | 308/339 (91%) | 312/339 (92%) |
| Test | 147/156 (94%) | 147/156 (94%) |

### Training: five-fold cross-validation

| Model | Mean log score (n = 308) | Top-1 | Top-3 | Fitted `c` / `f` |
|---|---|---|---|---|
| Image only | −0.371 | 264/339 (78%) | 304/339 (90%) | – |
| Hand-set (8 km, 0.15, 0.85) | −0.370 | 266/339 (78%) | 304/339 (90%) | – |
| Trained, 2 km | −0.367 | 266/339 (78%) | 303/339 (89%) | 0.50 / 1.00 in all five folds |
| Trained, 8 km | −0.367 | 266/339 (78%) | 304/339 (90%) | 0.50 / 1.00 in all five folds |
| Trained, 25 km | −0.367 | 266/339 (78%) | 304/339 (90%) | 0.50 / 1.00 in all five folds |

The three radii tied, so 8 km stays. Refitted on the whole pool: `c` = 0.50, `f` = 1.00.

### Test

| Model | Mean log score (n = 147) | Top-1 | Top-3 | Top-1 gained / lost vs image only |
|---|---|---|---|---|
| Image only | −0.390 | 127/156 (81%) | 146/156 (94%) | – |
| Hand-set (8 km, 0.15, 0.85) | −0.387 | 127/156 (81%) | 146/156 (94%) | 0 / 0 |
| Trained (8 km, 0.50, 1.00) | −0.384 | 127/156 (81%) | 146/156 (94%) | 0 / 0 |

Top-1 was identical in every stratum as well: 45/53 wild flowering, 43/55 wild other and 39/48
cultivated. With no changed answers the sign test is uninformative (p = 1). The trained rule did
not move the correct species to a different rank in any test photo.

How often each cue could act on the test set (8 km):

- Geographic support applied, because all five candidates resolved in ALA: 40/156 (26%). Every
  other photo had at least one candidate that ALA could not match to a species.
- Flower photo, so the flowering check ran: 66/156 (42%). At least one candidate out of season:
  1/156 (1%).

## Why training chose these values

Measured on the training pool only, at 8 km:

| Candidates with complete ALA context | Candidates | Mean support | 50 or more records |
|---|---|---|---|
| Correct species | 93 | 0.79 | 52/93 (56%) |
| Other candidates | 307 | 0.41 | 65/307 (21%) |

| Candidates in flower photos | Candidates | In season | Out of season | No VicFlora months |
|---|---|---|---|---|
| Correct species | 127 | 29 (23%) | 4 (3%) | 94 (74%) |
| Other candidates | 479 | 9 (2%) | 2 (0.4%) | 468 (98%) |

- **Location is informative, so `c` goes to its bound.** Correct species have far more nearby
  records than the other candidates, and a larger `c` raises their score. Because `c` reached the
  end of the grid, the unconstrained optimum may be higher; it stays at 0.50 to keep the image model
  in charge.
- **But location can rarely act.** In 44 training photos Pl@ntNet ranked the correct species 2nd
  to 5th. Only 10 of them had complete ALA context, and only 6 of those were within the ×1.5 that
  the largest boost can make up. One unresolved name among five turns support off, and Pl@ntNet's
  world-flora candidates often include names ALA cannot resolve to a species, such as the Korean fir
  *Abies koreana*, which it matches only to the genus. Even without that rule, only 12 of the 44 were
  within ×1.5: Pl@ntNet's leader usually leads by more.
- **The flowering check lowers the right answer.** It flagged 6 candidates, 4 of them the correct
  species. The VicFlora table covers 103 species, the 100 most recorded around Parkville plus the
  demo species, so wrong candidates, often not local, mostly have no data and are never lowered. A
  rule that only lowers mismatches therefore mostly penalises local species, which are the likely
  answers. VicFlora's months also describe plants across Victoria, while campus plants are often
  planted and watered. `f` = 1.00 turns the adjustment off.
- **The radius does not matter here:** 2, 8 and 25 km gave the same cross-validated score.

## What the app uses

`RankSpeciesCandidatesUseCase` defaults to `c` = 0.50 and `f` = 1.00, and the 8 km radius is
unchanged. Observations record the rule as
`ala-positive-support-v1-cap0.50-saturation50+flowering-mismatch-v1-x1.00-tolerance1-flower0.5+vicflora-2026-09-25`.
The results card still shows the VicFlora statement and how the capture month relates to it, for
reference, but the flowering check no longer changes the order. The mechanism stays, so a better
flowering source can be tested with the same harness.

## Limitations

- Labels are iNaturalist community identifications, not expert determinations. Cultivated
  observations cannot reach research grade, so their labels rest on at least two agreeing
  identifications.
- Pl@ntNet does not disclose its training images, so some of these public photos may have been
  seen in training, which would make image accuracy optimistic. All three models share the same
  image scores, so the comparison between them is unaffected.
- ALA includes research-grade iNaturalist records, so nearby counts partly reflect the same
  community's activity. The observation's own record is excluded; its observer's other records are not.
- iNaturalist photos are framed and sized differently from the app's captures, and Pl@ntNet's
  answers may change as its model is updated; the responses are cached as retrieved.
- 156 test photos with no changed answers cannot detect a small effect in either direction, and the
  data covers one area around Parkville.

## Reproduce

The collector reads the Pl@ntNet key from `local.properties` and never writes it out. It queries
live APIs, so a new run samples and scores again; the committed files are the data behind this page.

```bash
python3 tools/build-evaluation-set.py
```

```bash
python3 tools/build-evaluation-set.py --per-stratum 65 --split train --exclude app/src/test/resources/evaluation/pilot.json --out app/src/test/resources/evaluation/train.json
```

Training and testing need no network and write `app/build/reports/evaluation/fusion-training.md`:

```bash
./gradlew testDebugUnitTest --tests '*FusionParameterTraining*'
```
