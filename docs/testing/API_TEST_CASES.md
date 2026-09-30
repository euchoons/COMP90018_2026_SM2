# FloraGuide API test cases

## Scope and baseline

Repository: `euchoons/COMP90018_2026_SM2`  
Inspected integration branch: `main`  
Source snapshot: `ad79b69f723868d7079182426a875766c98270c5`  
Prepared: 2026-09-24  
**Execution status at delivery: NOT RUN in the complete Android Gradle project.**

These are 46 new local JUnit test methods in four new files. Each table row maps to
one test method; loops covering several inputs are not counted as separate tests.
They execute the existing app implementation with controlled dependencies. They do
not replicate production algorithms in a separate Python program.

Requirement labels below are local traceability labels for this test package, not
invented official course rubric IDs. The mapping uses the repository's current
implementation and documented scope; it does not certify the entire assignment.

| Local requirement | Existing source / project basis | Covered by |
|---|---|---|
| API-P: Validated PlantNet transport and honest failures | `data/plantnet/PlantNetClient.kt`, `PlantNetImageClassifier.kt`; app overview's image-derived candidates and network fallback | PN-01..10; retained PlantNet tests |
| API-F: Account-scoped photo transfer and verified stored bytes | `data/firebase/FirebasePhotoStorage.kt`, `domain/repository/PhotoStore.kt` | FB-01..20 |
| API-A: Read-only, bounded ALA evidence; unknown is not zero | `data/ala/AlaOccurrenceClient.kt`, `ReliableAlaSpeciesContextRepository.kt` | ALA-C01..08, ALA-R01..08; retained ALA tests |
| API-I: Classify the downloaded cloud photo and preserve stage failures | `domain/usecase/IdentifyStoredPhotoUseCase.kt` | Eight retained pipeline tests; no duplicate new implementation |
| TRACE: Reviewable, truthful test evidence | `CONTRIBUTING.md`, sections Tests and evidence / AI-assisted work | Runner summaries, this case register, PR draft |

## Explicit exclusions

No camera, sensor calibration, GPS hardware, Compose UI, map, registration UI,
Firestore/Room migration, sync worker, classifier training, new features or ALA
writes are added. No stress/penetration test is performed. Firebase client guards
are tested, but deployed Storage Rules, Auth/App Check configuration and real cloud
access are not validated. These fixtures do not establish identification accuracy,
calibrated confidence, biological correctness, real latency or end-to-end device
acceptance. Initial Gradle/Robolectric dependency downloads may require internet;
there are no real Firebase, PlantNet or ALA requests in these tests.

## New test cases

### AlaOccurrenceClientValidationTest (8 methods)

File: `app/src/test/java/au/edu/unimelb/floraguide/data/ala/AlaOccurrenceClientValidationTest.kt`

| ID / method | Given | When | Expected result |
|---|---|---|---|
| **ALA-C01**<br>`invalidCoordinatesFailBeforeTransport` | Invalid/NaN/infinite coordinates. | Call ALA client. | Reject before creating any HTTP connection. |
| **ALA-C02**<br>`invalidRadiusFailsBeforeTransport` | Radius 0, -1 or 101 km. | Call ALA client. | Reject outside the existing 1..100 km input contract. |
| **ALA-C03**<br>`invalidScientificNameFailsBeforeTransport` | Empty, control-character or overlong scientific name. | Call ALA client. | Reject before HTTP. |
| **ALA-C04**<br>`countsRejectNegativeAndOverflowButAcceptLargestInteger` | Int.MAX_VALUE, negative, overflow, null and boolean counts. | Parse totalRecords. | Accept maximum supported integer; reject unsupported values. |
| **ALA-C05**<br>`retryAfterSupportsSecondsAndHttpDateWithFixedClock` | Retry-After delay-seconds and an RFC 1123 future date. | Parse using fixed clock. | Exact 7,000 ms and 30,000 ms waits. |
| **ALA-C06**<br>`invalidRetryAfterIsUnknownAndPastDateHasNoWait` | Malformed/negative Retry-After or an HTTP date in the past. | Parse using fixed clock. | Unknown is null; past date has zero wait. |
| **ALA-C07**<br>`offlineTransportDoesNotBecomeZeroRecords` | Connection factory throws synthetic UnknownHostException. | Request candidate context. | OFFLINE failure, no count result, no implicit retry. |
| **ALA-C08**<br>`exactNameMatchingToleratesCaseAndInputPadding` | Exact-match payload varies name/rank case; request is padded. | Resolve taxon ID. | Accept exact species match after supported normalisation; fuzzy match remains unresolved. |

