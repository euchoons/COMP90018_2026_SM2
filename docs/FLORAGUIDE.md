# FloraGuide quick start

FloraGuide is a context-aware Android plant observation app. Start here to build it and try the guided or live route. The [documentation index](README.md) lists the full reference set; the [project status and roadmap](PROJECT_STATUS_AND_ROADMAP.md) tracks implemented features, pending verification and scope decisions.

## Requirements

- Android Studio with Android SDK 36;
- JDK 17 configured as the Gradle JDK;
- Android 8.0/API 26 or newer;
- a physical phone for meaningful camera, sensor and GPS checks.

## Local configuration

Open the repository root in Android Studio and allow Gradle sync to complete.

- **Pl@ntNet:** set `plantnet.api.key` or `PLANTNET_API_KEY` in the git-ignored root `local.properties`, or provide the `PLANTNET_API_KEY` environment variable. Rebuild after changing it. A missing key blocks live identification; the explicitly selected guided demo remains available.
- **Firebase:** live photo identification uploads to Firebase Storage, downloads the stored bytes and sends them to Pl@ntNet. It needs the app's Firebase project configuration, an authenticated session and working service access. Use the team's configured test environment and keep credentials out of Git.
- **Maps:** set `MAPS_API_KEY` in `local.properties` or the environment for Google Maps. Without a configured key or saved locations, the field guide displays its map fallback.

See [technical reference](technical/TECHNICAL_REFERENCE.md) for the request contracts and [data handling](PRIVACY_POLICY.md) for current storage and deletion limitations.

## Build and check

Run from the repository root:

```bash
./tools/check.sh
```

This runs `testDebugUnitTest`, `lintDebug` and `assembleDebug`. To run one check separately:

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

Select the `app` run configuration in Android Studio and deploy it to your device. Generated APKs, build directories, IDE state and `local.properties` are excluded by `.gitignore`.

## Try the app

### Guided demo

Choose the offline entry route if needed, then start the guided demo from Home. It uses a fixed capture date, campus location and deterministic species/context data. It exercises navigation, explanation and saving without live identification requests; it is not recognition-accuracy or sensor evidence.

### Live observation

1. Sign in or start an online guest session with Firebase access.
2. Open Observe, grant the permissions needed for the check and choose the observed microhabitat.
3. Capture a photo using the stability and lighting feedback.
4. Wait for Firebase upload/download and Pl@ntNet identification; the image-only shortlist appears when classification finishes.
5. ALA context is requested for eligible capture-time locations and the shortlist is reranked using the [live context policy](technical/MISSING_CONTEXT_POLICY.md).
6. Inspect the explanation, confirm a candidate and open the Field Guide. Observations are saved locally; signed-in records are queued for cloud synchronisation.

Missing or unreliable location skips ALA. Live failures stay visible and can be retried; they do not silently become guided-demo results. Habitat is saved as metadata and changes ranking only in the synthetic demo. The on-device model was dropped in #50, so live identification requires connectivity.

## Continue reading

- [App overview](APP_OVERVIEW.md) for product scope and evaluation goals.
- [Architecture](technical/ARCHITECTURE.md) to follow capture, identification, context and persistence.
- [Current status](PROJECT_STATUS_AND_ROADMAP.md) for remaining work and decisions.
- [Device API checks](testing/DEVICE_API_TEST_CASES.md), [camera checks](testing/CAMERA_VALIDATION.md) and [sensor checks](testing/HARDWARE_ADAPTERS_VERIFICATION.md) before claiming device validation.
- [Assignment 1 plan](assignment-1/COMP90018_2026_T01_03_03_Assignment_1.md) and [source PDF](<assignment-1/COMP90018_2026_T01_03_03 Assignment 1.pdf>) for the original proposal.
- [Contribution workflow](../CONTRIBUTING.md) before taking ownership of a workstream; record material AI use in the [AI-use log](assignment-1/AI_USE_LOG.md).
