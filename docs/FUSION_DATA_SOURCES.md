# Fusion data sources (#16, #17)

Which data could back the live context cues, what was measured, and what was adopted. All
measurements were taken on 2026-09-25. ALA queries use the app's Parkville point
(-37.7963, 144.9614) and its 8 km radius. The ranking rules themselves are in the
[missing-context policy](MISSING_CONTEXT_POLICY.md).

## Summary

| Source | Cue | Decision | Main reason |
|---|---|---|---|
| VicFlora flowering statements | Season | Adopted | Victoria-specific expert statements; 75 of 103 common species parse |
| Pl@ntNet `predictedOrgans` | Season (gate) | Adopted | Returned with every `organs=auto` identification; separates flower photos from leaf, bark or whole-plant photos |
| ALA records per month | Season | Rejected | Single survey days dominate the monthly counts |
| ALA `reproductiveCondition` | Season | Rejected | 98% of records leave it empty |
| AusTraits `flowering_time` | Season | Not used yet | Combines many datasets and regions; Victorian applicability would need checking per source |
| City of Melbourne tree inventory | Location | Not adopted | Council trees only; none inside the campus |
| VicFlora habitat prose | Habitat | Not adopted, pending the #17 decision | Describes associations, not the app's four categories |

## Season (#16)

### Adopted: VicFlora flowering statements, gated by the photographed organ

Each VicFlora species description ends with a flowering sentence such as "Flowers summer."
(river red gum) or "Flowers Aug.–Oct." (golden wattle). Of the 103 species in scope, 75
parse into months, 25 descriptions have no flowering sentence and 3 names are not VicFlora
taxa. Scope, parsing and licence are in the [policy](MISSING_CONTEXT_POLICY.md#flowering-data-16).

Flowering months only describe flowers, so the cue applies only when Pl@ntNet's
`predictedOrgans` reports a flower. A leaf photo of a tree that is not flowering is never
lowered.

### Rejected: ALA records per month

Monthly record counts look like a free, automatic seasonal signal, but they record when
people surveyed, not when plants flower. River red gum (*Eucalyptus camaldulensis*) has 571
records within 8 km; 204 (36%) fall in June, although VicFlora gives summer flowering:

- 169 of the 204 June records came from one consultancy survey on 28 June 2018 (BIOSIS
  Research, via the Victorian Biodiversity Atlas).
- 37 of the 64 January records came from another single BIOSIS survey day, 15 January 2019.
- Across all 56,727 plant records in the same area, June is the quietest month (5%) and
  October the busiest (13%).

Normalising by overall recording effort would therefore still give river red gum a June
signal about 7 times its baseline (36% against 5%). A usable version would need per-survey
deduplication, effort normalisation and minimum sample sizes, and would still only
approximate what a flora states directly.

### Rejected: ALA reproductive condition

Darwin Core's `reproductiveCondition` could say whether a record was flowering. Of 20,750
river red gum records in Victoria, 20,385 (98%) leave it empty, and the rest are free text
such as "buds", "fruit | buds" or "flowers".

### Not used yet: AusTraits

AusTraits' `flowering_time` trait is structured and covers more species, but AusTraits
combines many datasets from different regions, so Victorian applicability would need
checking per source. VicFlora was sufficient for the initial scope.

### Rule alternatives

| Option | Behaviour | Decision |
|---|---|---|
| Boost in-season species, only when all five candidates have data (the ALA pattern) | Unknown species are never favoured against, but a small table seldom covers all five world-flora candidates, so the cue would rarely apply | Rejected |
| Lower documented mismatches only, per candidate | Unknown counts as in season, so partial coverage cannot favour species that have data; bounded at 0.85 | Adopted |

## Location

### Not adopted: City of Melbourne tree inventory

The [Urban Forest dataset](https://discover.data.vic.gov.au/dataset/trees-with-species-and-dimensions-urban-forest)
lists 82,064 council-managed trees with species, coordinates and a Park/Street location (CC BY,
modified 2025-09-22). It would add council trees that ALA misses, but it contains no trees
within 150 m of the Old Quad (-37.7975, 144.9610); the 380 within 400 m are street (331) and
park (49) trees around the campus. It is a second location cue, not habitat evidence, and
does not cover the campus interior.

## Habitat (#17)

The app's four choices (under tree canopy, open lawn, garden bed, wetland or water edge) mix
light, management and water, so one spot can match several. The available evidence does not
map onto them:

- VicFlora describes habitat in prose. English daisy is "a common component of well-watered
  lawns", which supports a lawn association but not a numeric affinity.
- River red gum is "Widespread along rivers", yet the council inventory lists 8,338 river red
  gums in parks (7,629) and streets (709). In a city, and on a campus, where a plant grows is
  largely a planting decision rather than ecology.
- No source found gives values for the four categories; any numbers would be invented.

Recommendation, pending the team's scope decision (#15 requires one before habitat is
dropped for good): keep habitat as observation metadata and do not rank with it.

## Reproducing the measurements

URL-encode the parameters when running these. ALA occurrence search is
`https://api.ala.org.au/occurrences/occurrences/search`; river red gum's taxon ID is
`https://id.biodiversity.org.au/node/apni/2921040`.

```text
Red gum by month:   q=taxonConceptID:"<red gum ID>"&lat=-37.7963&lon=144.9614&radius=8&pageSize=0&facets=month&flimit=13
Survey days:        same, plus fq=recordedBy:"BIOSIS Research Pty Ltd"&facets=year,month,day&fsort=count
All plants by month: q=*:*&fq=kingdom:Plantae&lat=-37.7963&lon=144.9614&radius=8&pageSize=0&facets=month&flimit=13
Phenology field:    q=taxonConceptID:"<red gum ID>"&fq=stateProvince:Victoria&pageSize=0&facets=reproductiveCondition&flimit=15
Council trees:      https://data.melbourne.vic.gov.au/api/explore/v2.1/catalog/datasets/trees-with-species-and-dimensions-urban-forest/records
                    ?select=located_in,count(*) as n&group_by=located_in
                    &where=within_distance(coordinatelocation, geom'POINT(144.9610 -37.7975)', 150m)
```

The VicFlora species scope and statements are regenerated by `tools/build-flowering-table.py`.
Counts change as ALA and the council inventory are updated.
