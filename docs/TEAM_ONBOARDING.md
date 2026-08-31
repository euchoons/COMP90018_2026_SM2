# Team onboarding

The goal of onboarding is not merely to make the project compile. Every contributor should understand the user problem, the current prototype boundary, the data flow, the planned Assignment 1 claims and the part of the system they will own.

## First 30 minutes

### 0–5 minutes: build the baseline

Run:

```bash
./tools/check.sh
```

If the build fails, record the Android Studio version, Gradle JDK, SDK installation and full error. Do not commit `local.properties` or generated build directories.

### 5–10 minutes: run the guided demo

Open FloraGuide and choose **Run the 60-second guided demo**. Observe this sequence:

1. an image-only candidate list appears;
2. deterministic location/season/habitat context is applied;
3. the final ordering changes;
4. the result explains the four evidence components;
5. changing the habitat reruns the fusion locally;
6. confirming a species creates an entry in the Field Guide.

The guided demo is designed to explain the product. It does not prove that a real image model, GPS or ALA network request worked.

### 10–15 minutes: inspect the live Observe screen

On a physical phone, review:

- CameraX preview and photo capture;
- stability score from accelerometer and gyroscope;
- ambient light level and quality message;
- magnetometer heading;
- live or demo location status;
- permission and missing-sensor fallbacks.

A teammate working on sensors must test real device variation rather than rely on an emulator.

### 15–25 minutes: trace one observation through the code

Follow this path:

1. `MainActivity.kt` starts the Compose application.
2. `FloraGuideApp.kt` selects the screen from `FloraGuideUiState`.
3. `FloraGuideViewModel.kt` coordinates capture, classification, context lookup, reranking and persistence.
4. `DemoImageClassifier.kt` returns a deterministic Top-K list.
5. `AlaSpeciesContextRepository.kt` obtains live, partial or fallback nearby counts through `AlaOccurrenceClient.kt`.
6. `RankSpeciesCandidatesUseCase.kt` combines image, location, season and habitat evidence.
7. `PreferencesObservationRepository.kt` stores a confirmed observation locally.
8. `SensorMonitor.kt` and `LocationTracker.kt` adapt Android platform data into domain models.

Read `docs/ARCHITECTURE.md` while following these files.

### 25–30 minutes: understand the assignment plan

Read `docs/assignment-1/README.md`, then scan `RUBRIC_PLAN.md`. Check that you can distinguish:

- functionality already present in the baseline;
- functionality proposed for the final application;
- evidence the team plans to collect;
- unresolved decisions or risks.

Choose a workstream only after the group agrees on the final species scope and the single showcase extension.

## Shared vocabulary

**Image candidate** — one species proposed by the visual model before ecological context is applied.

**Top-K** — a candidate set larger than the three results shown to the user. Keeping more candidates lets context recover a species that was not initially in the visual Top 3.

**Location prior** — a smoothed value derived from nearby occurrence counts. It is not proof that a species is present.

**Seasonal prior** — a heuristic or learned value representing how compatible a candidate is with the date.

**Microhabitat prior** — compatibility with a user-observed local setting such as canopy, lawn, garden bed or water edge.

**Relative ranking score** — the softmax-normalised score within the current candidate set. It is not a calibrated probability of correct identification.

**Fallback** — explicit behaviour used when a permission, sensor, location provider or network source is unavailable.

## Questions every member should be able to answer

- What user problem does FloraGuide solve?
- What is the current visual-model limitation?
- Why do we generate more than three image candidates?
- How can location, season and habitat change the order?
- What happens if ALA is unavailable or only some requests succeed?
- Which sensors are used, and what user-visible behaviour does each enable?
- Why is the stored location coarsened?
- Which part of the system will you own, test and explain in the viva?
