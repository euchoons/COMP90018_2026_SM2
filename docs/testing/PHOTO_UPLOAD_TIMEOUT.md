# Foreground Photo Upload: 30-Second Timeout

## Scope and baseline

Prepared against `euchoons/COMP90018_2026_SM2`, `main` commit
`6e1761c7ad0403242653d22971e1e8d24f0c440c`.

This change bounds a user-approved, foreground photo-upload attempt. It does not
change the result ranking UI, the removed identification debug card, Firebase
Storage rules, PlantNet transport, ALA ranking, saved-observation background sync,
photo consent, or the explicit guided demo. `ResultsScreen.kt` is not modified.
No additional Gradle dependencies are required by this patch.

## Behaviour contract

The timer starts when `IdentifyStoredPhotoUseCase` enters the upload stage after
photo consent. Local validation, waiting for a usable network, Firebase SDK
retries and the actual upload share one 30,000 ms budget. Invalid local photos,
signed-out users, invalid configuration and explicit server errors retain their
normal error handling; they are not deliberately delayed to 30 seconds.

While the default network is known to be unusable, the storage adapter waits
without creating a Storage task or cloud journal entry. Network recovery within
the budget permits a transfer using the remaining time. A reconnection at
20 seconds does not grant another 30 seconds.

A successful upload ends this timer. Firebase download, metadata validation,
PlantNet identification and ALA lookup are outside this upload-specific timeout.
A retry with a previously uploaded cloud reference skips upload and its timer.

At the deadline, a still-active upload is cancelled. The ViewModel stops loading,
preserves the approved local photo and capture snapshot, and shows one modal:

| Network snapshot at timeout | Title | Message |
|---|---|---|
| UNAVAILABLE | Upload failed | No internet connection was detected. The photo upload timed out after 30 seconds. Please reconnect and try again. |
| AVAILABLE or UNKNOWN | Upload timed out | The photo could not be uploaded within 30 seconds. Please check your connection and try again. |

`Close` dismisses only the modal; the photo and the existing inline error/retry
entry remain. `Retry` revalidates consent/account/busy state, dismisses the old
modal through the normal new-attempt state reset, and starts a fresh attempt.
There is no automatic post-timeout retransmission. The same timeout is not also
queued as a Snackbar. Returning, starting a new capture or changing accounts
invalidates the old dialog/request. Back closes the dialog instead of invoking
the result-page navigation handler behind it.

## Implementation notes

`withTimeoutOrNull` is restricted to `photoStore.uploadPhoto`. A timeout is
converted into `PhotoUploadTimeoutException` (not a `CancellationException`) so
that normal ViewModel failure handling runs. Navigation/account cancellation
continues to propagate without a failure modal.

The `StoredPhoto` is assigned inside the timeout scope. If cancellation wins as
the upload returns, a newly created but undelivered reference can still be passed
to the existing `onUndeliveredUpload` cleanup callback. Already owned cloud
references on retry are not abandoned by this path.

`FirebasePhotoStorage` retains `UploadTask.cancel()` and pending-photo journal
cleanup on coroutine cancellation. Cancellation checks run after network waiting,
before task creation and while calculating the checksum. The cancellation check
between journal registration and `putFile` is inside the existing terminal-state
error handler. Cleanup continues to wait for the SDK task's terminal callback.
Cancellation is not a promise of immediate remote deletion: a racing completed
object remains covered by the existing durable cleanup workflow.

`AndroidNetworkStatusProvider` uses the default network, `INTERNET` plus
`VALIDATED`, and `NetworkCallback`. It does not poll, ping, request network
settings changes, or use WorkManager for the foreground attempt. `onAvailable`
alone is not treated as validated internet. Listener cleanup runs after success,
timeout and navigation cancellation. Android `Network` values are compared by
value when handling network loss. Inability to inspect connectivity is UNKNOWN,
not an invented offline result. Platform validation cannot guarantee that
Firebase is reachable; the actual request outcome remains authoritative.

Only the foreground storage instance in `AppContainer` receives the network
provider. Existing `FirebasePhotoStorage` callers default to no gate, so the
saved-observation worker keeps its previous scheduling and retry behaviour.

