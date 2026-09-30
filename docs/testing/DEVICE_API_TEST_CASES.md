# FloraGuide Real-Device API Test Cases

## Scope and execution status

This document contains the agreed DT-01 to DT-14 checks for the PlantNet integration, the supporting Firebase photo-transfer pipeline, and read-only ALA lookups. These are local tracking IDs, not official course rubric IDs.

The document is a test plan and execution register, not a completed test report. All device cases initially have status **NOT RUN**. Device details, the tested commit, actual outcomes, and evidence must be filled in after execution. Automated-test results are recorded separately and do not establish a live-device pass.

Camera, location, account access, and the results screen are used only to exercise this API flow. This document does not add camera-quality tests, GPS/sensor calibration, map tests, registration-UI tests, database migration/synchronisation tests, cross-account security audits, identification-accuracy studies, or service load tests.

## Shared setup and recording rules

- Install the intended build on a physical Android phone. Record its model, Android version, build variant, and the full tested Git commit. Disclose uncommitted changes; a commit hash alone does not identify a modified build.
- Use an authorised test account, locally configured credentials, and the project's existing live-service configuration. Do not enable guided-demo mode for live cases or change shared cloud rules to create errors.
- Use existing diagnostics to identify request stages. For interruption tests, confirm that the target stage was still in progress. A controlled network interruption or delay with real service responses is still a live test; substituted responses are **Simulated** and must be labelled accordingly.
- Use non-identifying test scenes. Redact API keys, credential-bearing URLs, tokens, account identifiers, private object references, and precise coordinates from all committed evidence. Do not commit real users' photos, configuration secrets, or complete build/log directories.
- Restore the normal network/location settings after the relevant case. Reuse valid evidence across IDs to avoid unnecessary API calls.

If a required action or diagnostic is unavailable, record **BLOCKED** with the reason. Do not introduce a new feature solely to make this plan executable. No real 429, 5xx, or quota exhaustion needs to be induced for these fourteen cases; controlled HTTP-error tests remain separate.

**Status meanings:** `NOT RUN` = no attempt yet; `PASS` = all required checks met with evidence; `FAIL` = an observed requirement was not met; `BLOCKED` = the required setup, stage, or observation could not be obtained. A natural API outcome such as candidates for a non-plant photo must be recorded honestly and evaluated against that case's stated expectations.

## Execution register

Keep this header unchanged. The Network and Live / Simulated entries below describe the **planned setup**; replace them with the actual conditions after execution. Replace `TBD` with the tested device and commit. Keep observations in Actual Result, not expected outcomes. Replace pending evidence descriptions with links to reviewed, redacted evidence that actually exists.

