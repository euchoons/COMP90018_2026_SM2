# FloraGuide

FloraGuide is a context-aware Android application for campus biodiversity exploration. It combines an image-derived candidate list with location, season, user-selected microhabitat and nearby Atlas of Living Australia occurrence records, then explains how those context cues change the final ranking.

This repository is the **team baseline** for COMP90018. It is intended to be readable, reproducible and honest about what is implemented today versus what remains planned.

## Current prototype boundary

The current `DemoImageClassifier` is deterministic. It does **not** analyse image content and must not be described as a trained species-recognition model. It exists so the team can test the complete camera-to-ranking workflow before integrating a real on-device model.

The following parts are implemented in source code:

- Jetpack Compose screens for Home, Observe, Results and Field Guide;
- CameraX preview and JPEG capture;
- accelerometer and gyroscope fusion for a capture-stability score;
- ambient-light feedback and magnetometer heading;
- GPS/network location with an explicit University of Melbourne demo fallback;
- read-only Atlas of Living Australia occurrence-count requests;
- concurrent context lookup with live, partial and offline fallback states;
- log-linear image/location/season/habitat fusion with smoothing and softmax;
- an image-only versus context-fused ranking comparison;
- local observation persistence and coarse-location storage;
- JVM tests for ranking, ALA parsing, telemetry, fallback and cancellation.

The following are **not** implemented yet:

- a trained TensorFlow Lite image model and robust model-label/taxon mapping;
- Firebase authentication, photo storage or shared observation data;
- a validated campus species dataset and calibrated uncertainty;
- the final campus map or team mission feature;
- complete accessibility, field evaluation and cross-device sensor calibration.

## Start in five minutes

### Requirements

- Android Studio with Android SDK 36;
- JDK 17 configured as the Gradle JDK;
- Android 8.0/API 26 or newer;
- a physical phone for meaningful camera, sensor and GPS testing.

### Build

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

Or run all three through:

```bash
./tools/check.sh
```

Open the repository root in Android Studio, allow Gradle sync to complete, select the `app` run configuration and deploy to a device.

Do not commit `local.properties`, `.idea`, `.gradle`, `.kotlin`, `app/build`, APKs or local recordings. The repository `.gitignore` excludes them.

## First repository push

Before uploading, follow [`docs/FIRST_PUSH.md`](docs/FIRST_PUSH.md). It includes a staged-file review, private-remote setup, member Git identities and a rule to tag the baseline only after another teammate has cloned and verified it.

## Understand the app

The fastest path for a new team member is:

1. Read [`docs/TEAM_ONBOARDING.md`](docs/TEAM_ONBOARDING.md).
2. Run the guided demo from the Home screen.
3. Read [`docs/APP_OVERVIEW.md`](docs/APP_OVERVIEW.md) for the product scope and innovation claim.
4. Follow one observation through [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
5. Check what is real, provisional and still planned in [`docs/PROJECT_STATUS_AND_ROADMAP.md`](docs/PROJECT_STATUS_AND_ROADMAP.md).
6. Read [`CONTRIBUTING.md`](CONTRIBUTING.md) before taking ownership of a workstream.

## Core user flow

```text
Home
  -> Observe
  -> capture photo with sensor feedback
  -> show image-only candidates immediately
  -> query nearby ALA occurrence counts
  -> fuse image, location, season and microhabitat
  -> explain the final Top 3
  -> user confirms an observation
  -> save it to the personal Field Guide
```

The guided demo follows the same flow but deliberately uses deterministic candidate and nearby-record data. It is a reliable explanation tool, not evidence that a trained model or live network is functioning.

## Architecture at a glance

```text
Compose UI
   -> FloraGuideViewModel
      -> ImageClassifier
      -> SpeciesContextRepository
      -> RankSpeciesCandidatesUseCase
      -> ObservationRepository
      -> SensorMonitor / LocationTracker
```

The domain model and ranking use case do not import Android APIs. The classifier, context source and observation store are behind interfaces so the team can replace the demo classifier with TensorFlow Lite and local storage with Firebase without rewriting the UI flow.

## Assignment 1 workspace

Assignment 1 requires complete, traceable planning against every Assignment 2 rubric criterion, a one-page itemised contribution plan, group/member details and acknowledgement of AI use. The working files are under [`docs/assignment-1/`](docs/assignment-1/README.md):

- `README.md` — official requirements, page strategy and team workflow;
- `REPORT_OUTLINE.md` — suggested final report structure;
- `RUBRIC_PLAN.md` — English planning draft for all 19 Assignment 2 criteria;
- `CONTRIBUTION_PLAN_TEMPLATE.md` — one-page, concrete and balanced contribution template;
- `AI_USE_LOG.md` — tool-use log and acknowledgement draft.

These files are planning material. The group must review every claim, assign owners, replace placeholders and ensure the final PDF reflects the work the team genuinely intends to complete.

## Useful technical commands

```bash
# Deterministic unit tests; no live ALA connection required
./gradlew testDebugUnitTest

# Android lint
./gradlew lintDebug

# Debug APK
./gradlew assembleDebug

# Optional live check of three ALA queries; requires curl and jq
./tools/verify-ala.sh
```

## Repository policy

Use a private team repository unless the group and teaching staff agree otherwise. Every substantial change should arrive through a reviewable branch or pull request, include evidence, and be understood by the person committing it. The final individual viva asks students about their committed code, so ownership must be real rather than nominal.