The timer lives in the use case/ViewModel job, not in a composable. Compose
recomposition does not restart it. This is a foreground UI feature, not a
background notification guarantee. Main-thread scheduling can cause a small
delay in displaying the modal. Process death is not covered by a persistent
timer; this patch does not schedule a dialog while the app is closed.

## Automated tests added

| File | Count | Coverage |
|---|---:|---|
| `domain/PhotoUploadTimeoutTest.kt` | 13 | Exact 29,999/30,000 ms boundary, success, network recovery, unchanged budget, slow upload, slow download/classification, uploaded-reference retry, cancellation, explicit errors, late delivery cleanup and fresh retry budget |
| `ui/UploadTimeoutViewModelTest.kt` | 10 | Consent boundary, modal state, no duplicate Snackbar, retained photo, close/manual retry, duplicate clicks, existing ranking on success, navigation/account guards, online/unknown error classification |
| `platform/AndroidNetworkStatusProviderTest.kt` | 6 | Validation requirement, callback cleanup after success/timeout/cancellation, permission and registration failure handling |

Tests use fake storage/network boundaries and virtual coroutine time. The Android
network tests use Robolectric and SDK mocks, not actual airplane mode. The
ViewModel tests exercise the real consent gate, real use case and real ViewModel;
they do not send a photo to Firebase or consume PlantNet/ALA quota.

Run from the repository root with networking enabled for Gradle dependencies:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*PhotoUploadTimeoutTest" --tests "*UploadTimeoutViewModelTest" --tests "*AndroidNetworkStatusProviderTest" --no-daemon --console=plain
```

Then run the existing storage regression and the full project checks:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*FirebasePhotoStorageLocalTest" --no-daemon --console=plain
.\gradlew.bat test lint assembleDebug --no-daemon --console=plain
```

The local test report is normally at
`app/build/reports/tests/testDebugUnitTest/index.html`.

## Device acceptance record

Sign in while online first. Configure the existing PlantNet key and allow photo
consent normally. Test a real scan, not `Continue offline` or the guided demo.
Measure from confirming the upload consent, not from the shutter press.

| ID | Action | Expected result | Device result |
|---|---|---|---|
| UDT-01 | Disable Wi-Fi and mobile data; approve a photo upload | No modal before the deadline; around 30 seconds show the no-internet upload-failure modal; loading stops | Not run |
| UDT-02 | Close the UDT-01 modal | Photo remains; no automatic upload and no duplicate Snackbar | Not run |
| UDT-03 | Reconnect after timeout, then choose Retry | One new attempt using the same approved photo; final candidate/evidence UI remains unchanged | Not run |
| UDT-04 | Restore connectivity around 20 seconds; upload completes before 30 seconds | Continue normal identification; no late modal at 30 seconds | Not run |
| UDT-05 | Leave results or retake the photo before the deadline | No timeout modal for the abandoned attempt | Not run |
| UDT-06 | Rotate the device during upload and again while the modal is open | No new upload due to rotation; one modal; Close/Retry still work | Not run |
| UDT-07 | With internet available, throttle or stall the Firebase upload beyond 30 seconds | Generic upload-timeout message, not a categorical no-internet message | Not run |
| UDT-08 | Upload succeeds quickly but recognition takes more than 30 seconds | No upload-failure modal after upload success | Not run |
| UDT-09 | After timeout, repeatedly tap Retry | At most one new active attempt; the old dialog cannot control it | Not run |
| UDT-10 | Switch accounts or sign out during/after the failed attempt | Old modal and callbacks do not affect the new session | Not run |

## Verification status at delivery

Completed in an isolated local environment: 14 standalone deterministic Kotlin
core checks; 6 network-provider logic checks against local API-shaped fakes;
Kotlin syntax parsing; baseline blob-hash verification; patch forward/reverse
checks and full-file/patch equivalence checks. The core checks compile the actual
modified use case/exception/dialog-state code with repository-shaped domain
interfaces, using a small standalone virtual scheduler rather than JUnit.

Not executed at delivery: the 29 project JUnit/Robolectric tests, the existing
Firebase regression suite, Android lint, complete APK compilation, real Firebase
transfers, or device UI tests. Do not record those as passed until run in the
actual project environment. The package includes the isolated check output and
its supporting harness separately from the files to merge.
