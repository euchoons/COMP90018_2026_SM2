# Device testing evidence

Owner: Mason Lu. Project plan deliverable: *"Device testing evidence — Test at least two phones for denied permissions, missing sensors and camera/location failures."*

This register records how FloraGuide behaves on real phones when permissions are denied, sensors are missing or unreliable, and the camera or location fails. Feature checklists keep their own rows and are tracked at the end, so this page also shows which phones each was run on. It supports rubric criteria 1 (Report and Video), 6 (Sensors), 11 (Guidelines) and 14 (Reactiveness).

All cases are **NOT RUN** until results with evidence are recorded below.

## Phones

| | Phone A | Phone B |
|---|---|---|
| Manufacturer and model | | |
| Android version (API) | | |
| Accelerometer / gyroscope / light / magnetometer | | |
| Tested commit | | |
| Date | | |

`tools/device-evidence.sh info` collects these from the connected phone.

## Recording rules

- Install a debug build of the tested commit and record the commit; disclose uncommitted changes.
- Record what happened, not what was expected. **PASS** needs evidence, **FAIL** records the actual behaviour and an issue, and **BLOCKED** records why the case could not be run. Status meanings match the [device API cases](DEVICE_API_TEST_CASES.md).
- Use the emulator only for missing-sensor fallbacks that no available phone can show, and mark those results **Simulated**. An emulator never proves physical sensor, camera or GPS behaviour.
- Save screenshots and logs under `docs/evidence/device/<model>/`, which is git-ignored. Crop coordinates and account details from anything committed.
- Restore permissions, location and network after each case.

## Register

| ID | Scenario | Expected | Phone A | Phone B | Evidence |
|---|---|---|---|---|---|
| **Permissions** | | | | | |
| DE-01 | Fresh install (`fresh`), open Observe, deny camera once | **Enable camera** stays and asks again when tapped | NOT RUN | NOT RUN | |
| DE-02 | Deny camera a second time, tap **Open app settings**, allow camera, return | The camera slot says access is off and offers **Open app settings**; after allowing, the preview appears without leaving Observe | NOT RUN | NOT RUN | |
| DE-03 | Deny location from **Enable / refresh** | Status "Location permission denied. Identification can continue, but ALA will be skipped."; a capture is identified without ALA counts | NOT RUN | NOT RUN | |
| DE-04 | Grant approximate location only (Android 12+) | Status shows about "±2000 m" and ALA counts appear on results | NOT RUN | NOT RUN | |
| DE-05 | With FloraGuide in the background, revoke camera, then location, in system settings and return | The app restarts without crashing and asks for the revoked permission again | NOT RUN | NOT RUN | |
| DE-06 | Tap **Skip location**, grant location in system settings, return | Location stays skipped until **Enable / refresh** | NOT RUN | NOT RUN | |
| **Missing or unreliable sensors** | | | | | |
| DE-07 | Phone without a gyroscope, or emulator with `hw.gyroscope=no` (Simulated) | Switch greyed out with "Sensor unavailable: manual capture"; pill "Stability n/a"; hint "Manual capture: motion sensors unavailable"; capture works | NOT RUN | NOT RUN | |
| DE-08 | Phone without a light sensor or magnetometer, or emulator with both disabled (Simulated) | Pills "Light n/a" and "Heading n/a"; the saved observation has no heading | NOT RUN | NOT RUN | |
| DE-09 | Cover the light sensor while holding still | Pill "Low light"; hint adds "· low light may blur the photo"; capture still allowed | NOT RUN | NOT RUN | |
| DE-10 | Hold a magnet near the phone, then do the figure-8 gesture | Pill "Calibrate compass", then the bearing returns | NOT RUN | NOT RUN | |
| **Camera failures** | | | | | |
| DE-11 | Open Observe while another app holds the camera, such as a video call | "Camera unavailable — the guided demo on Home still works" and no crash; BLOCKED if the phone hands the camera over | NOT RUN | NOT RUN | |
| DE-12 | Tap the shutter and immediately navigate away | No crash; at most a capture-failed message | NOT RUN | NOT RUN | |
| DE-13 | In airplane mode, capture and agree to online identification | Identification fails at the upload stage with a clear message; the app stays usable | NOT RUN | NOT RUN | |
| **Location failures** | | | | | |
| DE-14 | Location services off, open Observe | Status "Device location is off. Enable it before capture to add ALA context."; a capture is identified without ALA | NOT RUN | NOT RUN | |
| DE-15 | Get a fix, then turn location services off | Status "Device location is off; no capture location is available." | NOT RUN | NOT RUN | |
| DE-16 | Get a fix outdoors, then go indoors or cover the phone for over a minute | Within about 61 s the status reads "Waiting for a new device location. A capture now would skip ALA."; a capture then has no ALA counts | NOT RUN | NOT RUN | |
| DE-17 | Indoors in airplane mode with location on (GPS only, no fix) | Status stays "Waiting for a recent device location..." and never claims a fix | NOT RUN | NOT RUN | |
| DE-18 | Capture with a usable fix, save, open Field Guide | Results show ALA counts; the card shows three-decimal coordinates and the map pin sits there | NOT RUN | NOT RUN | |

## Feature checklists on the same phones

| Checklist | Scope | Phone A | Phone B |
|---|---|---|---|
| [Camera](CAMERA_VALIDATION.md#physical-device-checklist) | Rows 1–16 | not run | not run |
| [Light and heading](HARDWARE_ADAPTERS_VERIFICATION.md#device-checklist) | Rows 1–11 | not run | not run |
| [Motion calibration](../technical/MOTION_STABILITY_CALIBRATION.md#device-calibration-procedure) | Conditions 1–7 | not run | not run |
| [Location measurements](LOCATION_VALIDATION.md#device-measurement-procedure) | Four points, GPS only, approximate | not run | not run |

## Evidence helper

`tools/device-evidence.sh` runs from the repository root in Git Bash or any POSIX shell. It needs `adb` and one connected phone with USB debugging enabled, and finds `adb` in the default Android SDK location if it is not on `PATH`.

| Command | Effect |
|---|---|
| `info` | Saves manufacturer, model, Android version, motion/light/magnetic sensor presence and the tested commit to `device-info.txt` |
| `install` | Builds and installs the debug APK |
| `fresh` | Clears FloraGuide's data and permissions on the phone, which also signs out |
| `shot NAME` | Saves a screenshot |
| `revoke PERMISSION`, `grant PERMISSION` | For `CAMERA`, `ACCESS_FINE_LOCATION` or `ACCESS_COARSE_LOCATION`; revoking restarts the app, as Android does |
| `location on`, `location off` | Toggles location services |
| `airplane on`, `airplane off` | Toggles airplane mode |
| `log-start`, `log-save NAME` | Clears, then saves, the `FloraGuide-Location` log for `tools/location-accuracy.py` |

Older Android versions may reject the location and airplane commands; the script then asks you to use Quick Settings.
