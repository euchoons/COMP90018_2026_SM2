# PR drafting aid: scoped API regression tests

Use the repository's existing PR template. Copy the relevant text below and fill
in actual results. This is not a submitted PR and is not completed test evidence.

## Suggested title

`test(api): add scoped PlantNet Firebase and ALA regression tests`

## Summary

Add 46 local JUnit test methods for existing PlantNet transport/classification
failure handling, Firebase photo storage boundaries and integrity checks, and ALA
validation/reliability. Preserve existing tests and production behaviour.

## Scope and project relevance

The change supports reliable external-data handling and the existing stored-photo
identification workflow. It stays within PlantNet, Firebase photo transfer, ALA
read-only context, and evidence recording. Traceability is in
`docs/testing/API_TEST_CASES.md` and is based on existing implementation contracts,
not new feature requirements or invented rubric IDs.

No production source, Gradle dependencies, Firebase configuration/rules, keys,
CameraX/sensors, map/UI, Room/Firestore or sync-worker logic is changed.

## Changes

Four new test classes contain 46 methods. A scoped PowerShell runner additionally
selects 34 existing relevant methods without claiming them as new work. The runner
checks the six relevant production files against the inspected source snapshot
and writes evidence from actual fresh JUnit results, not from a prefilled result.

Inspected baseline: `main`, `ad79b69f723868d7079182426a875766c98270c5`.

## Testing — replace with actual local evidence before requesting review

| Item | Actual status / evidence |
|---|---|
| Contributor and tested Git HEAD | NOT RECORDED |
| JDK / Gradle / operating system | NOT RECORDED |
| New suite: expected 46 methods | NOT RUN — attach actual summary |
| Scoped suite: expected 80 methods | NOT RUN — attach actual summary |
| Full testDebugUnitTest / lintDebug / assembleDebug | NOT RUN — record separately |
| Evidence path | NOT RECORDED |
| Teammate review | PENDING |

Authoring-environment verification is documented separately in
`API_TEST_AUTHOR_VERIFICATION.md`. Syntax parsing is not a JUnit pass and is not
substituted for the local Gradle execution above.

## Limits

These are synthetic/fake-transport and mocked-SDK tests. They do not prove deployed
Firebase Rules, live credentials, real service availability, physical sensor
behaviour, identification accuracy, real latency or complete device acceptance.
SDK cancellation coverage in the new Firebase class tests an already-cancelled
Task, not live network cancellation timing. Existing pipeline tests remain in place.
Do not merge while project checks fail or claim a full-project pass from a scoped run.

## AI assistance and ownership

ChatGPT assisted with source inspection, test drafting, the runner and documentation.
The contributor must review and run the code, record actual changes/verification,
and be able to explain it during the viva. Human review and local execution are
pending until completed and documented by the contributor.

## Entry to append to the existing AI-use log

Do not replace `docs/assignment-1/AI_USE_LOG.md`. Adapt this entry to the log's
existing format and fill in actual human work:

- Date: 2026-09-24.
- Tool: ChatGPT; record the model shown in the contributor's session.
- Task: Draft computer-runnable tests limited to existing FloraGuide PlantNet,
  Firebase photo storage and ALA code, with GitHub paths and truthful test evidence.
- Files affected: Four new API test classes; tools/test-api.ps1; docs/testing API
  register, manifest, verification and PR drafting aid; evidence README.
- AI output: Initial tests, synthetic fixtures, execution script and documentation.
- Human review/changes: PENDING — describe actual review and modifications.
- Verification: Authoring Kotlin syntax parsing only; contributor Gradle results,
  full repository checks and teammate review must be recorded after execution.
