# Issue #14: Cloud Photo Lifecycle and Storage Validation

## Baseline and scope

Repository: `euchoons/COMP90018_2026_SM2`  
Source baseline: `eda740c51447cceb8cda51a0be5ba32cb7c8fb14` (`main`, 1 October 2026).

The baseline already connects `FloraGuideViewModel` to `IdentifyStoredPhotoUseCase`, preserves `StoredPhoto` on retry, saves cloud metadata, and uses one Firebase BOM. This change retains those integrations. It does not replace the ALA client, ranking rules, flowering-season evidence, authentication model, Room schema, Storage Rules, API keys, or UI layouts.

## Changes

| File | Purpose |
|---|---|
| `data/firebase/FirebasePhotoStorage.kt` | Read complete image signatures; validate remote checksum, MIME, size, path and account; support journalled, independently named scan uploads. Default observation-sync uploads preserve their old naming convention. |
| `data/firebase/PhotoContentValidation.kt` | Android-independent signature reader, including short reads, EOF and zero-byte-read progress. |
| `domain/usecase/IdentifyStoredPhotoUseCase.kt` | Keep the upload/download/classify order; release a newly uploaded object if cancellation prevents delivery to the ViewModel. Keep the four-argument invocation contract. |
| `domain/usecase/PendingPhotoRegistry.kt` | Persist unconfirmed upload ownership and distinguish active scans, saves in progress and abandoned scans. Never automatically delete legacy shared digest paths. |
| `domain/usecase/PendingPhotoCleanupUseCase.kt` | Check current UID and saved local references before deletion; retain failed work; drop references Storage rejects outright (another bucket or owner); propagate cancellation. |
| `data/firebase/PendingPhotoUploads.kt` | SharedPreferences-backed journal, one registry per app process, and the one cleanup factory. Registration is persisted before an upload is started; later state changes stay in memory. |
| `data/firebase/PendingPhotoCleanupWorker.kt` | Network-constrained WorkManager cleanup with at most 5 attempts per run. Failed cleanup never overwrites the analysis UI. |
| `data/local/ObservationEntity.kt` | `isPhotoReferenced`: one `EXISTS` query for the cleanup's reference check. |
| `di/AppContainer.kt` | Wire the registry, scan uploader and cleanup use case into the existing dependency container. |
| `ui/FloraGuideViewModel.kt` | Invalidate old request callbacks; keep retry ownership; queue abandoned photos; protect saving; release committed ownership; drain queued cleanup before account deletion and before a cloud guest signs out. |

All paths in the table are relative to `app/src/main/java/au/edu/unimelb/floraguide/`.

## Photo lifecycle

1. A new live scan uses an independently named object: `plant_photos/{uid}/scan-{uuid}-{sha256}.{jpg|png}`. This flat path is compatible with the existing owner-only Storage rule.
2. The journal is written before the SDK upload starts. A failed journal write prevents the upload. SDK tasks that have not reached a terminal state remain protected against cleanup. Later state changes stay in memory: after a restart every journalled record is eligible and is checked against the local database.
3. The use case downloads the Firebase object and passes the downloaded local cache path to Pl@ntNet. It does not fall back to sending the camera original.
4. Upload/download/classification errors are reported at their stage. A completed upload remains attached to the results session for retry. Repeated retry clicks do not start concurrent analyses.
5. Retry reuses `StoredPhoto`: it downloads and identifies again, without uploading again. A new scan, even with identical bytes, owns a different scan object.
6. Leaving results queues the uncommitted scan photo for cleanup. Cleanup checks the current account and local observation references before deleting. It never deletes the local camera original.
7. Saving enters a protected state before the local repository write. Duplicate save actions and navigation during that write are blocked. A successful save transfers ownership to the observation and clears the ViewModel's temporary reference.
8. If the process stops after the observation is saved but before the journal is cleared, recovery checks the local database and retains the referenced photo.
9. On restart, journal entries belonging to the previous app process can be cleaned up. On sign-out, entries remain queued for their original UID and resume when that account signs in again. A cloud guest cannot sign back in, so its queued photos are deleted before it signs out.
10. Explicit account deletion first deletes this device's pending scan photos, waiting briefly for a background cleanup or a cancelling upload. After 20 seconds, for example offline, it stops with a retryable message. Other account actions are blocked meanwhile.
11. A reference Storage rejects outright (another bucket or owner) is dropped from the journal instead of retried. The worker stops after 5 attempts; the next sign-in or app start schedules it again.

