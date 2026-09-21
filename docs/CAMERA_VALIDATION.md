# Camera validation

Owner: Mason Lu. Project plan deliverable: *"Camera — Validate the existing CameraX lifecycle, capture, rotation, resize and permission paths."*

This document records what was checked in the camera path, what was changed and why, what was left for other workstreams, and the physical-device tests that are still required. It supports rubric criteria 5 (Quality), 6 (Sensors), 11 (Guidelines) and 12 (Flow).

## Scope and method

The camera path runs from the Observe screen to the results screen and the photo upload:

```text
ScanScreen (permissions, stability gate)
  -> CameraCaptureCard (CameraX preview + ImageCapture)
  -> capturePhoto: filesDir/photos/observation-<epoch ms>.jpg
  -> FloraGuideViewModel.analyzeCapturedPhoto
       -> IdentifyStoredPhotoUseCase: upload -> download -> classify
  -> PhotoThumbnail on Results and Field Guide (EXIF-aware decode)
```

This branch was rebased onto `main` on 2026-09-20, after the account, Room cache and sync work landed. Identification now runs on the photo stored in Firebase rather than the local file, so the notes below describe that flow.

Method:

1. Code review of every step above against the CameraX and Android permission guidance.
2. Fixes for the defects found, each in its own commit.
3. JVM unit tests where the logic can run without Android (`ThumbnailSampleSizeTest`).
4. `./gradlew testDebugUnitTest lintDebug assembleDebug` passing after the changes.

**Not yet done:** physical-device testing. Camera behaviour cannot be proven by JVM tests or an emulator, so the checklist at the end must be completed on at least two phones before this deliverable is claimed as validated.

## Summary

| Path | Result | Commit |
|---|---|---|
| Lifecycle | Defect fixed: the camera could bind after the Scan screen was gone. | `fix(camera): guard late provider callbacks and surface start failures` |
| Lifecycle | Defect fixed: a failed start showed "Starting CameraX…" forever; devices with only a front camera could not capture. | same |
| Permissions | Defect fixed: after a permanent denial the Enable button did nothing. | `fix(camera): route permanently denied camera permission to app settings` |
| Permissions | Defect fixed: a permission granted in system settings was ignored until the screen was re-entered. | same |
| Rotation | Defect fixed: with auto-rotate locked, landscape photos were tagged as portrait. | `fix(camera): follow physical orientation and bound capture resolution` |
| Resize | Defect fixed: full-sensor JPEGs were uploaded to Pl@ntNet and Firebase on every capture. | same |
| Resize | Thumbnail decoding: no change needed; `ObservationPhotoLoader` on `main` already samples by size off the main thread. | — |
| Capture | Verified, no change. | — |
| Firebase upload | Verified against the account-scoped upload/download flow; open points recorded for the cloud-data owner. | — |

## Lifecycle

### What was already correct

- `bindToLifecycle` uses the activity as lifecycle owner, so CameraX closes the camera on `onStop` and reopens it on `onStart`. Backgrounding the app releases the camera without extra code.
- The `DisposableEffect` is keyed on the lifecycle owner, and `onDispose` calls `unbindAll()`. Leaving the Observe screen releases the camera.
- Rotating the device recreates the activity (the manifest does not declare `configChanges`). The old effect unbinds, the new one binds against the new activity, and the `ViewModel` survives.
- `isSaving` disables the shutter while a capture is in flight, preventing double captures.

### Defect: binding after disposal

`ProcessCameraProvider.getInstance()` completes asynchronously. If the user left the Observe screen before it completed, `onDispose` ran while `provider` was still `null`, so nothing was unbound. The listener then ran and bound the camera to the activity lifecycle with no preview on screen. The camera stayed open (and its privacy indicator stayed on) until the activity stopped.

**Fix:** a `disposed` flag set in `onDispose` and checked first in the listener. Both run on the main executor, so no synchronisation is needed.

### Defect: silent start failure and back-camera assumption

`CameraSelector.DEFAULT_BACK_CAMERA` was hard-coded. The manifest declares `android.hardware.camera.any` as not required, so the app installs on devices with only a front camera (some tablets and Chromebooks) or no camera. There, binding threw, a snackbar appeared briefly, and the overlay said "Starting CameraX…" forever.

**Fix:** use the back camera if present, otherwise the front camera, otherwise fail with an explicit message. A `cameraFailed` state replaces the loading text with "Camera unavailable — the guided demo on Home still works", which matches the fallback listed in `ARCHITECTURE.md`.

### Known behaviour, not changed

- **Capture in flight while leaving the screen.** `unbindAll()` makes CameraX fail the pending capture, and its error message appears as a snackbar on the next screen. If the image was already written, the app opens Results with that photo. Both outcomes follow from something the user pressed, so they were left as they are.
- **The camera is a `LazyColumn` item.** If the Observe screen is scrolled until the camera card is completely off screen, Compose disposes it and the camera unbinds, then rebinds when it scrolls back. This is acceptable and saves power, but a short restart delay is expected.

