# Hardware adapters: light and heading

Owner: Mason Lu. Project plan deliverable: *"Hardware adapters — Verify ambient-light guidance and magnetometer heading, including missing-sensor fallbacks."*

This document records what was checked in the ambient-light and compass paths, the defects found and fixed, what each fallback does when a sensor is absent or unreliable, and the device tests still required. It supports rubric criteria 6 (Sensors), 13 (Language) and 14 (Reactiveness).

Related: [`MOTION_STABILITY_CALIBRATION.md`](../technical/MOTION_STABILITY_CALIBRATION.md) for the accelerometer/gyroscope gate, [`CAMERA_VALIDATION.md`](CAMERA_VALIDATION.md) for the capture path.

## Summary

| Area | Result | Commit |
|---|---|---|
| Heading | Defect fixed: the azimuth was measured along the phone's top edge, which is undefined in the normal photographing pose. | `fix(sensor): measure observation heading along the rear camera axis` |
| Heading | Defect fixed: an unreliable compass was displayed and saved as if it were good. | `feat(sensor): flag an uncalibrated compass instead of showing its heading` |
| Heading | Defect fixed: observations stored the heading from confirmation time, not capture time. | `fix(observation): record the compass heading at capture time` |
| Heading | Fixed: bearings were magnetic, about 12° off true north in Melbourne; they now use true north whenever a location is known. | `feat(sensor): show and store compass bearings relative to true north` |
| Light | Defect fixed: the low-light and glare assessment was computed but never shown. | `fix(sensor): show light guidance and missing-sensor states to the user` |
| Light | Defect fixed: pills duplicated the thresholds, disagreeing at exactly 20,000 lux. | same |
| Fallbacks | Defect fixed: phones without a gyroscope showed "Hold still" permanently. | same |
| Fallbacks | Verified: missing light sensor, missing magnetometer, missing motion sensors. | — |
| Stability switch | Defect fixed: the switch reset to on whenever Observe was reopened. | `fix(sensor): keep the stability gate setting between Observe visits` |
| Stability switch | Defect fixed: with the gate off, the pill still said "Hold still" while the hint said "Ready to capture", and on and off looked identical while the phone was still. | `fix(sensor): show the stability gate state in the capture hint and pill` |
| Fallbacks | Regression fixed: phones without motion sensors had lost their manual-capture hint and showed "Ready to capture". | same |
| Light | Regression fixed: the low-light and glare warning had dropped out of the capture hint in a later `ScanScreen` rewrite (`5b7bf9e`). | `fix(sensor): restore the light warning in the capture hint` |
| Light | Threshold raised: "Very bright" now starts at 50,000 lux, because Phone A read over 30,000 lux under cloud. | `fix(sensor): raise the very-bright light threshold to 50,000 lux` |

## Ambient light

### What the sensor actually tells us

The ambient-light sensor sits next to the **front** display, so it measures light falling on the user's side of the phone, not the exposure of the scene the rear camera is pointed at. It is a proxy for "are we in bright daylight or a dim room", and the wording in the UI stays at that level rather than claiming anything about the photo's exposure. The project plan's own description — "warn of poor exposure conditions", never blocking capture — is what is implemented.

### Defect: the guidance was invisible

`SensorSnapshot.lightAssessment` produced "Low light", "Light looks usable" or "Possible glare", and **nothing displayed it**. Both pills showed a bare lux number, so the user saw `12 lux` with no hint that this was a problem. The plan lists a low-light warning as a deliverable, so the cue existed but was not delivered.

**Fix:** ambient light is now classified once as a `LightCondition` (`UNAVAILABLE`, `LOW`, `USABLE`, `VERY_BRIGHT`). The camera overlay and the Home summary label it ("Low light · 12 lux", "Very bright · 45000 lux"), and the capture hint gains a short explanation of the consequence: *"Stable — low light may blur the photo"*. Capture is never blocked.

### Defect: duplicated thresholds

The model used `25f..<20_000f` while both pills used `25f..20_000f`. At exactly 20,000 lux the pill was green while the assessment said glare. Both now read `SensorSnapshot.lightCondition`, and `SensorSnapshotTest` pins the boundaries.

### Threshold status

| Condition | Range | Basis |
|---|---|---|
| `LOW` | below 25 lux | Dimmer than typical indoor lighting; handheld exposures lengthen and blur risk rises |
| `USABLE` | 25 to 50,000 lux | Overcast to bright daylight, including bright cloud |
| `VERY_BRIGHT` | 50,000 lux and above | Direct sun, which typically reads about 60,000–100,000 lux on a phone's light sensor |

