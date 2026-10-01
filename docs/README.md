# Documentation index

Start with the quick start, then use the sections below for product scope, current implementation, testing and historical records. Run documented commands from the repository root unless a document says otherwise.

## Start here

- [Quick start and user flow](FLORAGUIDE.md) — prerequisites, local configuration, build commands and guided/live routes.
- [App overview](APP_OVERVIEW.md) — user problem, intended MVP and success measures.
- [Project status and roadmap](PROJECT_STATUS_AND_ROADMAP.md) — implemented capabilities, remaining work and scope decisions, including the dropped on-device model (#50).
- [Data handling and limitations](PRIVACY_POLICY.md) — current location, storage, diagnostic and deletion behaviour.
- [Contribution workflow](../CONTRIBUTING.md) — issues, branches, review, evidence and AI acknowledgement.

## Technical reference

- [Architecture](technical/ARCHITECTURE.md) — dependencies, package responsibilities, runtime flow and fallbacks.
- [Technical reference](technical/TECHNICAL_REFERENCE.md) — toolchain, API requests, demo formula and evaluation measures.
- [Missing-context policy](technical/MISSING_CONTEXT_POLICY.md) — authoritative description of the live ranking rule, location eligibility, missing evidence and flowering data.
- [Fusion data sources](technical/FUSION_DATA_SOURCES.md) — evidence behind the season, taxonomy, location and habitat decisions.
- [Motion stability calibration](technical/MOTION_STABILITY_CALIBRATION.md) — threshold derivations and the physical-device calibration still required.

## Testing and verification

These documents distinguish test specifications, earlier checks and pending physical-device work. A checklist or historical PASS does not establish a result for a later build.

- [API test cases](testing/API_TEST_CASES.md) — scoped regression inventory, baseline and execution instructions.
- [API test baseline](testing/api-test-baseline.json) — source fingerprints and test selection used by `tools/test-api.ps1`; keep this path stable.
- [Device API test cases](testing/DEVICE_API_TEST_CASES.md) — DT-01 through DT-14 specifications and the execution register.
- [Camera validation](testing/CAMERA_VALIDATION.md) — camera fixes, the reviewed capture/upload path and outstanding device checks.
- [Hardware adapters verification](testing/HARDWARE_ADAPTERS_VERIFICATION.md) — light/heading behaviour and outstanding device checks.

`evidence/` holds local screenshots, run summaries, recordings and import archives. Its contents are git-ignored by default; only `.gitkeep` is tracked. Review and deliberately include any evidence needed for submission. Keep maintained specifications and reports in the tracked directories above.

## Assignment 1

- [Project plan — Markdown](assignment-1/COMP90018_2026_T01_03_03_Assignment_1.md) — original proposal wording, tables, formulas and nine figures.
- [Project plan — original PDF](<assignment-1/COMP90018_2026_T01_03_03 Assignment 1.pdf>) — source document for the Markdown conversion.
- [AI use log](assignment-1/AI_USE_LOG.md) — tool-use record and acknowledgement draft.

The submitted plan is a historical scope reference. Later decisions belong in the current roadmap. Keep its Markdown file beside `COMP90018_2026_T01_03_03_Assignment_1_assets/` so the figures resolve.

## Historical records

- [Initial milestones](archive/INITIAL_MILESTONES.md) — the original pre-submission checklist and proposed delivery stages.
- [Cache and account repair verification](archive/CACHE_AUTH_REPAIR_VERIFICATION.md) — the original repair and UI-integration results, with later limitations identified separately.
- [API test authoring verification](archive/API_TEST_AUTHOR_VERIFICATION.md) — syntax/package checks against the 2026-09-24 source snapshot.
- [API test PR drafting aid](archive/API_TEST_PR.md) — the original draft and unfilled evidence fields, retained for provenance.

## Maintaining these docs

Update feature status and decisions in the roadmap, runtime structure in the architecture, live-ranking semantics in the missing-context policy, and data handling in the privacy document. Other pages should link to those details. Keep completed experiment reports tied to their data and revision, and keep unexecuted checks marked as pending.
