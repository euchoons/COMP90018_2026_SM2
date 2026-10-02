# Project status and roadmap

Updated 2026-10-01 against the current implementation. This page owns current feature status, remaining work and scope decisions. The [Assignment 1 plan](assignment-1/COMP90018_2026_T01_03_03_Assignment_1.md) and [initial milestones](archive/INITIAL_MILESTONES.md) preserve the earlier proposal.

## Current status

“Implemented in source” does not mean deployed services, field performance or all devices have been validated.

| Area | Status | Current capability and boundary |
|---|---|---|
| Android UI | Implemented in source | Home, Observe, Results, Field Guide and Account flows; device and accessibility acceptance remain separate checks. |
| Camera | Implemented in source | CameraX capture, rotation, resize and permission handling; the [physical-device checklist](testing/CAMERA_VALIDATION.md#physical-device-checklist) remains required. |
| Motion sensing | Implemented in source | Accelerometer/gyroscope stability gate with provisional thresholds; [measured calibration](technical/MOTION_STABILITY_CALIBRATION.md#device-calibration-procedure) is still required. |
| Light and heading | Implemented in source | Optional guidance and capture-time heading with missing-sensor fallbacks; [device checks](testing/HARDWARE_ADAPTERS_VERIFICATION.md#device-checklist) remain open. |
| Location | Implemented in source | Capture-time freshness/accuracy eligibility; unusable location skips ALA. Campus coordinates belong to the guided demo only. |
| Image recognition | Implemented in source (cloud) | Firebase upload → stored-photo download → Pl@ntNet identification. The API requests eight candidates; the UI/ranker retain five. Live use requires configuration, an authenticated Firebase session and network access. The on-device model was dropped (#50). |
| ALA connectivity | Implemented in source | Species-level name resolution, concurrent count requests, bounded retry and explicit live/partial/unavailable states. |
| Fusion algorithm | Implemented and trained offline | Geographic support trained in #20, with names ALA cannot match counted as zero records; the flowering check is shown but does not reorder. See the [live policy](technical/MISSING_CONTEXT_POLICY.md). Log-linear season/habitat priors are confined to the guided demo. |
| Accounts | Implemented in source | Firebase registration, sign-in, online guest, offline entry, sign-out and account actions. Backend/device verification and erasure limitations are separate from implementation presence. |
| Observation storage | Implemented in source | Room local records, Firestore metadata, Firebase Storage photos and WorkManager synchronisation/retry. Preferences are a legacy import source, not the active store. |
| Privacy | Implemented with limitations | Coarse coordinates, diagnostic handling and deletion paths are documented in [data handling and limitations](PRIVACY_POLICY.md). Complete erasure and encryption-upgrade guarantees are not established. |
| Map | Implemented in source | Field Guide displays saved observation locations through Google Maps when a key is set at build time, and a placeholder without one; an invalid key or missing Play services is not detected at runtime. Field usability remains to be verified. |
| Evaluation | Partly done | Scoped/unit checks exist. [Fusion training](technical/FUSION_EVALUATION.md) (#20) tested the live rule on 156 held-out iNaturalist photos: Top-1 rose from 81% to 88%. Live latency (#53), usability and field studies remain outstanding. |

## Claims that are not currently supported

- Reliable identification of every campus species, calibrated confidence or a validated model trained by this team.
- Offline/on-device live identification or direct observation uploads to ALA.
- Complete account erasure, guaranteed encryption of every existing database or independently validated deployed Firebase rules.
- A completed campus field study, sensor calibration across all phones or an executed device checklist without recorded evidence.
- The #20 fusion result as general superiority: it is a 156-photo test from one area around Parkville.

## Next work

1. Complete and record the [device API cases](testing/DEVICE_API_TEST_CASES.md), camera checklist and sensor calibration on the required physical phones. Keep device, Android version, tested revision and evidence with each result.
2. Review and integrate the separate per-photo consent work in [PR #48](https://github.com/euchoons/COMP90018_2026_SM2/pull/48). The app currently uploads after capture without that consent step.
3. Verify configured Firebase/Maps behaviour, offline synchronisation/retry and the remaining [privacy limitations](PRIVACY_POLICY.md) against explicit acceptance evidence.
4. Finish accessibility and task-based usability checks, then assemble the report, demonstration video, reproducible build evidence and itemised contributions.

## Risk register

| Risk | Consequence | Mitigation and decision gate |
|---|---|---|
| Real model performs poorly outdoors | Core identification story is weak. | Restrict species set, collect representative photos, show Top 3/unknown and evaluate early. |
| Pl@ntNet free quota is 500 identifications a day | Live demo fails mid-presentation. | `remainingIdentificationRequests` is logged on every call; a quota error asks the user to retry later, and the guided demo stays available offline. |
| Cloud identification needs network and adds latency | No identification in poor coverage; ~3.4 s measured. | Accepted: the on-device model was dropped (#50). Show the image-only result as soon as the cloud pipeline returns, keep the offline guided demo and measure Pl@ntNet latency. |
| Model labels do not match ALA taxonomy | Nearby counts are missing or misleading. | ALA name matching accepts only exact, canonical or objective-synonym species matches, the flowering table carries WCVP aliases (#18), and representative synonyms are tested. |
| ALA latency or availability varies | Reranking is slow or unavailable. | Show image-only first, use bounded retries, retain partial/unavailable states and measure latency. A persistent ALA response cache remains future work. |
| Sensor availability differs by phone | Features fail on some devices. | Runtime checks, manual fallbacks and testing on multiple physical devices. |
| Too many social/game features | Core system remains incomplete. | One showcase extension only; treat all other gamification as stretch scope. |
| False confidence harms trust | Users accept incorrect identifications. | Relative-score language, explanation, multiple candidates, user confirmation and unknown fallback. |
| Exact locations create privacy/ecology risks | Personal or sensitive information is exposed. | Coarsen data, add consent and deletion, restrict public precision and document policy. |
| Work is concentrated in one member | Contribution rubric and viva risk. | Assign concrete code/test/evidence ownership and review balance weekly. |
| AI-generated code is not understood | Academic-integrity and viva risk. | Log AI use, require human review, tests and author explanation before merge. |

## Scope decisions

| Date | Decision | Reason | Record |
|---|---|---|---|
| 2026-09-25 | Habitat is observation metadata only and does not affect live ranking. | No source maps species onto the app's habitat categories. | #17 |
| 2026-10-01 | Drop the on-device LiteRT model (Pl@ntNet-300K ResNet18); identification stays cloud-only through Pl@ntNet. | Integration cost, and little benefit: Pl@ntNet-300K covers only 22 of the 309 species in the #20 Parkville evaluation set. | #50 |

## Scope-control rule

A new feature should only enter the committed MVP when the group identifies:

- the user problem it solves;
- the Assignment 2 criterion it strengthens;
- its owner and reviewer;
- acceptance tests and evidence;
- the core task that will be removed or delayed to make room.