The low-light value is still an inherited prototype value. The very-bright line used to be 20,000 lux, inside the 10,000–25,000 lux band of ordinary daylight. On 2026-10-05 Phone A read over 30,000 lux on South Lawn under cloud, so the warning fired on overcast days, which are good light for plant photos. It now starts at 50,000 lux: clear of those cloudy readings and below typical direct sun. The often-quoted 32,000 lux direct-sun figure would still have been within reach of cloudy readings. The sensor faces the screen side, so with the sun ahead of the user it can read low even in harsh light; the warning is advice and never blocks capture. "Very bright" replaced "possible glare" because the sensor cannot actually see glare in the lens.

The low-light warning matters more than it first appears: the stability gate's tolerance allows roughly 49 °/s of rotation, which is harmless at daylight shutter speeds but visible at the long exposures low light forces. Light and motion interact, and tying the stability threshold to lux is a recorded follow-up in the calibration document.

## Compass heading

### Defect: the wrong axis

`SensorManager.getOrientation` returns the azimuth of the device's **+Y axis**, that is the direction the top edge of the phone points. That is correct for a phone held flat like a map. It is wrong for this app: photographing a plant means holding the phone upright, where +Y points at the sky, its horizontal projection collapses towards zero, and the azimuth becomes numerically ill-conditioned — small tilts swing it wildly. The heading shown on the overlay, and saved with the observation, was therefore close to meaningless in the app's normal pose.

**Fix:** `observationHeadingDegrees` uses the direction the **rear camera** faces (the device −Z axis) whenever that axis is within about 60° of horizontal, and falls back to the top edge when the phone is flat enough for a top-down shot, where the camera axis is the degenerate one instead. Five unit tests build rotation matrices for known poses (flat, upright portrait facing each cardinal direction, upright landscape, tilted 45° down) and assert the expected bearing.

### Defect: unreliable readings presented as good

`onAccuracyChanged` was an empty implementation. A magnetometer disturbed by metal, magnets or a phone case reports `SENSOR_STATUS_UNRELIABLE`, and the app displayed that reading as a normal heading and stored it with the observation.

**Fix:** magnetometer accuracy is tracked from both `onAccuracyChanged` and each event. While it is `UNRELIABLE` the pills say "Calibrate compass" instead of a bearing, and `reliableHeadingDegrees` returns null so nothing persists it. Only `UNRELIABLE` is flagged: many phones sit at `LOW` accuracy for long stretches, and hiding the heading there would make it unavailable most of the time. Whether `LOW` also deserves flagging is a device-testing question.

### Defect: the heading was recorded too late

`confirmSelectedObservation` read the **live** heading at the moment the user pressed Confirm. By then the user has lowered the phone, walked, and is reading the Results screen, so the stored bearing described where they were pointing while reading, not what they photographed.

**Fix:** the heading is captured with the photo and carried through the analysis state. Retrying identification keeps the original value, and the guided demo stores none.

### Fixed: bearings were magnetic

The bearing used to be **magnetic**, about 11–12° east of true north in Melbourne, so it disagreed with ordinary compass apps by that much. Bearings now use true north whenever a device location is known. The pill adds the declination that Android's `GeomagneticField` gives for the current fix. Saved observations, which always have a capture location, store true bearings. Without a fix the pill shows the magnetic bearing, labelled "magnetic". Observations saved before this change hold magnetic bearings.

## Missing-sensor fallbacks

| Sensor absent | Behaviour | Verified by |
|---|---|---|
| Ambient light | Pills show "Light n/a"; no warning in the capture hint; capture unaffected | `SensorSnapshotTest`, code review |
| Magnetometer | Pills show "Heading n/a"; observations store a null heading | code review |
| Gyroscope | Stability gate disabled, switch greyed out, hint reads "Manual capture: motion sensors unavailable", pills show "Stability n/a" | `MotionStabilityEstimatorTest`, `SensorSnapshotTest`, `CaptureGuidanceTest` |
| Accelerometer | As above; heading also unavailable, since the rotation matrix needs gravity | code review |
| All four | Snapshot publishes once at start-up with everything unavailable; capture works manually | code review |

**Defect fixed:** with a missing gyroscope the stability score can never cross the threshold, because `SensorMonitor` holds angular velocity at its initial 1 rad/s. `ScanScreen` correctly switched to manual capture, but the camera overlay still showed a permanent red "Hold still", telling the user to fix something they could not fix. It now shows "Stability n/a". A test pins the underlying behaviour so the two stay consistent.