### AlaRepositoryBoundaryTest (8 methods)

File: `app/src/test/java/au/edu/unimelb/floraguide/data/ala/AlaRepositoryBoundaryTest.kt`

| ID / method | Given | When | Expected result |
|---|---|---|---|
| **ALA-R01**<br>`emptyCandidatesSkipTheService` | No candidates in live mode. | Ask repository for counts. | NOT_REQUESTED, empty map, zero service calls. |
| **ALA-R02**<br>`duplicateCandidateIdIsQueriedOnlyOnce` | Duplicate candidate ID. | Ask repository for counts. | One lookup and one result entry. |
| **ALA-R03**<br>`transientFailureThenSuccessRetainsAttemptHistory` | 503 followed by successful count 12. | Perform lookup. | Two attempts, both statuses, live success and no final failure. |
| **ALA-R04**<br>`permanentHttpFailuresAreNotRetriedOrConvertedToDemoCounts` | Permanent 400/401/403 error. | Perform lookup. | One attempt, unavailable context, no fabricated zero or demo count. |
| **ALA-R05**<br>`negativeAdapterCountIsNotAcceptedAsEvidence` | Injected adapter returns a negative count. | Perform lookup. | INVALID_INPUT, unavailable context, no count evidence. |
| **ALA-R06**<br>`serverRetryTimeIsExactAndNeverRetriedInsideInsufficientBudget` | 429 asks for 60 seconds, beyond the candidate budget. | Perform lookup with a fixed clock. | No early retry; exact retryNotBefore is exposed. |
| **ALA-R07**<br>`candidateConcurrencyNeverExceedsConfiguredLimit` | Five synthetic candidates and configured concurrency 2. | Use virtual-time delays. | Peak active calls 2; all five complete; no load test of ALA. |
| **ALA-R08**<br>`candidateTimeoutCancelsWorkAndReturnsUnknown` | Source exceeds a 100 ms test timeout. | Advance virtual time through lookup. | Cancel source, classify TIMEOUT, and leave count unknown. |

### FirebasePhotoStorageLocalTest (20 methods)

File: `app/src/test/java/au/edu/unimelb/floraguide/data/firebase/FirebasePhotoStorageLocalTest.kt`

