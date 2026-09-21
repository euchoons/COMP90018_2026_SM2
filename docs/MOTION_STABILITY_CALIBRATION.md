# Motion stability calibration

Owner: Mason Lu. Project plan deliverable: *"Motion stability — Calibrate accelerometer/gyroscope stability thresholds and justify the chosen values."*

This document states what the stability gate computes, what each constant means in physical units, why the prototype values were changed, and what still has to be measured on real phones before the values can be called calibrated rather than chosen. It supports rubric criteria 6 (Sensors), 9 (Technical Depth) and 14 (Reactiveness).

## Where the values live

| Constant | Value | Code |
|---|---:|---|
| `ACCELERATION_WEIGHT` | 0.9 | `domain/sensor/MotionStabilityEstimator.kt` |
| `ANGULAR_VELOCITY_WEIGHT` | 0.6 | same |
| `SMOOTHING_ALPHA` | 0.3 | same |
| `STABLE_THRESHOLD` | 0.6 | same |
| Gravity low-pass α (heading only) | 0.18 | `platform/SensorMonitor.kt` |
| Sampling rate hint | `SENSOR_DELAY_UI` | same |

They were previously inline in `SensorMonitor` and `SensorSnapshot`, where they could not be tested. `MotionStabilityEstimatorTest` now pins every derived figure in this document, so changing a constant without updating the reasoning fails the build.

## What the gate computes

Per sensor event:

```text
accelerationDeviation = | ‖a‖ − g |            (m/s², g = 9.80665)
angularVelocity       = ‖ω‖                    (rad/s)

target = exp(−(0.9 · accelerationDeviation + 0.6 · angularVelocity))
score  = 0.7 · previousScore + 0.3 · target

capture unlocked when score ≥ 0.6
```

The gate is advisory: `ScanScreen` lets the user switch it off, and it is skipped entirely on phones without both sensors. It never blocks the guided demo.

## What the constants mean in physical units

`exp(−x) ≥ 0.6` is the same as `x ≤ ln(1/0.6) = 0.511`. So a steady hand has a **motion budget** of 0.511 to spend across the two cues:

```text
0.9 · accelerationDeviation + 0.6 · angularVelocity ≤ 0.511
```

| Motion | Tolerated at the gate |
|---|---|
| Acceleration deviation only | 0.511 / 0.9 = **0.57 m/s²** |
| Angular velocity only | 0.511 / 0.6 = **0.85 rad/s ≈ 49 °/s** |
| Both together | e.g. 0.35 m/s² leaves only 0.27 rad/s ≈ 16 °/s |

The previous values (threshold 0.78, weights 1.55 and 1.25) gave a budget of `ln(1/0.78) = 0.248`, so:

| Motion | Old tolerance | Current tolerance |
|---|---|---|
| Acceleration deviation only | 0.16 m/s² | 0.57 m/s² |
| Angular velocity only | 0.20 rad/s ≈ 11 °/s | 0.85 rad/s ≈ 49 °/s |

The gate is therefore about 3.5× more permissive in translation and 4.3× in rotation. Those ratios treat each cue alone; when both move together the widening works out at about 3.7×, derived below.

## Response time

The score is an exponential moving average applied **once per sensor event**, not per unit of time. With the accelerometer, gyroscope and magnetometer all registered at `SENSOR_DELAY_UI` (a nominal 60 ms each, so roughly 50 events per second combined), one step is about 20 ms:

| Behaviour | Steps | Approx. time | Old values |
|---|---:|---:|---|
| Unlock from rest, phone perfectly still | 3 | ~60 ms | 8 steps, ~160 ms |
| Lock after a jolt (target ≈ 0) | 2 | ~40 ms | 2 steps, ~40 ms |
| Close 90% of the gap to a held target | 6.5 | ~130 ms | 12 steps, ~240 ms |
| Smoothing time constant | — | ~56 ms | ~101 ms |

A single bad sample therefore cannot close the gate — it takes two consecutive ones — which prevents the shutter flickering on one noisy reading. Both figures are pinned by tests.

**Smoothing changes latency, not reach.** An exponential moving average converges to its target whatever the retention weight, so 0.82/0.18 and 0.7/0.3 settle on exactly the same value. Held at a target of 0.65, the old filter would have converged to 0.65 and never reached a 0.78 gate no matter how long the user waited. The smoothing change bought faster confirmation, not a reachable gate; that came from the coefficients and the threshold.

The cost is noise. For white-noise input the EMA output variance scales as `(1−α)/(1+α)` in the retention weight `α`, so output jitter rises by about 34% (0.31σ to 0.42σ). That matters where the score sits close to the gate — see the margin discussion below.

**Caveat:** because smoothing counts events rather than milliseconds, the response time depends on how fast the device actually delivers sensor data. `SENSOR_DELAY_UI` is a hint, and a phone that delivers faster will settle proportionally sooner. See the follow-ups.

