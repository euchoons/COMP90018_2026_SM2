# Per-photo upload consent — Issue #7

## Scope and status

This implementation addresses the post-capture consent prompt and pre-consent transfer boundary requested in Issue #7. It is **not a complete resolution of Issue #7**. Safe deletion of already-uploaded, unconfirmed and shared photos remains coordinated with Issue #46.

Source baseline: `euchoons/COMP90018_2026_SM2`, `main`, commit `4e7fb379c132374815eecca17fbd629d5c6ff740`.

## User flow

```text
Shutter press: freeze capture ID, time, location and heading
  -> CameraX writes a local file named with that capture ID
  -> Show consent dialog; do not call the identification pipeline yet
     -> Cancel / Android Back: queue removal of this unsent local file; remain on Scan
     -> Agree and identify: Firebase upload -> verified download -> Pl@ntNet
        -> Optional ALA lookup using the already captured, rounded location
        -> Results
           -> Retry the same capture: reuse its current consent
           -> Save: confirm the observation separately; existing sync behaviour is unchanged
```

Outside-dialog taps do not accept consent or dismiss the dialog. Navigation is hidden while the dialog is present. The existing explicit offline guided demo remains available after cancelling.

Upload is deliberately **not moved to `confirmSelectedObservation()`** in this change. `IdentifyStoredPhotoUseCase` currently requires a completed Firebase download before Pl@ntNet classification. Changing that would be a separate pipeline redesign, not a consent-only change.

## Invariants

| Boundary | Behaviour |
|---|---|
| Before positive consent | No Firebase photo upload/download, Pl@ntNet identification or ALA lookup is started for this capture by the ViewModel. Existing background account/observation traffic is not disabled by this dialog. |
| Consent identity | Consent matches capture ID, account UID and local path, and is held only in the ViewModel's in-memory gate. |
| Repeated taps | Approval consumes the pending request once. |
| Retry | Live identification and context retry entry points require a matching active grant. |
| New capture / navigation / account change | Pending consent is discarded and the active grant is cleared. A later capture needs its own positive choice. |
| Late CameraX callback | A mismatched shutter filename, old account or off-screen callback cannot start identification. |
| Local deletion | Only new UUID-named files directly inside the app's photo directory are eligible, and only when never accepted. Saved/accepted paths, arbitrary paths, subdirectories and symlinks are not cleanup targets. |
| Scope of deletion | No cloud delete call or observation deletion is added. |

## What the dialog discloses

The dialog names Firebase Storage and Pl@ntNet; it also explains the ALA lookup when a usable capture location exists. It distinguishes identification from saving and notes the existing account synchronisation behaviour. It does not promise that leaving a result page recalls data already sent, strips all image metadata, or immediately deletes uploaded photos.

There is no permanent “always allow” preference and no claim that the offline demo performs genuine on-device plant recognition. Permission to use the camera is separate from consent to send this capture to online services.

## Cleanup policy and remaining work

Before consent, Cancel, navigation and ViewModel clearing schedule a bounded local-file deletion on an application-owned IO scope. A late unconsented camera result is also eligible. Cleanup is idempotent and restricts paths to the new capture naming scheme. A normal CameraX error also attempts to remove its incomplete output.

This is **best-effort local cleanup**. It is not durable across process death, device shutdown, app removal or storage failures. There is no startup sweep or persistent cleanup retry queue in this patch; process-death recovery for an unsent file remains unimplemented. A failed live cleanup can report a local-file warning while the same session is active.

After consent, this patch does not delete the local original or the uploaded object when the user leaves without saving. The existing identification use case still removes its download-cache file. Existing historical files are not swept. Pl@ntNet-side retention is outside this application's deletion control.

Cloud object cleanup must follow Issue #46, including shared references, concurrent saves/sync, ownership checks, retryable failures and multiple devices. Merely checking the current UI observation list is not a safe global reference check. Do not add a direct `deletePhoto(storedPhoto.gsUri)` call on Back/Cancel.

## Automated validation

Added standard Gradle/JUnit test sources:

- `PhotoConsentGateTest`: 9 tests for identity binding, positive consent, cancellation, replacement and retries.
- `CapturedPhotoFilesTest`: 5 tests for eligible local deletion, idempotence and path boundaries.
- `FloraGuidePhotoConsentTest`: 8 Robolectric/MockK ViewModel tests for no transfer before consent, exactly-one approval, cancellation, navigation, account changes, retries, accepted-file protection and the guided-demo pipeline boundary.

The ViewModel tests deliberately mock the cloud use-case boundary. They do not contact Firebase, Pl@ntNet or ALA and do not consume API quota.

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --no-daemon --console=plain
```

Package-generation verification: 25 standalone JVM smoke cases for the pure consent gate and file policy passed. The standalone run is **not** the Gradle/JUnit suite. The complete Android build, the 22 added Gradle tests and the device cases below were not run in the patch-generation environment. The PowerShell installer was not executed in Windows in that environment.

## Device acceptance checklist

All cases below are initially **Not run**. Record actual device/API level, account mode, timestamp and evidence after execution. Never record API keys or private image URLs in the evidence.

| ID | Action | Expected result | Status |
|---|---|---|---|
| I7-DT01 | Capture a photo and leave the consent dialog open. | No new object from this capture appears in Storage and no Pl@ntNet/ALA request for it occurs. | Not run |
| I7-DT02 | Choose Cancel. | Remain on Scan; the dialog and pending capture disappear; the unsent local file is removed when IO cleanup completes. | Not run |
| I7-DT03 | Press Android Back while the dialog is open. | Same cancellation result; no live analysis begins. | Not run |
| I7-DT04 | Tap outside the dialog. | Dialog stays open; no consent is inferred. | Not run |
| I7-DT05 | Choose Agree and identify. | Exactly one identification pipeline starts; results retain the existing Firebase -> Pl@ntNet flow. No observation is saved automatically. | Not run |
| I7-DT06 | Rapidly tap the positive action, then test a network failure and Retry. | No duplicate pipeline from the double tap; retry is allowed for the same capture after consent. | Not run |
| I7-DT07 | Leave the results and capture another photo. | The new capture requires its own consent. | Not run |
| I7-DT08 | Rotate the device while the consent dialog is visible. | The same pending choice survives normal Activity recreation; no network request starts automatically; no duplicate capture occurs. | Not run |
| I7-DT09 | Navigate away during JPEG saving; separately change account before an old capture callback arrives. | Old results cannot start a transfer or open results in the new session. | Not run |
| I7-DT10 | Capture with location, wait at the dialog, then agree; repeat after Skip location. | Shutter-time metadata stays fixed. Rounded ALA coordinates are used only in the first case; no ALA query in the skipped case. | Not run |
| I7-DT11 | Cancel, then run the explicit offline guided demo. | Existing demo remains available and the real-photo cloud pipeline is not invoked. | Not run |
| I7-DT12 | Accept and save a photo; then leave the page and exercise navigation. | Unsent-photo cleanup never removes that accepted/saved local photo. Existing cloud-deletion limitations are tracked separately. | Not run |

Known-gap check: kill the process while awaiting consent and inspect the unsent local file on restart. Do not label this as passed automatic cleanup: recovery of such files is not implemented by this patch.

## Review and issue linkage

Suggested title: `feat(privacy): require per-photo consent before online identification (#7)`.

Use `Refs #7, #46`, not `Closes #7`. Verify the new branch's own build and device behaviour before merging. Do not reuse the baseline branch's CI result as proof that these changes passed.