| ID / method | Given | When | Expected result |
|---|---|---|---|
| **FB-01**<br>`signedOutUploadFailsBeforeAnyStorageRequest` | Mock auth has no current user. | Upload a local signature fixture. | IOException with sign-in hint; no Storage call. |
| **FB-02**<br>`expectedAccountMismatchFailsBeforeTransfer` | Pinned expected UID differs from mock current UID. | Start upload. | IllegalStateException before transfer. |
| **FB-03**<br>`missingLocalFileIsRejectedBeforeUpload` | Local photo path does not exist. | Upload. | Reject before putFile. |
| **FB-04**<br>`emptyLocalFileIsRejectedBeforeUpload` | Zero-byte local file. | Upload. | Reject before putFile. |
| **FB-05**<br>`photoAboveTwentyMiBLimitIsRejectedBeforeUpload` | Local file length is 20 MiB + 1 byte. | Upload. | Reject before putFile; no large network transfer. |
| **FB-06**<br>`unsupportedHeaderIsRejectedEvenWhenExtensionIsJpeg` | A .jpg file contains a plain-text header. | Upload. | Reject unsupported signature before putFile. |
| **FB-07**<br>`jpegUploadUsesUidDigestAndPrivateStorageReference` | Signed-in mock user, JPEG signature, completed mock upload. | Upload through the actual adapter. | UID/SHA-256 object name, private gs reference, correct size, hash, content type, metadata and input URI. |
| **FB-08**<br>`pngHeaderChoosesPngMetadataAndObjectSuffix` | PNG signature in a misleading .jpg filename. | Upload. | Choose image/png and a .png object suffix from the signature. |
| **FB-09**<br>`sameContentWithDifferentNamesUsesSameObjectPath` | Two local filenames contain identical bytes. | Upload both. | Same cloud object path and digest; two SDK calls. This is naming reuse, not a real cloud deduplication test. |
| **FB-10**<br>`accountChangeAfterUploadIsNotReportedAsSuccess` | Mock account changes between pre/post-upload checks. | Complete upload. | Do not report success; throw account-change error. |
| **FB-11**<br>`downloadFromDifferentBucketIsRejectedBeforeMetadata` | Mock reference points to a different bucket. | Download. | Reject before reading metadata or downloading a file. |
| **FB-12**<br>`similarButDifferentUidPathIsRejectedBeforeMetadata` | Object path uses a similar but different UID prefix. | Download. | Reject at the client ownership guard before metadata. |
| **FB-13**<br>`invalidStoredSizeIsRejectedBeforeMetadata` | StoredPhoto size is zero or over 20 MiB. | Download. | Reject before metadata. |
| **FB-14**<br>`changedRemoteSizeIsRejectedBeforeFileDownload` | Remote metadata size differs from StoredPhoto. | Download. | Reject before getFile; no temporary cache remains. |
| **FB-15**<br>`successfulDownloadReturnsVerifiedTemporaryBytes` | Mock metadata, bytes and SHA-256 agree. | Download via actual adapter. | Return correct bytes in the expected temporary cache; caller removes successful file. |
| **FB-16**<br>`checksumMismatchRemovesTemporaryDownload` | Mock download changes a byte but keeps the length. | Download. | Integrity-check exception and temporary-file cleanup. |
| **FB-17**<br>`truncatedDownloadRemovesTemporaryFile` | Mock download writes fewer bytes than expected. | Download. | Integrity-check exception and temporary-file cleanup. |
| **FB-18**<br>`deniedUploadMapsSdkErrorToActionableIOException` | Mock SDK upload fails with ERROR_NOT_AUTHORIZED. | Upload. | Map to IOException containing Storage code and actionable rules hint. |
| **FB-19**<br>`failedDownloadMapsErrorAndRemovesTemporaryFile` | Mock SDK download fails with ERROR_OBJECT_NOT_FOUND. | Download. | Map to IOException and delete temporary file. |
| **FB-20**<br>`cancelledSdkUploadPropagatesCancellationAndCallsCancel` | Mock upload Task is already marked cancelled. | Await through actual adapter. | Propagate CancellationException and call upload.cancel; not a timing test of live cancellation. |

### PlantNetClientRegressionTest (10 methods)

File: `app/src/test/java/au/edu/unimelb/floraguide/data/plantnet/PlantNetClientRegressionTest.kt`

