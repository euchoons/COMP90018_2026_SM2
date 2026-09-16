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
       -> ImageClassifier.classify (Pl@ntNet or demo adapter)
       -> FirebasePhotoStorage.uploadPhoto
  -> PhotoThumbnail on Results and Field Guide (EXIF-aware decode)
```

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
| Resize | Defect fixed: thumbnails used a fixed sample size regardless of photo size. | `perf(ui): size thumbnail decode sample rate to the photo` |
| Capture | Verified, no change. | — |
| Firebase upload | Verified working in code; issues recorded for the cloud-data owner. | — |

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

- uploaded to Pl@ntNet over a 5 s connect / 20 s read timeout;
- uploaded to Firebase Storage;
- decoded for thumbnails.

**Fix:** a `ResolutionSelector` targeting **1920×1440** (4:3, about 2.8 MP) with `FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER`, so devices without that exact size pick the nearest smaller one, then the nearest larger one.

Why 1920×1440:

- The only measured Pl@ntNet round trip (`TECHNICAL_REFERENCE.md`) used a 1123×1600 photo and returned HTTP 200 in about 3.4 s. The cap stays above that proven resolution while removing most of the upload size of a 12–50 MP capture.
- The planned on-device LiteRT model takes a far smaller input tensor, so it does not need more.
- 4:3 matches the preview's default aspect ratio, so the photo covers the same field of view the user framed.

**Affects other workstreams:** Pl@ntNet (Mingyang) and Firebase (Seb/Asleif) now receive smaller photos. Identification quality at this size should be confirmed with the evaluation photos before the final report.

### Defect: fixed thumbnail sample size

`inSampleSize = 4` was fixed. For a 50 MP photo that still decodes a 2040×1530 bitmap (~12 MB) for a 90–104 dp thumbnail. Decoding happens in composition, so several such thumbnails in the Field Guide risk jank and out-of-memory errors.

**Fix:** read the image bounds first, then choose the largest power-of-two sample size that keeps the shorter side at least 400 px (about 104 dp at xxxhdpi). 12 MP photos keep the previous value of 4, and the new 1920×1440 captures use 2. `ThumbnailSampleSizeTest` pins these cases.

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

## Firebase photo upload (in progress by teammates)

Firebase integration is not finished, so the code was verified and nothing in `FirebasePhotoStorage` was changed.

Verified working in code:

- `analyzeCapturedPhoto` receives the absolute path from `capturePhoto`. `uploadPhoto` checks that the file exists, then uploads it to `plant_photos/<random UUID>.jpg` with content type `image/jpeg` and the original file name as custom metadata.
- `app/google-services.json` contains a `storage_bucket`, so `FirebaseStorage.getInstance()` in `AppContainer` does not throw at start-up.
- The guided demo (`photoPath == null`) returns before uploading, so demo runs do not create cloud files.
- The upload success path no longer overwrites ALA warnings (`953c4b5`).
- Uploaded JPEGs contain no GPS tags (see Capture) and are now resolution-capped (see Resize).

Issues for the cloud-data owners:

| Issue | Consequence | Suggested direction |
|---|---|---|
| Upload fires on every capture, before the user confirms a species. | Retakes, non-plant photos and rejected observations are stored in the cloud, while the plan syncs *confirmed* records only (architecture Phase 4). | Upload from `confirmSelectedObservation`, or through the planned sync queue. |
| The upload failure path still writes to `uiState.message`. | A failed upload replaces an ALA partial/offline warning, which is the same problem `953c4b5` fixed for the success path. | Surface upload state separately, as the UI integration deliverable plans. |
| No authentication is used. | Uploads only succeed if Storage rules allow unauthenticated writes; the rules are not in the repository and could not be checked. If they require auth, every capture shows "Photo upload failed". | Verify the rules in the Firebase console; add auth before per-user rules. |
| The storage path is only logged. | An uploaded photo cannot be linked to its `Observation` or deleted later. | Store the path on the observation record when Firestore is added. |

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
| 14 | Capture, then check Firebase Storage `plant_photos/` | New JPEG present; note whether upload succeeded without auth | | |
| 15 | Capture several photos, open Field Guide | Thumbnails upright and scrolling smooth | | |
| 16 | Tap the shutter and immediately navigate away | No crash; at most a capture-failed snackbar | | |

## Remaining limitations

- No physical-device results are recorded yet; the checklist above is the evidence still owed.
- The 1920×1440 cap is justified by one Pl@ntNet measurement, not by an accuracy comparison across resolutions.
- Photo retention, upload timing and authentication belong to the persistence and cloud workstreams and are recorded above rather than changed here.
