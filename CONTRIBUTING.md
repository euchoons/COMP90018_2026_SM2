# Contributing to BioScout

This project is assessed both as a group system and through individual code ownership. The repository workflow therefore has two goals: keep the app stable and preserve clear evidence of who designed, implemented, tested and reviewed each part.

## Before starting work

1. Read the root `README.md`, `docs/APP_OVERVIEW.md`, `docs/ARCHITECTURE.md` and `docs/PROJECT_STATUS_AND_ROADMAP.md`.
2. Run `./tools/check.sh` on your machine.
3. Run the guided demo and, where relevant, test on a physical phone.
4. Select an issue or workstream with a concrete acceptance condition.
5. Confirm that the work is consistent with the agreed Assignment 1 scope.

## Branches and commits

Create a short-lived branch from the current integration branch:

```text
feature/tflite-classifier
feature/ala-cache
fix/location-permission-loop
test/sensor-threshold-study
docs/a1-connectivity-plan
```

Write commits around coherent outcomes rather than arbitrary file batches. Examples:

```text
feat(sensor): add calibrated stability sampling window
feat(context): cache ALA counts with a 24-hour TTL
test(fusion): add image-only versus fused accuracy fixtures
docs(a1): justify connectivity and offline behaviour
```

Avoid vague commits such as `update`, `final changes`, `helped with code` or a single enormous commit containing unrelated work.

## Pull requests

Use the repository pull-request template. A pull request should state:

- the user-visible or technical result;
- which rubric criteria it supports;
- tests and device conditions;
- screenshots, logs or measurements where appropriate;
- known limitations and fallback behaviour;
- AI tools used and the human verification performed.

At least one teammate should review non-trivial changes. The author remains responsible for understanding all retained code, including code initially suggested by an AI tool.

## Definition of done

A change is complete when:

- the app builds and existing tests pass;
- new non-trivial domain or data logic has focused tests;
- camera, sensor, location and permission changes have physical-device evidence;
- loading, denied-permission, missing-sensor and network-failure states are considered;
- user-facing text does not misrepresent relative ranking scores as calibrated confidence;
- documentation and Assignment 1 planning are updated when scope or architecture changes;
- another team member has reviewed the change.

## Code organisation

Keep responsibilities within the existing boundaries:

- `domain/` contains framework-independent models and algorithms;
- `data/` contains concrete classifier, ALA and persistence implementations;
- `platform/` wraps Android sensors and location;
- `ui/` renders state and sends user actions to the ViewModel;
- `di/AppContainer.kt` wires implementations to interfaces.

Do not place HTTP calls, sensor calculations or ranking logic directly inside composables. Prefer small interfaces and constructor-injected dependencies so behaviour remains testable.

## Tests and evidence

Minimum local checks:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

For a change involving external or physical behaviour, record the conditions:

- phone model and Android version;
- permissions granted, denied or approximate;
- network connected, slow or disabled;
- sensor availability;
- location source and accuracy;
- measured latency or observed result.

Do not use an emulator as proof of physical accelerometer, gyroscope, light, magnetometer, camera or GPS behaviour. Store small, intentional evidence under `docs/evidence/`; do not commit large videos or personal data.

## Privacy and data handling

Do not commit API secrets, service-account files, personal coordinates, photos of identifiable people or sensitive-species locations. The current observation store rounds latitude and longitude before persistence; future cloud work must preserve or strengthen this protection.

## AI-assisted work

AI use is permitted by the assignment only when it is acknowledged and the submitted work remains the group's reviewed work. Record material use in `docs/assignment-1/AI_USE_LOG.md`. For code, note:

- the tool and model shown at the time;
- the task or representative prompt;
- files or decisions influenced;
- what was changed by the team;
- how the result was tested or independently verified.

Never commit AI-generated code that no team member can explain or modify.