| ID / method | Given | When | Expected result |
|---|---|---|---|
| **PN-01**<br>`scoreBoundariesArePreservedWithoutNormalisation` | Scores 0, 1 and 0.8 in a synthetic response. | Parse three candidates. | Preserve values and sum 1.8; do not normalise raw scores. |
| **PN-02**<br>`negativeAndOverOneScoresAreRejected` | A numeric score is -0.01 or 1.01. | Parse the response. | Throw PlantNetResponseException. |
| **PN-03**<br>`commonNameSkipsBlankEntriesAndTrimsNames` | Padded scientific name and blank/padded common names. | Parse one candidate. | Trim names; choose the first nonblank common name. |
| **PN-04**<br>`pngExtensionProducesPngMultipartWithoutChangingBytes` | A .PNG filename and synthetic PNG bytes. | Send through an in-memory HTTP connection. | POST PNG multipart with unchanged bytes and closing boundary; disconnect. |
| **PN-05**<br>`remainingHttpErrorsPreserveStatusAndActionableMessages` | Simulated HTTP 400, 401, 403, 413, 415 or 500. | Call identify. | Preserve status and useful hint; do not expose body; disconnect. |
| **PN-06**<br>`networkFailureDoesNotLeakKeyOrBecomeDemoSuccess` | Simulated IOException, including a fake key in its message. | Call real client and real live classifier. | Sanitise error/logs, disconnect, propagate failure; never invoke guided demo. |
| **PN-07**<br>`invalidSuccessJsonRemainsAResponseFailureAndDisconnects` | HTTP 200 with invalid JSON. | Call identify. | Throw response error, not success; disconnect. |
| **PN-08**<br>`successfulUploadUsesExactStreamingLengthAndDisconnects` | HTTP 200 with one valid candidate. | Upload synthetic image bytes. | Fixed streaming length matches actual multipart bytes; retain timeouts and disconnect. |
| **PN-09**<br>`absentOrNonNumericQuotaIsUnknownNotZero` | Absent, malformed or nonnumeric remaining quota; also numeric zero. | Parse optional quota. | Unknown stays null; real zero stays zero. |
| **PN-10**<br>`badCandidateAfterValidCandidateCannotReturnPartialSuccess` | Valid first candidate followed by malformed second candidate. | Parse both. | Fail closed; do not silently return a shortened list. |

## Retained tests: existing work, not new contribution

The scoped runner also executes these existing classes. Do not replace, delete or
claim authorship of them as part of this package. Counts are from the inspected
snapshot and must match the actual XML reports before a run is accepted.

| Existing class | Methods | Relevant coverage already present |
|---|---:|---|
| `PlantNetClientTest` | 6 | Recorded response fixture, candidate cap, malformed response, multipart/telemetry, 429/404, missing key/photo |
| `PlantNetImageClassifierTest` | 4 | Name/neutral-ecology mapping, an existing ALA ranking example, explicit offline demo, duplicate candidates |
| `AlaOccurrenceClientTest` | 9 | Exact taxon resolution before counts, zero versus unresolved, count-only requests, response guards, cancellation |
| `ReliableAlaSpeciesContextRepositoryTest` | 7 | Live/partial/unavailable/demo results, retry policy, cancellation, unknown versus zero |
| `IdentifyStoredPhotoUseCaseTest` | 8 | Downloaded-file-only classification, stage failures, retry reuse, empty results, cancellation and cache cleanup |

New suite: **46 methods / 4 classes**. Scoped suite: **80 methods / 9 classes**
(46 new + 34 existing). These are planned counts, not execution results.

## Execution and acceptance

Run `tools/test-api.ps1 -Suite New` for new tests only, or `-Suite Scoped` for the
recommended focused regression run. The default is Scoped. The script performs
read-only production-source checks before Gradle. It records PASS only after a
zero Gradle exit code and complete, matching, unskipped JUnit XML results. Preflight
is not a test pass. Compile failures are BLOCKED, not evidence of API test success.
A nonzero overall result must be investigated; do not disable tests to make CI green.

Generated summaries belong under `docs/evidence/api-tests/`. Keep raw reports in
`app/build/`; do not commit all build output. Before accepting the PR, also run the
repository's unchanged project-wide gate and obtain teammate review:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

That gate is a repository regression requirement, not an expansion of this test
change's feature scope. A focused pass is not a claim that the full project passed.

## Authoring verification

Source contracts and existing tests were inspected through the GitHub connector.
Package paths, method/ID counts and the manifest were checked programmatically.
All four test files also passed Kotlin 1.9 compiler-PSI syntax parsing; this is not
dependency resolution, type checking, compilation or JUnit execution.
The full Android project, these JUnit tests and the Windows runner have not been
executed in the authoring environment. No real cloud or device result is claimed.
The generated run summaries must be produced on the contributor's machine before
reporting local PASS in GitHub.