Cleanup is performed by WorkManager, not by an untracked ViewModel network coroutine. It is not guaranteed to run immediately when the user leaves the page. Cleanup errors are not put into the identification `message`/`analysisError` fields.

## Boundaries and deployment requirements

- This is a client-side recovery mechanism, not a server-enforced retention deadline. Uninstalling the app, clearing app data, permanently abandoning an account, or deleting the account outside this app can remove access to the journal or credentials. A server-side unreferenced-object retention process is needed to cover those cases.
- Existing orphaned, content-addressed objects are not automatically deleted. Their ownership is ambiguous and some can be shared by saved observations. Audit references before removing historical objects.
- Generic observation-sync uploads still use their existing content-addressed paths. This PR does not refactor historical cross-observation sharing/deletion semantics.
- Metadata/hash checks detect inconsistent records and bytes. Custom metadata is not an authenticity signature: an authorised writer can change an object and its metadata.
- The code does not enable Firebase Authentication providers or deploy rules. Verify the configured Android package/bucket, relevant sign-in provider, Storage Rules, and any App Check enforcement in the actual project. Do not make the bucket public to work around a permission error.

## Automated tests included

| Suite | Cases | Covers |
|---|---:|---|
| `Issue14CoreTest` / `Issue14CoreContract` | 28 | Registry, cleanup use case and signature reader, as parameterized JUnit cases. |
| `FirebasePhotoStorageLocalTest` | 32 | The real adapter with SDK mocks, including SDK `StorageException` mapping for upload and download. |
| `Issue14CloudWorkflowViewModelTest` | 16 | Real ViewModel and real identification use case with fake services, including guest sign-out and account deletion. |
| `Issue14UndeliveredUploadTest` | 1 | Cancellation between upload completion and ViewModel delivery. |

The existing `IdentifyStoredPhotoUseCaseTest`, `IdentifyStoredPhotoContract` and `FloraGuideViewModelTest` remain. The production `PhotoStore` interface remains unchanged.

```bash
./gradlew testDebugUnitTest --tests "*Issue14*" --tests "*FirebasePhotoStorageLocalTest*"
```

```bash
./tools/check.sh
```

The JVM tests use mocks and fakes and do not consume live Pl@ntNet requests.

## Device acceptance checks

| ID | Action | Required evidence | Status |
|---|---|---|---|
| I14-D01 | Authenticated capture and identification | Upload, download and classification stage order; result from the downloaded photo | Not run |
| I14-D02 | Fail download or classification, then retry | Same cloud reference reused; no second upload for the same retry session | Not run |
| I14-D03 | Leave an unsaved result | Unique scan object is eventually removed when cleanup can run | Not run |
| I14-D04 | Save an observation, then navigate/restart | Saved cloud photo remains accessible and metadata remains in the observation | Not run |
| I14-D05 | Start a second scan before the first completes | Old errors/stages cannot replace the new scan's state | Not run |
| I14-D06 | Abandon a result while offline, then reconnect | Deletion remains queued and is retried without replacing the analysis error | Not run |
| I14-D07 | Kill/reopen the process with an unconfirmed uploaded photo | Journal recovery removes only unreferenced objects for the same signed-in UID | Not run |
| I14-D08 | Switch accounts before cleanup | The new UID does not delete the old UID's photo; work resumes on the owning account | Not run |
| I14-D09 | Start saving and immediately press Save/Back again | One local observation write; the saved photo is not queued for deletion | Not run |
| I14-D10 | Explicit account deletion with pending scan photos | Cleanup completes first, or deletion stops with a retryable message | Not run |

## Source references

- Issue: https://github.com/euchoons/COMP90018_2026_SM2/issues/14
- Baseline: https://github.com/euchoons/COMP90018_2026_SM2/commit/eda740c51447cceb8cda51a0be5ba32cb7c8fb14
- Firebase object metadata: https://firebase.google.com/docs/storage/android/file-metadata
- Coroutine cancellation: https://kotlinlang.org/docs/cancellation-and-timeouts.html