| Test ID | Device / Android | Commit | Network | Live / Simulated | Actual Result | Status | Evidence |
|---|---|---|---|---|---|---|---|
| [DT-01](#dt-01-live-photo-end-to-end-demo) | TBD | TBD | Stable Wi-Fi or mobile data | Live (planned) | Not executed. | NOT RUN | Pending: demo recording and stage trace |
| [DT-02](#dt-02-real-firebase-photo-upload-and-download) | TBD | TBD | Stable Wi-Fi or mobile data | Live (planned) | Not executed. | NOT RUN | Pending: Storage evidence and download trace |
| [DT-03](#dt-03-live-plantnet-candidates-and-data-mapping) | TBD | TBD | Stable Wi-Fi or mobile data | Live (planned) | Not executed. | NOT RUN | Pending: candidate fields and results screenshot |
| [DT-04](#dt-04-live-ala-lookup-for-multiple-candidates) | TBD | TBD | Stable Wi-Fi or mobile data | Live (planned) | Not executed. | NOT RUN | Pending: candidate lookup trace and ALA results |
| [DT-05](#dt-05-identification-without-a-usable-location) | TBD | TBD | Online; no usable capture location | Live (planned) | Not executed. | NOT RUN | Pending: no-location context and image-only result |
| [DT-06](#dt-06-non-plant-photo-input) | TBD | TBD | Stable Wi-Fi or mobile data | Live (planned) | Not executed. | NOT RUN | Pending: input description and actual response |
| [DT-07](#dt-07-no-network-before-identification-starts) | TBD | TBD | Wi-Fi and mobile data off before submission | Live (planned) | Not executed. | NOT RUN | Pending: offline state and failure timing |
| [DT-08](#dt-08-network-loss-during-firebase-upload-and-download) | TBD | TBD | Online -> offline during each transfer stage | Live (planned) | Not executed. | NOT RUN | Pending: upload and download interruption traces |
| [DT-09](#dt-09-network-loss-during-a-plantnet-request) | TBD | TBD | Online -> offline after PlantNet request starts | Live (planned) | Not executed. | NOT RUN | Pending: PlantNet interruption and error trace |
| [DT-10](#dt-10-network-loss-during-ala-lookup) | TBD | TBD | Online -> offline after ALA lookup starts | Live (planned) | Not executed. | NOT RUN | Pending: ALA failure states and retained candidates |
| [DT-11](#dt-11-retry-after-network-restoration) | TBD | TBD | Offline failure -> network restored | Live (planned) | Not executed. | NOT RUN | Pending: original failure and retry-success trace |
| [DT-12](#dt-12-ala-only-retry) | TBD | TBD | ALA failure -> network restored | Live (planned) | Not executed. | NOT RUN | Pending: ALA-only retry trace and updated context |
| [DT-13](#dt-13-measured-live-api-latency) | TBD | TBD | Record actual Wi-Fi or mobile-data conditions | Live (planned) | Not executed. | NOT RUN | Pending: real request timing and outcome |
| [DT-14](#dt-14-live-model-details-and-quota-evidence) | TBD | TBD | Stable Wi-Fi or mobile data | Live (planned) | Not executed. | NOT RUN | Pending: actual metadata fields and quota source |

One live session may support several rows, but each row has its own acceptance checks. DT-08 requires both its upload and download subchecks; record each subcheck result in the evidence. Never turn all rows into PASS solely because DT-01 succeeds.

## Test-case specifications

The sections below define what to do and what to check. They are not observed results. If a defect is found, record the actual behaviour and issue reference rather than changing the expected result to match the defect.

## DT-01: Live-photo end-to-end demo

**Preconditions:** Use the tested build on a physical Android phone, an authorised test account, valid local service configuration, and live mode. Allow camera access and obtain a usable capture location.

**Steps**

1. Photograph a real plant using the app. Use a non-identifying test scene.
2. Submit the photo and observe the existing upload, download, identification, and ALA lookup stages.
3. Open the results and correlate the observation/photo with the safe diagnostic trace.

**Expected result**

- The flow completes in this order: capture -> Firebase upload -> cloud-photo download -> PlantNet identification -> ALA lookup -> displayed results.
- The displayed results belong to this photo and come from the live services, not guided-demo data.
- Processing finishes without a crash or a permanently active loading state.

**Record / evidence:** Save a short recording and a redacted stage trace. Record the actual outcome of each stage; reuse this run for DT-02, DT-03, DT-04, DT-13, and DT-14 where their conditions are met.

## DT-02: Real Firebase photo upload and download

**Preconditions:** Use the authorised test account and the photo from DT-01. Have authorised access to inspect the relevant Firebase Storage object and existing app diagnostics.

**Steps**

1. Complete the real upload and inspect the resulting object in Firebase Storage.
2. Confirm that it corresponds to the test photo and the current account's expected storage path. Redact account identifiers in evidence.
3. Observe the app downloading this cloud object and passing the downloaded photo into the identification stage.

**Expected result**

- The expected cloud object exists and is readable through the current account's app session.
- Identification uses the downloaded cloud photo; the existence of an uploaded object alone is not sufficient evidence.
- There is no silent bypass that substitutes the original local photo when cloud download has failed.

**Record / evidence:** Save a redacted Storage screenshot and the upload/download-to-identification trace. Claim byte-for-byte integrity only when actual integrity-check evidence is available. This case does not certify all deployed security rules or cross-account isolation.

## DT-03: Live PlantNet candidates and data mapping

**Preconditions:** Obtain a successful live PlantNet response. Use safe diagnostics or local debugging to inspect relevant response and parsed fields without exposing credentials.

**Steps**

1. Record the configured candidate limit K and the candidates returned for the test photo.
2. Compare the retained Top-K scientific names, available common names, and raw scores with the live response.
3. Compare the parsed results with the displayed candidates, distinguishing PlantNet scores from any later ALA-based ranking.

**Expected result**

- The retained candidates match the configured Top-K selection. Fewer than K available candidates are not padded with invented entries.
- Scientific names and available common names are mapped correctly; a missing common name is not invented.
- Raw PlantNet scores are preserved and are not confused with a normalised or fused ranking score. Later ranking changes do not misattribute a score to another species.

**Record / evidence:** Save the relevant redacted response fields, their parsed equivalents, and a results screenshot. Record the actual K and candidate count. This is a mapping check, not a biological accuracy or calibrated-confidence evaluation.

## DT-04: Live ALA lookup for multiple candidates

**Preconditions:** Use a live PlantNet result with at least two candidates and a usable location attached to the original photo. Keep the existing ALA radius and concurrency configuration.

**Steps**

1. Allow the app to query ALA for the recognised candidates.
2. Inspect the candidate-to-query mapping, configured search radius, capture-context reference, and each lookup outcome.
3. Compare the successful live results with the ALA information shown or retained by the app. Include overlapping request timing evidence when observable.

**Expected result**

- Multiple real candidate lookups are attempted using the existing bounded-concurrency implementation, not demo counts.
- Each result is associated with the correct candidate and the photo's capture context; results are not mixed between candidates.
- Successful counts and lookup states are retained accurately. A failure is not represented as a successful count of zero.

**Record / evidence:** Record the candidate count, configured radius, per-candidate outcomes, and available timing evidence. Redact coordinates. Do not claim live overlap or a measured concurrency maximum without observing it; automated concurrency-limit evidence remains separate. If the run is partial, record that outcome and repeat for the fully successful live path.

## DT-05: Identification without a usable location

**Preconditions:** Keep network access and the test account available. Ensure a new photo will have no usable capture location; disabling location alone is insufficient if an earlier location is still reused.

**Steps**

1. Disable device location or deny the app's location permission.
2. Start a new observation, take a photo, and verify that its capture context has no usable location.
3. Submit identification and inspect the resulting PlantNet and ALA states.

**Expected result**

- Firebase transfer and PlantNet identification can still complete.
- ALA is skipped, and image-only results remain available.
- The app does not substitute demo coordinates or fabricated ALA counts for missing location data.

**Record / evidence:** Save the no-location state and resulting image-only outcome. If a cached location is actually used, the intended condition was not exercised; correct the setup or mark the case BLOCKED rather than PASS.

## DT-06: Non-plant photo input

**Preconditions:** Use live mode and a non-sensitive non-plant subject, such as a book or an empty desk.

**Steps**

1. Photograph the non-plant subject and submit it to the real identification flow.
2. Record the actual service outcome: candidates, no results, or an input-related error.
3. Check the corresponding app state and the existing route to take or submit another photo.

**Expected result**

- The app does not crash or silently replace the outcome with demo results.
- A no-result or input-error response produces an appropriate message and does not prevent another attempt.
- If the real API returns candidates, the app handles that response honestly. This result does not prove that all non-plant inputs are rejected or that the candidates are correct.

**Record / evidence:** Save the actual response category and app behaviour. Describe the test input without including personal material. State explicitly whether non-plant rejection was observed; do not infer universal rejection from this case.

## DT-07: No network before identification starts

**Preconditions:** Have an existing signed-in test session. Use a new photo so that a previously completed result cannot be mistaken for a new live request.

**Steps**

1. Turn off both Wi-Fi and mobile data and verify that the phone has no usable network connection.
2. Take a new photo and submit identification.
3. Observe the failure state and measure the wait until the configured failure or timeout handling completes.

**Expected result**

- The app presents a clear network or upload failure instead of reporting live identification success.
- Loading ends according to the configured failure/timeout handling; the app does not retry or wait indefinitely.
- No demo result is silently presented as a successful live response.

**Record / evidence:** Record the observed error, failed stage, applicable timeout/retry budget, and elapsed wait. Do not invent a time threshold. Use this failed attempt as a possible prerequisite for DT-11.

## DT-08: Network loss during Firebase upload and download

**Preconditions:** Use live services and observable Firebase transfer stages. This case has two required subchecks: upload interruption and cloud-download interruption.

**Steps**

1. Upload subcheck: start a new photo upload, confirm that the upload is in progress, and disable both network connections.
2. Record the actual upload failure. Restore the network before preparing the next subcheck.
3. Download subcheck: complete an upload, confirm that its cloud download is in progress, and interrupt the network again.
4. Record the actual download failure and verify that classification does not proceed using the original local photo instead.

**Expected result**

- Each interruption is attributed to the actual failed transfer stage in the app or diagnostics.
- A failed upload or download is not treated as a completed identification.
- Download failure does not silently bypass the cloud-photo requirement. Error handling finishes without an indefinitely active loading state.

**Record / evidence:** Record both subcheck outcomes separately under this ID. PASS requires both to be exercised successfully. If the transfer finished before the interruption, repeat the setup; that run did not test the intended condition. Document any controlled network delay used.

## DT-09: Network loss during a PlantNet request

**Preconditions:** Complete the real Firebase upload and cloud download. Be able to identify the start of the live PlantNet request.

**Steps**

1. Begin live identification and confirm that the PlantNet request is in progress.
2. Disable Wi-Fi and mobile data before the response completes.
3. Observe the identification failure and the existing recovery route.

**Expected result**

- The app reports the actual identification-request failure rather than success.
- There is no automatic substitution of guided-demo results or invented candidates.
- The failed photo remains associated with the recovery attempt, and waiting ends under the configured error handling.

**Record / evidence:** Save the stage trace, observed error category, and app state. An interrupted connection is not automatically a timeout; use the actual error classification. A response completed before the interruption does not exercise this case.

## DT-10: Network loss during ALA lookup

**Preconditions:** Obtain live PlantNet candidates and start ALA lookup with a usable capture location.

**Steps**

1. Wait until PlantNet has returned candidates and confirm that ALA requests are in progress.
2. Disable Wi-Fi and mobile data before all lookups complete.
3. Inspect the remaining PlantNet results, each ALA outcome, and the resulting ranking mode.

**Expected result**

- PlantNet candidates remain available when ALA fails.
- ALA failures are represented as unavailable or unknown, not successful zero counts or demo counts.
- The app distinguishes partial from fully unavailable context and uses the existing image-only fallback when ALA context is incomplete or unavailable.

**Record / evidence:** Record which candidates completed before disconnection and which failed. Save the displayed/retained context status and fallback result. Do not label a partial run as a complete ALA outage. Reuse this state for DT-12.

## DT-11: Retry after network restoration

**Preconditions:** Have a recorded failed attempt from DT-07, DT-08, or DT-09 and access to the existing retry action. Record exactly which stage failed.

**Steps**

1. Restore a working Wi-Fi or mobile-data connection.
2. Use the existing retry action for the failed photo without taking a replacement photo.
3. Follow the recovery trace to the completed result and compare the photo/observation reference with the original attempt.

**Expected result**

- The flow can recover and complete using the original photo, without showing another photo's results.
- Previously completed cloud work is reused according to the existing retry design. A retry may download the stored photo again; a repeated download is not automatically a repeated upload.
- The app leaves the failure/loading state and displays the new actual outcome.

**Record / evidence:** Record the prerequisite test ID, failed stage, retry count, reused cloud reference where observable, and final outcome. Claim recovery only for the stage actually exercised. The same procedure may be reused for other recorded stage failures.

## DT-12: ALA-only retry

**Preconditions:** PlantNet has succeeded but ALA has failed, for example in DT-10. Use the existing ALA-only retry entry point in the tested build.

**Steps**

1. Restore network access and retain the original photo, PlantNet candidates, and capture context.
2. Trigger ALA-only retry and observe which service stages are called.
3. Compare the retry's capture-context reference with the original attempt and inspect the updated ALA result.

**Expected result**

- Only ALA is requested again: there is no new Firebase upload or download and no new PlantNet identification request.
- The retry uses the original photo's capture location and time, not a newly acquired location or a new observation.
- A successful retry updates the ALA status and relevant results without discarding the existing PlantNet candidates.

**Record / evidence:** Save a complete stage trace covering the retry and the updated result. If the tested build has no accessible ALA-only retry entry point, record BLOCKED and the limitation rather than inventing a new feature or claiming PASS.

## DT-13: Measured live API latency

**Preconditions:** Use at least one real PlantNet request, preferably the DT-01 run, and existing timing diagnostics or observable request-boundary timestamps.

**Steps**

1. Record the network type, request outcome, and precise start/end boundaries used for the PlantNet measurement.
2. Record the measured duration and units. Separate individual request attempts from total elapsed time across retries.
3. If also measuring capture-to-results time, Firebase transfer time, or ALA time, label those measurements separately.

**Expected result**

- Evidence contains an actual client-observed PlantNet request duration with clearly stated measurement boundaries and outcome.
- Firebase transfer or total end-to-end time is not mislabelled as PlantNet-only latency. Client-observed request time is not described as provider server-processing time.
- No fabricated measurement or new performance threshold is introduced. Failed attempts are distinguished from successful ones.

**Record / evidence:** Record the duration, units, timing source, boundaries, network conditions, outcome/HTTP status when available, and attempt count. ALA timing is optional supporting evidence. If only a screen recording of total duration exists, do not claim API-only latency; mark that measurement BLOCKED until observable.

## DT-14: Live model details and quota evidence

**Preconditions:** Use a live PlantNet request, preferably shared with DT-13. Inspect relevant response and parsed metadata locally; an authorised service dashboard may provide supplementary quota evidence.

**Steps**

1. Record the actual field names and values returned for model details and quota, where present.
2. Compare those values with the app's parsed/stored metadata. Distinguish API-returned model details from a model/project name inferred only from local configuration.
3. If quota evidence comes from the service dashboard, record its source and observation time separately from API response fields.

**Expected result**

- Model and quota metadata that the real response provides are correctly preserved by the existing data mapping.
- Absent or invalid optional metadata remains unavailable/unknown. An unknown quota is not recorded as zero, and a real zero is not treated as missing.
- Dashboard information is labelled as dashboard evidence, not as a response field or an exact per-request quota change. No quota is deliberately exhausted.

**Record / evidence:** Save a redacted metadata comparison and, if used, a redacted dashboard excerpt with its observation time. Mark absent fields as 'Not provided' and record their unknown handling. A field genuinely absent from the service is different from a field returned but dropped by the parser. If no quota value can be observed from either source, state that evidence limitation explicitly rather than supplying a number.

## Evidence and Git placement

Suggested location for this file: `docs/testing/DEVICE_API_TEST_CASES.md`.

Suggested evidence location: `docs/evidence/device-api-tests/<run-id>/`. Create that directory only when there is actual evidence to save. From this document, evidence links can use `../evidence/device-api-tests/<run-id>/<filename>` after those files exist. Reuse the same evidence link for related cases when appropriate.

For each run, include the following in a short evidence note:

```text
Run ID:
Executed at / timezone:
Device model / Android version:
Build variant / app version:
Full Git commit / working-tree changes:
Network conditions:
Test IDs covered:
Live services / any simulated components:
Observed stage sequence and errors:
Timing boundaries / measured durations, where relevant:
Actual model or quota fields / evidence source, where relevant:
Case outcomes / limitations / issue references:
Evidence filenames:
```

Only commit this register and reviewed, redacted evidence. Keep raw diagnostic material private. Before committing, check that every PASS has supporting observations, simulated behaviour is not presented as live evidence, and all unexecuted cases remain NOT RUN or BLOCKED with an explanation.
