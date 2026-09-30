# Missing context policy (#19)

Decision: build on PR #31's capture snapshots, reliable ALA transport and existing
live ranking rule. Do not restore the older location or storage implementation.

## Live lookup

- Resolve the candidate's scientific name to a species-level ALA taxon ID before
  querying occurrences by `taxonConceptID`. ALA must match the name exactly, or
  canonically (differing only in authorship or formatting). An objective synonym (same
  type as the accepted name, e.g. Pl@ntNet's *Melaleuca citrina* for ALA's *Callistemon
  citrinus*) is the same species, so it resolves to the accepted taxon, whose ID ALA
  already returns, and the results show the accepted name.
- A resolved taxon with a successful count of zero is known zero, not absence of
  the species. Positive counts are occurrence records, not population estimates.
- No match, a fuzzy or higher-rank match, and subjective, pro parte or misapplied
  synonyms are `UNRESOLVED_TAXON`: no count and no automatic retry. Those can denote a
  different plant, so matching stays conservative there.
- Malformed responses and network/HTTP failures remain distinct from unresolved
  names. Both requests use the same cancellation, size limits, redirect policy,
  timeouts and repository retry budget. A retried attempt starts with name matching.
- Never fill missing live counts with zero or demo counts. Existing failure details
  and persistent warnings expose the reason; partial/all failures remain image-only.

## Ranking and location

The geographic part of the live rule is `imageScore * (1 + 0.5 * support)`,
normalised over the candidate set, with `support = ln(1 + min(count, 50)) / ln(51)`.
Apply it only when every candidate has a successful non-negative live count. Otherwise
geographic support is neutral for every candidate; available counts remain visible as context.
The trade-off is losing usable partial evidence rather than favouring candidates
whose lookups happened to succeed. #20 fitted the cap of 0.5, its design bound, and kept the
8 km radius ([training](FUSION_EVALUATION.md)); the saturation of 50 was not fitted.

Unresolved names count as missing, so a single unresolved candidate disables geographic
support for the whole capture. A subjective or pro parte synonym, an infraspecific name or a
species outside the Australian name index among the five candidates is enough to disable it,
which can make the boost rare in practice.

PR #31 deliberately limits both displayed and reranked candidates to the first five
Pl@ntNet results (the API requests eight). This limit is unchanged here and must be
reported when evaluating candidate recall. Lookup failures never remove a candidate
from that five-candidate set.

Keep `LocationFreshnessPolicy`: valid coordinates, known accuracy at most 2 km,
fix at most 60 seconds old using monotonic time. Freeze the location/source at the
shutter in `CaptureSnapshot`; retries never replace it with a later fix. Denied,
missing or unreliable location skips ALA. These are prototype eligibility settings,
not validated scientific thresholds. A live capture never uses campus demo coordinates.

## Flowering season

`live()` also checks each candidate against its documented flowering months, independently of ALA:

- It applies only when Pl@ntNet's `predictedOrgans` reports a flower with a score of at
  least 0.5. Flowering months say nothing about leaf, bark, fruit or whole-plant photos,
  so those, and a missing organ, leave every candidate at 1.0.
- A candidate is out of season only when all of its documented flowering months are more
  than one month from the capture month (the device-local date of the shutter press).
  Candidates in season, within a month of it, or absent from the flowering table are not.
- An out-of-season candidate is multiplied by a factor that #20 trained to 1.0, so the check
  is shown but no longer changes the order: on the training photos, 4 of the 6 candidates it
  flagged were the correct species ([training](FUSION_EVALUATION.md#why-training-chose-these-values)).
- The table is keyed by scientific name, plus the WCVP name Pl@ntNet uses where WCVP treats
  VicFlora's name as a synonym (see below). Any other name is unknown, not out of season.

Unlike geographic support, this cue is per candidate. Unknown counts as in season, so
partial coverage cannot favour species that happen to have data: a listed species can only
lose, and only on affirmative evidence, a photographed flower outside a documented
flowering period. A zero ALA count only means nobody recorded the species nearby, so it
stays neutral. Training exposed the flaw in this design: the table covers local species, so
wrong candidates, which are mostly not local, seldom have data, and the check mostly lowers the
likely answers. The tolerance and organ threshold were not fitted.

Example with the bundled data: for a wattle flower photographed in February, *Acacia
implexa* (flowers Dec.–Mar.) is in season, *Acacia mearnsii* (Sep.–Nov.) is out of season, and
*Acacia dealbata* (no VicFlora statement) is unknown. A leaf photo leaves the check unused.

The results card shows the check for the selected candidate: the VicFlora statement and how
the capture month relates to it, or why the cue did not apply, with a link to VicFlora and
its CC BY 4.0 attribution.

Habitat does not participate in `live()`: by the #17 decision it is observation metadata only
(see [data sources](FUSION_DATA_SOURCES.md#habitat-17)). Merely filling Species
fields changes nothing. The old log-linear formula and synthetic priors remain confined to
the explicit guided demo; demo evidence must never enter live ranking.

### Flowering data (#16)

[`vicflora-flowering.tsv`](../app/src/main/assets/vicflora-flowering.tsv) is generated by
`python3 tools/build-flowering-table.py` (retrieved 2026-09-25; WCVP aliases refreshed with
`--names` on 2026-09-27):

- Scope: the 100 plant species most recorded in ALA within 8 km of Parkville, plus the
  guided-demo species. This approximates plants seen around campus; it is not a campus
  inventory. Cultivated plants that VicFlora does not treat, such as jacaranda and London
  plane, stay unknown.
- Source: the flowering sentence that ends each VicFlora species description (Royal Botanic
  Gardens Victoria, CC BY 4.0). Each row keeps the verbatim sentence, taxon URL, profile date
  and license. The statements describe plants across Victoria, so irrigated or cultivated
  campus plants may flower outside them.
- Parsing: months and ranges as written; seasons follow the Bureau of Meteorology (spring is
  Sep.–Nov., and so on). Qualifiers such as "mainly" keep the stated period, which the
  one-month tolerance softens. "All year" and "most of the year" become all
  twelve months, so those species are never out of season. Any other wording stays unknown.
- Result: 75 of 103 species documented. 25 VicFlora descriptions have no flowering sentence
  (e.g. silver wattle, white clover) and 3 names are not VicFlora taxa; these rows stay in
  the file with their status so the gap is visible.

Names (#18): Pl@ntNet appears to follow Kew's World Checklist of Vascular Plants (WCVP),
while VicFlora follows the Australian Plant Census. When WCVP files a VicFlora name only as a
synonym of one species, the table adds that accepted name as `wcvp_name`, provided ALA
confirms it as an objective synonym of the same species, the rule the ALA lookup
uses. *Callistemon citrinus* is thus found under *Melaleuca citrina*, and the results card
names the VicFlora taxon the statement belongs to. An alias never replaces another species'
own row; any other mismatch stays unknown. Evidence and the aliases are in
[data sources](FUSION_DATA_SOURCES.md#names-18).

The saved rule label ends with the retrieval date, and a unit test keeps it in step with the file.

## Evaluation

[#20](FUSION_EVALUATION.md) fitted the parameters on 339 iNaturalist photos and compared the
image-only, hand-set and trained rules on 156 held-out ones. No rule changed a Top-1 answer.
Geographic support applied to 26% of the test photos; an unresolved candidate name blocked the
rest. The flowering check found an out-of-season candidate in 1 of 156. The scores are not
calibrated probabilities, and the taxonomy investigation #10 stays open.

ALA API reference: https://docs.ala.org.au/ (Namematching and Occurrences).
