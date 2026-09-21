# Missing-context policy (#19)

Decision before implementation: use a cue only when every candidate has the required
evidence. Disable an incomplete cue for the entire candidate set, keeping all candidates.
This sacrifices some usable evidence rather than biasing the ranking against unknowns.
Keep existing weights, smoothing and the 8 km radius; this is not parameter tuning.

- A present occurrence count of zero is a successful result, not missing data.
- Failed requests and unresolved taxa have no count; never substitute demo counts in live mode.
- Empty preferred months or an absent entry for the selected habitat disable that cue.
- With no eligible cues, return the image-only baseline, including its scores.
- Only the explicit guided demo may use deterministic demo counts and demo coordinates.
- Live location must have valid coordinates, known accuracy within 1 km, and a fix no
  older than 2 minutes when analysis starts. These conservative prototype thresholds
  are eligibility guards, not scientifically calibrated parameters.
- Freeze location for the analysis/retries so later movement does not change its context.
- Explain disabled cues and retain lookup warnings in the results, even after snackbars clear.
- The existing observation schema requires coordinates. Until nullable-location storage is
  implemented, identification remains available without GPS but saving requires a retake
  with usable location. Never persist campus coordinates as the location of a live capture.
- Resolve an exact species-level name match using ALA name matching before querying
  occurrences by taxonConceptID. Ambiguous, fuzzy, higher-rank and synonym-only matches
  are unresolved for now, not zero counts. Request failures remain separately reported.

API reference: https://docs.ala.org.au/ (Namematching and Occurrences).

No ecological metadata is invented. Taxonomy investigation #10, metadata enrichment,
and model evaluation remain separate work.