Device declarations are already correct: every sensor is declared `required="false"` in the manifest, so the app installs on phones that lack them.

## Stability gate switch

A teammate reported that the "Stability-gated capture" switch on Observe did not change anything. The gate itself worked, but three things hid it:

- With the phone still, the gate is already open, so on and off looked identical. The emulator's motion sensors never move, so there the switch always looked inert.
- The "Hold still" pill ignored the switch. With the gate off it contradicted the "Ready to capture" hint.
- The switch was `rememberSaveable` state inside `ScanScreen`, which is discarded whenever Observe leaves the composition, so it reset to on after every capture or navigation.

`captureGuidance()` now derives the shutter state, hint, pill and switch summary together, so they cannot disagree:

| Motion sensors | Switch | Phone | Shutter | Pill | Hint |
|---|---|---|---|---|---|
| Present | On | Steady | Enabled | Steady | Ready to capture |
| Present | On | Moving | Disabled | Hold still | Hold still before capturing |
| Present | Off | Steady | Enabled | Steady | Ready to capture (stability gate off) |
| Present | Off | Moving | Enabled | Moving | Moving: photo may blur (stability gate off) |
| Missing | Greyed out | — | Enabled | Stability n/a | Manual capture: motion sensors unavailable |

Once the shutter is enabled, low or very bright light adds a warning, as in "Ready to capture · low light may blur the photo"; light never blocks capture. While the upload-consent dialog is open, the shutter stays disabled and the hint reads "Choose whether to use online identification" instead of "Hold still before capturing". The switch summary now describes the current behaviour rather than only naming the sensors. The setting lives in `FloraGuideUiState`, so it survives navigation and account changes; an app restart returns it to on. `CaptureGuidanceTest` pins every row and `FloraGuideViewModelTest` pins the persistence.

## Device checklist

Phone A is the OnePlus PGP110 (Android 15) listed in the [device testing evidence](DEVICE_TESTING_EVIDENCE.md#phones); Phone B has not run this checklist. Record phone model and Android version with each result.

| # | Scenario | Expected | Phone A | Phone B |
|---|---|---|---|---|
| 1 | Point the camera north, east, south and west while standing, phone upright | Pill bearing matches a separate compass app set to true north within about 10°, with the phones at least 50 cm apart | PASS | |
| 2 | Tilt the phone down to photograph ground cover | Bearing stays sensible, then switches to the top-edge reference when nearly flat | PASS | |
| 3 | Hold a magnet or magnetic case near the phone | Pill changes to "Calibrate compass" | PASS (DE-10) | |
| 4 | Perform the figure-8 calibration gesture | Bearing returns | PASS (DE-10) | |
| 5 | Save an observation, then check the Field Guide entry | Stored heading matches the direction at capture, not at confirmation | PASS | |
| 6 | Indoors under normal lighting | "Low light" only below about 25 lux; compare with a lux meter app | PASS | |
| 7 | Outdoors in open sun | Note how often "Very bright" appears; it should not be constant on an ordinary day | FAIL at the old 20,000 lux line: over 30,000 lux and "Very bright" under cloud on South Lawn. Threshold raised to 50,000 lux; a direct-sun re-check is pending | |
| 8 | Cover the light sensor with a finger | Warning appears and the hint changes | PASS (DE-09) | |
| 9 | A phone without a gyroscope, if the team can borrow one | "Stability n/a", manual capture works | N/A (has a gyroscope); covered by DE-07 (Simulated) | |
| 10 | Airplane mode plus no location | Light and heading pills unaffected | PASS | |
| 11 | Wave the phone with "Stability-gated capture" on, then off; leave and reopen Observe | On: shutter greys out and the hint says "Hold still". Off: shutter stays enabled and the pill reads "Moving". The choice survives reopening Observe | PASS | |

## Follow-ups

1. Done on Phone A. A direct-sun reading still has to confirm the 50,000 lux threshold.
2. Decide whether `SENSOR_STATUS_LOW` should also prompt calibration, based on how often phones report it.
3. Done on 2026-10-06: bearings use true north via `GeomagneticField` when a location is known, and are labelled magnetic otherwise.
4. Done on 2026-10-06: the 20,000 lux threshold proved too eager under cloud and was raised to 50,000 lux. Confirm it with a reading in direct sun.
