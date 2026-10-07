# Device testing evidence

Owner: Mason Lu. Project plan deliverable: *"Device testing evidence — Test at least two phones for denied permissions, missing sensors and camera/location failures."*

This register records how FloraGuide behaves on real phones when permissions are denied, sensors are missing or unreliable, and the camera or location fails. Feature checklists keep their own rows and are tracked at the end, so this page also shows which phones each was run on. It supports rubric criteria 1 (Report and Video), 6 (Sensors), 11 (Guidelines) and 14 (Reactiveness).

All cases are **NOT RUN** until results with evidence are recorded below.

## Phones

| | Phone A | Phone B | Emulator (Simulated) |
|---|---|---|---|
| Manufacturer and model | Oneplus PGP110 | Google Pixel 10a | Google sdk_gphone64_x86_64 (Android Emulator) |
| Android version (API) | 15 (API 35) | 16 (API 36) | 17 (API 37) |
| Accelerometer / gyroscope / light / magnetometer | yes / yes / yes / yes | yes / yes / yes / yes | yes / no / no / no (disabled in `config.ini`) |
| Tested commit | e0bb84a | 2fa9a4c | 69fd0b4 |
| Date | 05/10/2026 | 06/10/2026 | 06/10/2026 |

`tools/device-evidence.sh info` collects these from the connected phone. The emulator is used only for DE-07 and DE-08, because no available phone lacks those sensors.

## Recording rules

- Install a debug build of the tested commit and record the commit; disclose uncommitted changes.
- Record what happened, not what was expected. **PASS** needs evidence, **FAIL** records the actual behaviour and an issue, and **BLOCKED** records why the case could not be run. Status meanings match the [device API cases](DEVICE_API_TEST_CASES.md).
- Use the emulator only for missing-sensor fallbacks that no available phone can show, and mark those results **Simulated**. An emulator never proves physical sensor, camera or GPS behaviour.
- Save screenshots and logs under `docs/evidence/device/<model>/`, which is git-ignored. Crop coordinates and account details from anything committed.
- To link evidence from this page, commit selected files with `git add -f` after removing coordinates and account details. Phone B's are committed at half resolution, with the Firebase storage path blacked out on the result screenshots.
- Restore permissions, location and network after each case.

## Register