## Why the values were changed

The prototype defaults made the gate hard to open by hand, so commit `41c5b9b` relaxed them. The analysis below is from Mason's note *"Accelerometer and Gyroscope Values"* (3 September 2026); the figures have been reproduced and are pinned by `MotionStabilityEstimatorTest`.

### Two independent relaxations that compound

**1. The coefficients set a motion scale.** In `exp(−(a·c₁ + ω·c₂))`, each coefficient's reciprocal is the deviation that costs one factor of e (about 0.37):

| Cue | Old | New | Widening |
|---|---|---|---:|
| Acceleration | 1.55 → 0.65 m/s² per e-fold | 0.9 → 1.11 m/s² | 1.7× |
| Angular | 1.25 → 0.80 rad/s per e-fold | 0.6 → 1.67 rad/s | 2.1× |

The curve decays more slowly, so the same physical tremor lands further up the exponential.

**2. The threshold moves the pass line.** Independently of the curve, 0.78 → 0.6 accepts scores the old gate rejected.

### The combined boundary

Assuming angular deviation is about half the acceleration deviation, the score collapses to one variable: `old = exp(−2.175a)`, `new = exp(−1.2a)`.

| Acceleration deviation (m/s²) | Old score | Old gate 0.78 | New score | New gate 0.60 |
|---:|---:|---|---:|---|
| 0.0 | 1.000 | pass | 1.000 | pass |
| 0.1 | 0.805 | pass | 0.887 | pass |
| 0.2 | 0.647 | fail | 0.787 | pass |
| 0.3 | 0.521 | fail | 0.698 | pass |
| 0.4 | 0.419 | fail | 0.619 | pass |
| 0.5 | 0.337 | fail | 0.549 | fail |
| 0.6 | 0.271 | fail | 0.487 | fail |
| 0.8 | 0.176 | fail | 0.383 | fail |

The old gate passed only below about **0.11 m/s²**; the new one passes out to about **0.43 m/s²**, a **3.7× wider** acceptance band. The old curve crosses its gate almost immediately, which is the behaviour that made the gate feel stuck.

### Why that band had to widen: sensor bias

The 0.11 m/s² figure is the strongest argument for the change, because a still phone can exceed it without moving at all:

- The gate reads `TYPE_ACCELEROMETER`, which is bias-corrected but not perfect. Phone-class MEMS accelerometers typically specify zero-g offsets of tens of milli-g; at 1 mg ≈ 0.0098 m/s², that is roughly **0.1–0.4 m/s²**.
- A motionless phone can therefore consume the entire old budget through calibration offset alone, so on an affected device the gate would rarely open however still the user held it.
- Gyroscope zero-rate offsets are typically a few °/s, well inside even the old 11 °/s rotation budget, so rotation was never the binding constraint.

This also answers the obvious alternative: lowering only the threshold, keeping the old coefficients, would have allowed about 0.23 m/s² under the same half-angular assumption. That fixes the immediate symptom, but it still sits inside the plausible bias band, so a phone at the high end could remain stuck. The coefficient change buys margin against the hardware rather than against the user's hand.

### The cost: a thinner reject margin

Widening the curve moves the gate closer to genuinely shaky motion. Using the illustrative pairs from the note — a steady hand at (0.2 m/s², 0.1 rad/s) and a shaky one at (0.5, 0.25):

| Configuration | Steady hand | Shaky hand | Distance from gate |
|---|---:|---:|---|
| Current (0.9 / 0.6, gate 0.60) | 0.787 | 0.549 | shaky only 0.05 below |
| Old coefficients, gate 0.60 | 0.647 | 0.337 | shaky 0.26 below, steady only 0.05 above |
| Old coefficients, gate 0.78 | 0.647 | 0.337 | both fail |

Neither configuration centres the gate between the two states: the current one sits close to the shaky cluster, the threshold-only alternative sits close to the steady cluster. With the current values a shaky hand is rejected by a margin of only 0.05, and the faster filter's 34% extra jitter can make that margin flicker.

The practical trade-off is which error is worse. A gate that will not open is a blocking bug; a gate that occasionally admits a slightly shaky photo costs one retake and is softened by the light warning. The current values fail in the cheaper direction, and Mason reports they behave correctly in hand-held use. The safest refinement, if the flicker appears on other phones, is **hysteresis** — unlock at 0.60, re-lock at 0.50 — which keeps the reachable band and removes the boundary oscillation without another recalibration. Raising the threshold to about 0.65 would instead centre the gate between the two example states.

**Status:** the derivations hold and the values have been confirmed informally on Mason's device. The per-device bias, the true steady/shaky score distributions and the flicker behaviour are still unmeasured; the procedure below records them.

