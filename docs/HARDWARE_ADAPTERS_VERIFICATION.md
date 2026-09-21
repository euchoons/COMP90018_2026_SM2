# Hardware adapters: light and heading

Owner: Mason Lu. Project plan deliverable: *"Hardware adapters — Verify ambient-light guidance and magnetometer heading, including missing-sensor fallbacks."*

This document records what was checked in the ambient-light and compass paths, the defects found and fixed, what each fallback does when a sensor is absent or unreliable, and the device tests still required. It supports rubric criteria 6 (Sensors), 13 (Language) and 14 (Reactiveness).

Related: [`MOTION_STABILITY_CALIBRATION.md`](MOTION_STABILITY_CALIBRATION.md) for the accelerometer/gyroscope gate, [`CAMERA_VALIDATION.md`](CAMERA_VALIDATION.md) for the capture path.

## Summary

| Area | Result | Commit |
|---|---|---|
| Heading | Defect fixed: the azimuth was measured along the phone's top edge, which is undefined in the normal photographing pose. | `fix(sensor): measure observation heading along the rear camera axis` |
| Heading | Defect fixed: an unreliable compass was displayed and saved as if it were good. | `feat(sensor): flag an uncalibrated compass instead of showing its heading` |
| Heading | Defect fixed: observations stored the heading from confirmation time, not capture time. | `fix(observation): record the compass heading at capture time` |
| Light | Defect fixed: the low-light and glare assessment was computed but never shown. | `fix(sensor): show light guidance and missing-sensor states to the user` |
| Light | Defect fixed: pills duplicated the thresholds, disagreeing at exactly 20,000 lux. | same |
| Fallbacks | Defect fixed: phones without a gyroscope showed "Hold still" permanently. | same |
| Fallbacks | Verified: missing light sensor, missing magnetometer, missing motion sensors. | — |

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
| `USABLE` | 25 to 20,000 lux | Overcast to bright daylight |
| `VERY_BRIGHT` | 20,000 lux and above | Inside the 10,000–25,000 lux band of full daylight; direct sun reads higher still |

These are inherited prototype values, kept rather than changed because there is no device measurement to justify moving them. The 20,000 lux line is the weaker of the two: it sits inside normal daylight, so on a sunny day the warning may fire on most captures. If field testing shows that, raising it towards the 32,000 lux direct-sun figure is the first thing to try. "Very bright" replaced "possible glare" because the sensor cannot actually see glare in the lens.

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

### Known limitation: magnetic north

The value is a **magnetic** bearing. True north in Melbourne differs by roughly 11–12° east, and correcting it requires `GeomagneticField` with the observation's location and date. The app does not do this, so the stored heading should be described as magnetic. This matters if headings are ever compared with map bearings.

## Missing-sensor fallbacks

| Sensor absent | Behaviour | Verified by |
|---|---|---|
| Ambient light | Pills show "Light n/a"; no warning in the capture hint; capture unaffected | `SensorSnapshotTest`, code review |
| Magnetometer | Pills show "Heading n/a"; observations store a null heading | code review |
| Gyroscope | Stability gate disabled, switch greyed out, hint reads "Manual capture fallback active", pills show "Stability n/a" | `MotionStabilityEstimatorTest`, `SensorSnapshotTest` |
| Accelerometer | As above; heading also unavailable, since the rotation matrix needs gravity | code review |
| All four | Snapshot publishes once at start-up with everything unavailable; capture works manually | code review |

**Defect fixed:** with a missing gyroscope the stability score can never cross the threshold, because `SensorMonitor` holds angular velocity at its initial 1 rad/s. `ScanScreen` correctly switched to manual capture, but the camera overlay still showed a permanent red "Hold still", telling the user to fix something they could not fix. It now shows "Stability n/a". A test pins the underlying behaviour so the two stay consistent.

Device declarations are already correct: every sensor is declared `required="false"` in the manifest, so the app installs on phones that lack them.

## Device checklist

Not yet run. Record phone model and Android version with each result.

| # | Scenario | Expected | Phone A | Phone B |
|---|---|---|---|---|
| 1 | Point the camera north, east, south and west while standing, phone upright | Pill bearing matches a separate compass app within a few degrees | | |
| 2 | Tilt the phone down to photograph ground cover | Bearing stays sensible, then switches to the top-edge reference when nearly flat | | |
| 3 | Hold a magnet or magnetic case near the phone | Pill changes to "Calibrate compass" | | |
| 4 | Perform the figure-8 calibration gesture | Bearing returns | | |
| 5 | Save an observation, then check the Field Guide entry | Stored heading matches the direction at capture, not at confirmation | | |
| 6 | Indoors under normal lighting | "Low light" only below about 25 lux; compare with a lux meter app | | |
| 7 | Outdoors in open sun | Note how often "Very bright" appears; it should not be constant on an ordinary day | | |
| 8 | Cover the light sensor with a finger | Warning appears and the hint changes | | |
| 9 | A phone without a gyroscope, if the team can borrow one | "Stability n/a", manual capture works | | |
| 10 | Airplane mode plus no location | Light and heading pills unaffected | | |

## Follow-ups

1. Run the checklist; the light thresholds in particular need lux-meter comparison before they can be called verified.
2. Decide whether `SENSOR_STATUS_LOW` should also prompt calibration, based on how often phones report it.
3. Apply `GeomagneticField` to convert magnetic heading to true north, or label the stored value as magnetic wherever it is displayed.
4. Reconsider the 20,000 lux threshold if the "Very bright" warning proves too eager outdoors.
