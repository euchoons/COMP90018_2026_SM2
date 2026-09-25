# Missing context policy (#19)

Decision: build on PR #31's capture snapshots, reliable ALA transport and existing
live ranking rule. Do not restore the older location or storage implementation.

## Live lookup

- Resolve the candidate's scientific name to an exact, species-level ALA taxon ID
  before querying occurrences by `taxonConceptID`.
- A resolved taxon with a successful count of zero is known zero, not absence of
  the species. Positive counts are occurrence records, not population estimates.
- No match, fuzzy/higher-rank match, or a different accepted name is
  `UNRESOLVED_TAXON`: no count and no automatic retry. Exact matching is deliberately
  conservative; validated synonym support remains separate taxonomy work.
- Malformed responses and network/HTTP failures remain distinct from unresolved
  names. Both requests use the same cancellation, size limits, redirect policy,
  timeouts and repository retry budget. A retried attempt starts with name matching.
- Never fill missing live counts with zero or demo counts. Existing failure details
  and persistent warnings expose the reason; partial/all failures remain image-only.

## Ranking and location

The geographic part of the live rule remains `imageScore * (1 + 0.15 * support)`,
normalised over the candidate set, with `support = ln(1 + min(count, 50)) / ln(51)`.
Apply it only when every candidate has a successful non-negative live count. Otherwise
geographic support is neutral for every candidate; available counts remain visible as context.
The trade-off is losing usable partial evidence rather than favouring candidates
whose lookups happened to succeed. These constants are provisional, not optimised.

Unresolved names count as missing, so a single unresolved candidate disables geographic
support for the whole capture. A synonym, an infraspecific name or a species outside the
Australian name index among the five candidates is enough to disable geographic support,
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

`live()` also multiplies each candidate by a flowering-season factor, independently of ALA:

- It applies only when Pl@ntNet's `predictedOrgans` reports a flower with a score of at
  least 0.5. Flowering months say nothing about leaf, bark, fruit or whole-plant photos,
  so those, and a missing organ, leave every candidate at 1.0.
- A candidate drops to 0.85 only when all of its documented flowering months are more
  than one month from the capture month (the device-local date of the shutter press).
  In season, within a month of it, or absent from the flowering table all stay at 1.0.
- The table is keyed by exact scientific name, like ALA; a synonym is unknown, not out
  of season.

Unlike geographic support, this cue is per candidate. Unknown counts as in season, so
partial coverage cannot favour species that happen to have data: a listed species can only
lose, and only on affirmative evidence, a photographed flower outside a documented
flowering period. A zero ALA count only means nobody recorded the species nearby, so it
stays neutral. The 0.85 bound mirrors the 15% geographic bound and can only reorder
candidates whose image scores are within about 18%. The factor, tolerance and organ
threshold are provisional.

The flowering table is empty until #16 supplies sourced, Victoria-applicable months with
their source and version, so the cue does not fire yet. Habitat does not participate in
`live()`; #17 owns that decision. Merely filling Species fields changes nothing. The old
log-linear formula and synthetic priors remain confined to the explicit guided demo; demo
evidence must never enter live ranking.

## Evaluation boundary

#20 will compare the provisional bounded boost, image-only baseline and any agreed
alternative on the same held-out cases. It must also report the share of live captures
with complete context, i.e. how often geographic support was applied at all, and how many
of the others were blocked by unresolved names rather than failed lookups. Report the
flowering cue separately: how often a flower photo changed a multiplier, and each case it
helped or harmed. This change
does not select optimal weights, prove either formula superior, calibrate scores or close
the taxonomy investigation #10.

ALA API reference: https://docs.ala.org.au/ (Namematching and Occurrences).