## Is 49 °/s still tight enough to prevent blur?

Angular motion dominates handheld blur. For a rotation `ω` held for exposure `t`, the image smears by roughly:

```text
blur_px ≈ ω · t · f_px          f_px = (width / 2) / tan(HFOV / 2)
```

Assuming a 1920 px wide capture (the cap set in `CAMERA_VALIDATION.md`) and a 70° horizontal field of view, `f_px ≈ 1371 px/rad`:

| Exposure | Blur at 49 °/s (gate limit) | Blur at 11 °/s (old limit) |
|---|---:|---:|
| 1/1000 s | 1.2 px | 0.3 px |
| 1/500 s | 2.3 px | 0.5 px |
| 1/250 s | 4.7 px | 1.1 px |
| 1/125 s | 9.3 px | 2.2 px |
| 1/60 s | 19.5 px | 4.6 px |

Reading of this:

- In daylight, where campus observations are expected, phones use short exposures and even the gate limit costs only a few pixels. Identification also downscales the photo further, which shrinks the blur proportionally.
- In dim conditions the exposure lengthens and the gate alone stops being sufficient. That is exactly where the ambient-light warning matters, and it is an argument for making the threshold depend on measured lux rather than staying fixed.
- 49 °/s is a deliberate sweep of the phone, not a hand tremor, so the gate still blocks the gross motion it was designed to block. The cost of the relaxation is concentrated in low light.

## Known limitations of the metric

1. **`‖a‖ − g` is nearly blind to horizontal shake.** Adding a horizontal acceleration `h` to gravity changes the magnitude by about `h² / 2g`: a 1 m/s² sideways shove shows up as only 0.05 m/s². Vertical motion is measured at full scale. The gyroscope covers most handheld shake, which is rotational, but a pure sideways drift is largely invisible to the gate. Comparing the raw vector against the low-passed gravity vector, which `SensorMonitor` already maintains for the compass, would measure all three axes equally.
2. **No exposure awareness.** The same threshold applies at 100,000 lux and at 5 lux, although the blur consequence differs by more than an order of magnitude.
3. **Event-count smoothing.** The response time varies with the device's delivery rate, and registering or losing a sensor changes it.
4. **Translation blur is not modelled.** It depends on subject distance, which the app does not know.
5. **No bias calibration.** A constant per-device offset is treated as motion.

## Device calibration procedure

The current values have been confirmed informally in hand-held use, but no numbers are recorded yet. This procedure is the evidence that converts "works for me" into "calibrated"; `CONTRIBUTING.md` requires physical devices for sensor claims, and the plan requires at least two phones.

Temporarily log `accelerationDeviation`, `angularVelocity` and `score` from `SensorMonitor`, then record each condition for about 30 s:

| # | Condition | Phone A: median / 95th percentile | Phone B |
|---|---|---|---|
| 1 | Flat on a table, untouched (measures bias) | | |
| 2 | Held still, standing, arms braced | | |
| 3 | Held still, standing, arms extended | | |
| 4 | Held still immediately after walking up | | |
| 5 | Walking slowly while framing | | |
| 6 | Panning deliberately across a garden bed | | |
| 7 | Phone without a gyroscope, if the team has one | | |

Acceptance criteria to aim for:

- Condition 1 confirms the bias band; the acceleration budget must sit clearly above it.
- Conditions 2–4: the gate opens within about a second, and stays open for at least 95% of samples.
- Conditions 5–6: the gate stays shut for at least 95% of samples, and closes within about 200 ms of motion starting.
- Watch for flicker specifically: the shaky-hand score is predicted to sit only about 0.05 below the gate, so note any case where the shutter enables and disables repeatedly. That is the trigger for adding hysteresis.
- Record the resulting photos too: a gate that opens but yields visibly blurred photos in shade means the threshold is still too loose.

If the criteria conflict on the two phones, prefer the stricter value that still passes conditions 2–4 on both, and record the trade-off here.

## Recommended follow-ups

Ranked by value for the remaining project time:

1. Run the procedure above and replace this section's estimates with measurements, including the steady and shaky score distributions the margin analysis assumes.
2. Add hysteresis (unlock at 0.60, re-lock at 0.50) if the measurements show the gate oscillating near the boundary. This is cheap, keeps the current calibration and removes the thin reject margin.
3. Measure the deviation from the low-passed gravity vector instead of from the gravity constant, so horizontal shake counts. This changes the meaning of the acceleration weight and needs recalibration.
4. Make the smoothing time-based, using `SensorEvent.timestamp`, so the response time is the same on every phone.
5. Consider a lux-dependent threshold: stricter in low light, where blur costs most.
6. Consider `SENSOR_DELAY_GAME` while the Observe screen is visible if the gate feels sluggish, and measure the battery cost.