## Capture

Verified, no change required:

- Photos are written to app-private storage (`filesDir/photos`), so no storage permission is needed and other apps cannot read them.
- File names use the capture time in epoch milliseconds. A double tap is blocked by `isSaving`, so names cannot collide.
- `CAPTURE_MODE_MINIMIZE_LATENCY` is appropriate: the stability gate already waits for a steady phone, so shutter lag matters more than the small quality gain of maximise-quality mode.
- No `ImageCapture.Metadata` location is set, so CameraX writes **no GPS tags** into the JPEG. This matters for privacy because the file is uploaded to Pl@ntNet and Firebase, while the plan only allows coarsened coordinates to be stored.

Recorded for follow-up:

- Every capture, including retakes and unconfirmed photos, stays in `filesDir/photos` indefinitely. A cleanup policy belongs with the persistence and retention work (Seb's privacy deliverable).

## Rotation

### Defect: orientation locked to the display

At capture time the code set `targetRotation = previewView.display.rotation`. That is correct only while the display rotates with the phone. With auto-rotate turned off, which is common, the display stays portrait even when the user holds the phone sideways, so a landscape photo was tagged as portrait. `PhotoThumbnail` honours the EXIF tag, so the photo was shown on its side on Results and in the Field Guide. It was also sent to Pl@ntNet and Firebase with the wrong orientation.

**Fix:** follow the CameraX guide's recommendation and set `ImageCapture.targetRotation` from an `OrientationEventListener`, which reports the phone's physical orientation whatever the display is doing. `ORIENTATION_UNKNOWN` (a phone lying flat, for a top-down shot) keeps the last known value. On a device without an accelerometer, `canDetectOrientation()` is false and the display rotation chosen at bind time is kept.

### Verified, no change

`decodePhotoThumbnail` handles all eight EXIF orientations, including mirrored ones, and falls back to the undecorated bitmap if the transform fails.

## Resize

### Defect: full-sensor captures

No resolution was requested, so `ImageCapture` used the largest 4:3 size the camera offers: 12 MP on most phones and 50 MP on many recent ones. Each JPEG (several MB) was then:

- uploaded to Firebase Storage;
- downloaded again and sent to Pl@ntNet over a 5 s connect / 20 s read timeout;
- decoded for thumbnails.

**Fix:** a `ResolutionSelector` targeting **1920×1440** (4:3, about 2.8 MP) with `FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER`, so devices without that exact size pick the nearest smaller one, then the nearest larger one.

Why 1920×1440:

- The only measured Pl@ntNet round trip (`TECHNICAL_REFERENCE.md`) used a 1123×1600 photo and returned HTTP 200 in about 3.4 s. The cap stays above that proven resolution while removing most of the upload size of a 12–50 MP capture.
- Identification now uploads *and* downloads the photo, so capture size is paid for twice on the phone's connection.
- `FirebasePhotoStorage` rejects anything over 20 MiB. A capped capture stays far below that limit on every device.
- The planned on-device LiteRT model takes a far smaller input tensor, so it does not need more.
- 4:3 matches the preview's default aspect ratio, so the photo covers the same field of view the user framed.

**Affects other workstreams:** Pl@ntNet (Mingyang) and Firebase (Seb/Asleif) now receive smaller photos. Identification quality at this size should be confirmed with the evaluation photos before the final report.

### Thumbnail decoding: no change required

An earlier commit on this branch sized the thumbnail sample rate to the photo. `main` has since replaced that code with `ObservationPhotoLoader`, which decodes on `Dispatchers.IO`, caps the long edge at 1024 px and falls back to the owned cloud object. That supersedes the change, so it was dropped during the rebase rather than reapplied.

## Permissions

### What was already correct

- The camera card is only composed once `CAMERA` is granted; otherwise a permission card explains why camera and location are needed and offers the guided sample.
- Camera and location are requested together from the permission card. Location alone can be requested from the location card. A location denial falls back to the labelled campus demo location.
- Granting either fine **or** coarse location counts, which respects Android 12's approximate-location choice.
- Duplicate location starts (from the permission callback and the `LaunchedEffect`) are harmless because `LocationTracker.start` calls `stop()` first.
- Revoking a permission in system settings kills the process, so a stale "granted" state cannot survive.

### Defect: permanent denial was a dead end

After a second denial (Android 11+) or "Don't ask again", `launch()` returns *denied* immediately without showing a dialog. The only button on the card then did nothing when tapped.

**Fix:** after a request that included `CAMERA`, if the permission is denied and `shouldShowRequestPermissionRationale` is false, the card explains that camera access is turned off and shows **Open app settings**, which opens `ACTION_APPLICATION_DETAILS_SETTINGS` for the app. The location-only request cannot reset this state.

Known edge case: if the user *dismisses* the very first dialog (back gesture or tapping outside it) without choosing, Android also reports denied with no rationale, so the settings route is shown. That route still lets the user grant access, so the edge case was accepted rather than adding timing-based guesswork.

### Defect: grants from settings were not noticed

Permission state was only read when the screen was first composed, or when a request returned. A user who granted camera access from settings and came back still saw the permission card.

**Fix:** `LifecycleResumeEffect` re-checks both permissions on every resume.

## Firebase photo upload

Checked against the account-scoped flow that arrived with the cache and authentication repair. Nothing in `FirebasePhotoStorage` or `IdentifyStoredPhotoUseCase` was changed here; this is the camera side's view of it.

Verified in code:

- The absolute path from `capturePhoto` reaches `IdentifyStoredPhotoUseCase`, which uploads, downloads and only then classifies. Each stage is labelled, so a failure tells the user which step failed instead of giving one generic error.
- Photos are stored per account at `plant_photos/<uid>/<sha256>.<ext>`. Naming by content hash means a retried capture does not create a duplicate object.
- The account is re-checked before and after upload, so a sign-out mid-upload cannot write into the previous user's area.
- `FirebasePhotoStorage` requires a readable file of 1 byte to 20 MiB and derives the content type from the file itself.
- The downloaded copy is deleted in a `finally` block; the camera original and the cloud object are kept.
- `app/google-services.json` contains a `storage_bucket`, so Firebase Storage resolves at start-up.
- The guided demo (`photoPath == null`) never uploads, so demo runs create no cloud files.
- Uploaded JPEGs carry no GPS tags (see Capture) and are resolution-capped (see Resize).
- A confirmed observation now records `cloudPhotoUri`, so the stored photo can be found again and, in future, deleted.

Open points for the cloud-data owners:

| Point | Consequence | Suggested direction |
|---|---|---|
| Every capture is uploaded before the user confirms a species, because identification reads the stored photo. | Retakes, non-plant photos and rejected candidates all leave objects in the user's storage area, which the retention policy must cover. | Deliberate trade-off; decide whether unconfirmed photos are swept up later. |
| Identification requires a successful upload and download. | With no network there is no identification at all, where the local file alone would previously have been enough. | Confirm this is the intended MVP behaviour, and note it in the offline story. |
| Local originals in `filesDir/photos` are never deleted. | Storage grows with every capture, including unconfirmed ones. | Fold into the photo retention policy. |
| Firebase Storage security rules are not in the repository. | Per-user isolation cannot be verified from the code alone; the client-side ownership check in `ObservationPhotoLoader` is not a substitute for a server rule. | Export the rules into the repo so they can be reviewed and evidenced. |

## Physical-device checklist

Record each result with the phone model and Android version (`CONTRIBUTING.md` requires this for camera changes). Keep small screenshots under `docs/evidence/`.

| # | Scenario | Expected | Phone A | Phone B |
|---|---|---|---|---|
| 1 | Fresh install → Observe → allow camera and location | Preview starts; shutter enables when steady | | |
| 2 | Deny camera once | Permission card stays; request can be repeated | | |
| 3 | Deny camera twice (or "Don't ask again") | Card says access is off and shows **Open app settings** | | |
| 4 | From 3, grant camera in settings and return | Camera card appears without leaving Observe | | |
| 5 | Revoke camera in settings while the app is in the background, then return | App restarts; permission card is shown | | |
| 6 | Allow camera, deny location | Camera works; campus demo location is labelled | | |
| 7 | Choose approximate location (Android 12+) | Treated as granted; live location shown | | |
| 8 | Open Observe and immediately tap Home | Camera privacy indicator turns off | | |
| 9 | Home button while previewing, then return | Preview resumes; capture still works | | |
| 10 | Rotate the phone with auto-rotate **on**, then capture | Thumbnail is upright | | |
| 11 | Auto-rotate **off**, hold the phone landscape, capture | Thumbnail is upright (was sideways before the fix) | | |
| 12 | Capture, then check the JPEG size and dimensions (Device Explorer → `files/photos`) | About 1920×1440 or the nearest supported size | | |
| 13 | Capture with network on and a Pl@ntNet key | Candidates returned; note the latency in Logcat | | |
| 14 | Sign in, capture, then check Firebase Storage `plant_photos/<uid>/` | New JPEG under your own account; identification reports its stage on failure | | |
| 14b | Capture with the network disabled | Identification fails at the upload stage with a clear message; the app stays usable | | |
| 15 | Capture several photos, open Field Guide | Thumbnails upright and scrolling smooth | | |
| 16 | Tap the shutter and immediately navigate away | No crash; at most a capture-failed snackbar | | |

## Remaining limitations

- No physical-device results are recorded yet; the checklist above is the evidence still owed.
- The 1920×1440 cap is justified by one Pl@ntNet measurement, not by an accuracy comparison across resolutions.
- Photo retention, offline identification and storage rules belong to the persistence and cloud workstreams and are recorded above rather than changed here.
