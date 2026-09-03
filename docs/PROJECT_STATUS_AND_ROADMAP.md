# Project status and roadmap

This document is the baseline source of truth for scope. Update it whenever the group changes a major feature, data source, model or evaluation commitment.

## Current status

| Area | Baseline status | What the team may claim now |
|---|---|---|
| Android UI | Implemented | Four-screen Compose prototype with guided and live-observation routes. |
| Camera | Implemented in source | CameraX preview and JPEG capture; team device verification is still required. |
| Motion sensing | Implemented in source | Accelerometer/gyroscope stability fusion with a heuristic threshold. |
| Light and heading | Implemented in source | Optional ambient-light and magnetometer feedback with fallback states. |
| Location | Implemented in source | GPS/network location and a visibly labelled campus demo fallback. |
| Image recognition | Prototype only | Deterministic Top-K adapter; no pixel-based species recognition. |
| ALA connectivity | Implemented in source | Read-only count requests, concurrent lookups, telemetry and fallback; live behaviour must be rechecked by the team. |
| Fusion algorithm | Implemented and unit-tested | Log-linear reranking with smoothing and relative scores. |
| Observation storage | Local prototype | App-private photos and preferences; no shared cloud data. |
| Privacy | Initial measure | Coordinates are rounded before local persistence. |
| Map/mission | Minimal demonstration only | Field-guide progress exists; final showcase extension is undecided. |
| Evaluation | Planned | Unit tests exist; model accuracy, latency, usability and field studies are not complete. |

## Claims that are not currently supported

The baseline must not be described as:

- a reliable plant-identification system;
- a trained or validated AI model;
- a calibrated confidence estimator;
- a Firebase or multi-user application;
- a direct ALA observation-upload client;
- a completed campus biodiversity study;
- proof that all target phones have the required sensors.

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
- run a small TensorFlow Lite inference spike on a phone;
- verify label-to-ALA mapping for representative species;
- verify Firebase or the selected cloud backend with one photo and one metadata record;
- measure live ALA latency and failure behaviour.

These spikes should happen before the group promises the final implementation in strong terms.

### Milestone 2 — core integration

- replace `DemoImageClassifier` with the real model adapter;
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
- run cue ablations and latency measurements;
- test at least two physical phones with different sensor configurations;
- record the final video against every rubric criterion;
- capture compile evidence and export commit logs;
- finalise individual contribution records and viva preparation.

## Risk register

| Risk | Consequence | Mitigation and decision gate |
|---|---|---|
| Real model performs poorly outdoors | Core identification story is weak. | Restrict species set, collect representative photos, show Top 3/unknown and evaluate early. |
| Model labels do not match ALA taxonomy | Nearby counts are missing or misleading. | Create a versioned mapping table using accepted identifiers and test representative synonyms. |
| ALA latency or availability varies | Reranking is slow or unavailable. | Show image-only first, use timeouts, cache results, retain partial/offline states and measure latency. |
| Sensor availability differs by phone | Features fail on some devices. | Runtime checks, manual fallbacks and testing on multiple physical devices. |
| Too many social/game features | Core system remains incomplete. | One showcase extension only; treat all other gamification as stretch scope. |
| False confidence harms trust | Users accept incorrect identifications. | Relative-score language, explanation, multiple candidates, user confirmation and unknown fallback. |
| Exact locations create privacy/ecology risks | Personal or sensitive information is exposed. | Coarsen data, add consent and deletion, restrict public precision and document policy. |
| Work is concentrated in one member | Contribution rubric and viva risk. | Assign concrete code/test/evidence ownership and review balance weekly. |
| AI-generated code is not understood | Academic-integrity and viva risk. | Log AI use, require human review, tests and author explanation before merge. |

## Scope-control rule

A new feature should only enter the committed MVP when the group identifies:

- the user problem it solves;
- the Assignment 2 criterion it strengthens;
- its owner and reviewer;
- acceptance tests and evidence;
- the core task that will be removed or delayed to make room.
