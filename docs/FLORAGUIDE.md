# FloraGuide

FloraGuide is a context-aware Android application for campus biodiversity exploration. It combines an image-derived candidate list with location, flowering season and nearby Atlas of Living Australia occurrence records, explains how those context cues change the final ranking, and records the user-selected microhabitat.

This repository is the **team baseline** for COMP90018. It is intended to be readable, reproducible and honest about what is implemented today versus what remains planned.

## Current prototype boundary

The current `DemoImageClassifier` is deterministic. It does **not** analyse image content and must not be described as a trained species-recognition model. It backs the guided demo and keyless builds, so the complete camera-to-ranking workflow runs offline; live captures use Pl@ntNet.

The following parts are implemented in source code:

- Jetpack Compose screens for Home, Observe, Results and Field Guide;
- CameraX preview and JPEG capture;
- accelerometer and gyroscope fusion for a capture-stability score;
- ambient-light feedback and magnetometer heading;
- GPS/network location with an explicit University of Melbourne demo fallback;
- read-only Atlas of Living Australia occurrence-count requests;
- concurrent context lookup with live, partial and offline fallback states;
- live fusion of image scores with bounded ALA support and a flowering-season check; log-linear image/location/season/habitat fusion only in the guided demo;
- an image-only versus context-fused ranking comparison;
- local observation persistence and coarse-location storage;
- JVM tests for ranking, ALA parsing, telemetry, fallback and cancellation.

The following are **not** implemented yet:

- Firebase authentication, photo storage or shared observation data;
- a validated campus species dataset and calibrated uncertainty;
- the final campus map or team mission feature;
- complete accessibility, field evaluation and cross-device sensor calibration.

The on-device image model in the Assignment 1 plan is out of scope (#50); identification is cloud-only.

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

## Understand the app

The fastest path for a new team member is:

1. Run the guided demo from the Home screen.
2. Read [`APP_OVERVIEW.md`](APP_OVERVIEW.md) for the product scope and innovation claim.
3. Follow one observation through [`ARCHITECTURE.md`](ARCHITECTURE.md).
4. Check what is real, provisional and still planned in [`PROJECT_STATUS_AND_ROADMAP.md`](PROJECT_STATUS_AND_ROADMAP.md).
5. Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) before taking ownership of a workstream.

## Core user flow

```text
Home
  -> Observe
  -> capture photo with sensor feedback
  -> show image-only candidates immediately
  -> query nearby ALA occurrence counts
  -> fuse image, location and flowering season (microhabitat is recorded, not ranked)
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

The domain model and ranking use case do not import Android APIs. The classifier, context source and observation store are behind interfaces so a classifier or storage back end can be replaced without rewriting the UI flow.

## Assignment 1 workspace

Assignment 1 requires traceable planning against the Assignment 2 rubric, an itemised contribution plan, group/member details and acknowledgement of AI use. The remaining working files are under [`docs/assignment-1/`](assignment-1/):

- [Project plan — Markdown](assignment-1/COMP90018_2026_T01_03_03_Assignment_1.md) — the Assignment 1 plan with diagrams and formulas;
- [Project plan — original PDF](<assignment-1/COMP90018_2026_T01_03_03 Assignment 1.pdf>) — the source document;
- [`AI_USE_LOG.md`](assignment-1/AI_USE_LOG.md) — tool-use log and acknowledgement draft.

The plan preserves the original proposal, not a statement of current implementation. Its Markdown file and `COMP90018_2026_T01_03_03_Assignment_1_assets/` directory must stay together for the diagrams to display.

Record any material AI use in `AI_USE_LOG.md` as the work happens, not at submission time.

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
