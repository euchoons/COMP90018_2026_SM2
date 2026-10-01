# Initial project milestones

> Historical planning checklist from the original roadmap, archived on 2026-10-01.
> The struck-out on-device tasks retain the #50 scope decision.
> See [current status and next work](../PROJECT_STATUS_AND_ROADMAP.md) for active tracking.

## Decisions required before Assignment 1 submission

The group should explicitly agree on and record:

1. the final taxonomic scope and approximate number of species;
2. the source and licence of the visual model or training data;
3. the mapping strategy between model labels and ALA identifiers;
4. the cloud backend and minimum cloud features;
5. one showcase extension: campus map or team mission;
6. the evaluation dataset, sample size and test protocol;
7. privacy rules for photos, users and sensitive locations;
8. the owner and reviewer for every workstream;
9. the evidence that will demonstrate each Assignment 2 criterion.

Do not submit the Assignment 1 plan with these decisions left implicit.

## Proposed final MVP milestones

### Milestone 0 — team baseline

- every member can build and run the repository;
- the canonical Git repository and branch policy are agreed;
- the current prototype is tagged after team verification;
- each member understands the guided flow and architecture.

### Milestone 1 — scope and feasibility spikes

- freeze the target species list;
- ~~run a small TensorFlow Lite inference spike on a phone~~ (dropped, #50);
- verify label-to-ALA mapping for representative species;
- verify Firebase or the selected cloud backend with one photo and one metadata record;
- measure live ALA latency and failure behaviour.

These spikes should happen before the group promises the final implementation in strong terms.

### Milestone 2 — core integration

- ~~add the on-device TensorFlow Lite adapter alongside the Pl@ntNet cloud adapter and let the
  user or the network state choose between them (both implement `ImageClassifier`)~~ (dropped, #50);
- add unknown/genus fallback;
- add Room context caching;
- implement cloud-backed observation storage and retry;
- preserve live/partial/offline transparency;
- add unit, integration and device tests.

### Milestone 3 — showcase extension and UX

- implement either a campus map or a team mission;
- complete Material 3, accessibility and permission-flow review;
- conduct task-based usability testing;
- refine explanations without implying false certainty.

### Milestone 4 — evaluation and submission evidence

- compare image-only and fused Top-1/Top-3 performance;
- run cue ablations and Pl@ntNet and ALA latency measurements;
- test at least two physical phones with different sensor configurations;
- record the final video against every rubric criterion;
- capture compile evidence and export commit logs;
- finalise individual contribution records and viva preparation.