| ID | Scenario | Expected | Phone A | Phone B | Evidence |
|---|---|---|---|---|---|
| **Permissions** | | | | | |
| DE-01 | Fresh install (`fresh`), open Observe, deny camera once | **Enable camera** stays and asks again when tapped | PASS | PASS | Phone B: `Pixel_10a/DE-01.jpg` |
| DE-02 | Deny camera a second time, tap **Open app settings**, allow camera, return | The camera slot says access is off and offers **Open app settings**; after allowing, the preview appears without leaving Observe | PASS | PASS | Phone B: `Pixel_10a/DE-02-denied.jpg`, `Pixel_10a/DE-02-preview.jpg` |
| DE-03 | Deny location from **Enable / refresh** | Status "Location permission denied. Identification can continue, but ALA will be skipped."; a capture is identified without ALA counts | PASS | PASS | Phone B: status shown; the capture was identified with ALA not queried. `Pixel_10a/DE-03-status.jpg`, `Pixel_10a/DE-03-result.jpg` |
| DE-04 | Grant approximate location only (Android 12+) | Status "Approximate location ready · ±2000 m" and ALA counts on results; Save is disabled with an explanation, and **Use precise location** asks to upgrade | PASS | PASS | Fixed in `63f3fb1`: approximate fixes now stay usable for 15 min. Re-run on 2026-10-06 verified it. Evidence: `DE-04.png`, `DE-04-Disabled-save.png`, `DE-04-Precise.png`, `A-loc-approx.log`<br>Phone B: DE-03's denial left location as "don't ask again", so FloraGuide's location flags were reset with `pm clear-permission-flags` first. ALA records shown, Save disabled with its explanation, precise prompt shown. `Pixel_10a/DE-04-status.jpg`, `Pixel_10a/DE-04-ala-counts.jpg`, `Pixel_10a/DE-04-disabled-save.jpg`, `Pixel_10a/DE-04-precise.jpg` |
| DE-05 | With FloraGuide in the background, revoke camera, then location, in system settings and return | The app restarts without crashing and asks for the revoked permission again | PASS | PASS | Before the fix: the camera closed but the option to take a photo was still there; only changing pages (Field Guide, Home or Account) showed the camera-closed message and closed the camera properly. Fixed in `34a40c7` (permissions re-read on resume) and `8494f7c` (shutter follows the camera state). Re-run on 2026-10-06 verified the camera has to be re-enabled. Evidence: `DE-05.png`<br>Phone B: no FloraGuide entry in the crash log; Observe asks for camera and location again. `Pixel_10a/DE-05.jpg` |
| DE-06 | Tap **Skip location**, grant location in system settings, return | Location stays skipped until **Enable / refresh** | PASS | PASS | Phone B: stays skipped after granting in settings; **Enable / refresh** then shows "Device location ready · ±100 m". `Pixel_10a/DE-06-skipped.jpg`, `Pixel_10a/DE-06-refreshed.jpg` |
| **Missing or unreliable sensors** | | | | | |
| DE-07 | Phone without a gyroscope, or emulator with `hw.gyroscope=no` (Simulated) | Switch greyed out with "Sensor unavailable: manual capture"; pill "Stability n/a"; hint "Manual capture: motion sensors unavailable"; capture works | N/A (has all four sensors) | N/A (has all four sensors) | **PASS (Simulated)** on the emulator: switch greyed out with "Sensor unavailable: manual capture", pill "Stability n/a", hint "Manual capture: motion sensors unavailable"; a photo was captured. Evidence: `sdk_gphone64_x86_64/DE-07.png` |
| DE-08 | Phone without a light sensor or magnetometer, or emulator with both disabled (Simulated) | Pills "Light n/a" and "Heading n/a"; capture still works; the stored heading is null (check in Firestore, or covered by unit tests, since no screen shows a saved heading) | N/A (has all four sensors) | N/A (has all four sensors) | **PASS (Simulated)** on the emulator: pills "Light n/a" and "Heading n/a" on the same screen as DE-07, and capture works. The stored heading was not checked in Firestore; `FloraGuideViewModelTest` covers a missing heading being saved as null. Evidence: `sdk_gphone64_x86_64/DE-08.png` |
| DE-09 | Cover the light sensor while holding still | Pill "Low light"; hint adds "· low light may blur the photo"; capture still allowed | PASS | PASS | Phone B: "Low light · 4 lux", shutter still enabled. `Pixel_10a/DE-09.jpg` |
| DE-10 | Hold a magnet near the phone, then do the figure-8 gesture | Pill "Calibrate compass", then the bearing returns | PASS | PASS | Phone B: with the magnet near, the pill alternated every few seconds between "Calibrate compass" and a fixed bearing set by the magnet (176°, then 229°); after a figure-8 the bearing returned and stayed stable (#78). Counted as a pass: the magnet (an iPhone's MagSafe ring) was too weak to keep Android's "unreliable" flag set, so the warning flickered rather than staying on as it did on Phone A. A stronger magnet is expected to give the same result. `Pixel_10a/DE-10-calibrate.jpg`, `Pixel_10a/DE-10-restored.jpg`, `Pixel_10a/DE-10-flicker.txt` |
| **Camera failures** | | | | | |
| DE-11 | Put Observe and a video call (or the Camera app) side by side in split-screen, tap the other app, then tap FloraGuide again | While the other app has focus, FloraGuide's half shows "Another app is using the camera. Close it to continue." with the shutter disabled; tapping FloraGuide brings its preview back. The other app's camera then freezes, because Android gives the camera to the app in focus | PASS | NOT RUN | Redefined 2026-10-05 with `8494f7c`. Re-run on 2026-10-06: the app correctly shows that another application is using the camera and asks to close it first. Evidence: `DE-11.png` |
| DE-12 | Tap the shutter and immediately navigate away | No crash; at most a capture-failed message | PASS | PASS | Popup message of camera is closed appears<br>Phone B: "Camera is closed." shown, no crash. `Pixel_10a/DE-12.jpg` |
| DE-13 | In airplane mode, capture and agree to online identification | Identification fails at the upload stage with a clear message; the app stays usable | FAIL | NOT RUN | The program will continue to try to upload with no signs of failure message |
| **Location failures** | | | | | |
| DE-14 | Location services off, open Observe | Status "Device location is off. Enable it before capture to add ALA context."; a capture is identified without ALA | PASS | PASS | Phone B: status shown; the capture was identified with ALA not queried. `Pixel_10a/DE-14-status.jpg`, `Pixel_10a/DE-14-result.jpg` |
| DE-15 | Get a fix, then turn location services off | Status "Device location is off; no capture location is available." | PASS | NOT RUN | |
| DE-16 | Get a fix outdoors, then go indoors or cover the phone for over a minute | Within about 61 s the status reads "Waiting for a new device location. A capture now would skip ALA."; a capture then has no ALA counts | PASS | NOT RUN | |
| DE-17 | Indoors in airplane mode with location on (GPS only, no fix) | Status stays "Waiting for a recent device location..." and never claims a fix | PASS | NOT RUN | |
| DE-18 | Capture with a usable fix, save, open Field Guide | Results show ALA counts; the card shows three-decimal coordinates and the map pin sits there | PASS | NOT RUN | |

## Feature checklists on the same phones

A lot of these are similar to what is tested in the testing above.

| Checklist | Scope | Phone A | Phone B |
|---|---|---|---|
| [Camera](CAMERA_VALIDATION.md#physical-device-checklist) | Rows 1–16 | PASS, except row 14b: an upload with the network off showed no failure, the same issue as DE-13 (FAIL, outside this workstream). Rows first BLOCKED by the DE-05 and DE-11 defects pass after those fixes | not run |
| [Light and heading](HARDWARE_ADAPTERS_VERIFICATION.md#device-checklist) | Rows 1–11 | PASS, except row 7: "Very bright" fired under cloud at the old 20,000 lux line, so the threshold is now 50,000 lux, which direct sun (over 100,000 lux) confirmed. Row 9 is covered by DE-07 (Simulated) | not run |
| [Motion calibration](../technical/MOTION_STABILITY_CALIBRATION.md#device-calibration-procedure) | Conditions 1–6 | PASS on conditions 1–3; 4–5 recorded as secondary; 6 covered by DE-07 (Simulated); panning dropped | PASS on conditions 1–3; 4–5 not run |
| [Location measurements](LOCATION_VALIDATION.md#device-measurement-procedure) | Four points, GPS only, approximate | All recorded. Four points and GPS only: outdoor criteria pass, indoor GPS accuracy is optimistic. Approximate: accepted at ±2000 m after the DE-04 fix; the delivery interval was not measured | not run |

## Evidence helper

`tools/device-evidence.sh` runs from the repository root in Git Bash or any POSIX shell. It needs `adb` and one connected phone with USB debugging enabled, and finds `adb` in the default Android SDK location if it is not on `PATH`. With an emulator or a second phone attached, set `ANDROID_SERIAL` to the phone's serial for every command, and install with `./gradlew assembleDebug` and `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`: `install` runs `./gradlew installDebug`, which installs on every attached device.

| Command | Effect |
|---|---|
| `info` | Saves manufacturer, model, Android version, motion/light/magnetic sensor presence and the tested commit to `device-info.txt` |
| `install` | Builds and installs the debug APK |
| `fresh` | Clears FloraGuide's data and permissions on the phone, which also signs out |
| `shot NAME` | Saves a screenshot |
| `revoke PERMISSION`, `grant PERMISSION` | For `CAMERA`, `ACCESS_FINE_LOCATION` or `ACCESS_COARSE_LOCATION`; revoking restarts the app, as Android does |
| `location on`, `location off` | Toggles location services |
| `airplane on`, `airplane off` | Toggles airplane mode |
| `log-start`, `log-save NAME`, `motion-save NAME` | Clears the logs, then saves the location log (for `tools/location-accuracy.py`) or the stability-gate log (for `tools/motion-calibration.py`) |

Older Android versions may reject the location and airplane commands; the script then asks you to use Quick Settings.
