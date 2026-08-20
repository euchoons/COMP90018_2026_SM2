# Nightwatch — Android starter

Context-aware night-walk safety companion for COMP90018. Passively senses a walk
home and, if it detects a possible incident and the user does not respond to a
check-in, notifies their chosen emergency contacts with a location.

This is a **starter skeleton**, not a finished app. The sensing pipeline,
escalation state machine, and UI are real and runnable; the alert delivery is
deliberately stubbed to a logger so nothing is sent to real people during
development.

## Running it

1. Android Studio (Koala or newer), open the project root, let Gradle sync.
2. Run on a **physical device**. The emulator has no real accelerometer or
   ambient light sensor, so fall and dark-area detection cannot be exercised
   properly (you can inject values via Extended Controls, but it is painful).
3. Grant location, microphone and notification permissions when prompted.
4. Tap **Start safe walk**, then put the phone in a pocket and walk around.

Unit tests: `./gradlew test` — these run on the JVM and are the fastest way to
tune the detector thresholds.

## Architecture

```
core/
  SafeWalkEngine.kt      decision layer: detector events -> escalation
  SensorHub.kt           SensorManager subscriptions, routes samples to detectors
  SafetyState.kt         state model (Phase, Threat, SafetyState snapshot)
  detect/
    FallDetector.kt      3-phase state machine: free fall -> impact -> stillness
    SnatchDetector.kt    jerk + angular-velocity co-occurrence
    DistressAudioDetector.kt  RMS level vs adaptive ambient baseline
    ActivityClassifier.kt     still/walking/running from accel variance
    SlidingWindow.kt     allocation-free circular buffer with statistics
  location/LocationTracker.kt   fused location wrapper
  contacts/              contact model + alert dispatch abstraction
service/SafeWalkService.kt      foreground service, survives screen-off
ui/SafeWalkScreen.kt            single-screen Compose UI
```

The flow is one-directional: sensors -> detectors -> engine -> `StateFlow<SafetyState>`
-> UI. The UI never touches a sensor and the detectors never know about alerts,
which keeps each piece independently testable.

## Escalation model

| Phase | Meaning |
|---|---|
| `IDLE` | Walk not started |
| `MONITORING` | Sensing, nothing unusual |
| `CAUTION` | Low-light stretch — advisory only, nothing sent without consent |
| `CONFIRMING` | Threat detected, countdown running, user can cancel |
| `ALERTED` | Countdown expired — contacts notified |

Countdown length varies by how much the detector is trusted: fall 15 s, snatch
10 s, distress sound 12 s, unexpected sprint 20 s. Nothing is ever sent without a
cancellable window first.

## What is still TODO

These are the gaps to close for Assignment 2, roughly in priority order:

- **Real alert delivery.** Implement `RemoteAlertDispatcher` against a backend
  (Firebase or a simple REST service) that fans out to contacts and returns a
  signed, expiring live-location URL. This is what earns the Connectivity marks —
  the logging dispatcher will not.
- **Contact management UI.** `ContactRepository` is an in-memory stub; move it to
  DataStore or Room and add an add/remove screen.
- **Threshold tuning against real traces.** The constants in the detectors are
  starting points, not tuned values. Record traces (walking, jogging, phone into
  pocket, phone onto a couch, controlled falls onto a mattress) and replay them
  through the unit tests.
- **Upgrade the audio detector.** Replace the level heuristic with an on-device
  classifier (TFLite YAMNet head over log-mel features). This is the single
  strongest technical-depth story available in this project.
- **Battery.** Continuous 50 Hz accelerometer plus high-accuracy GPS plus mic is
  heavy. Measure it, then consider duty-cycling GPS while the gait classifier
  reports `STILL`.
- **Map view.** The screen currently shows readings, not a route.

## Rubric notes (Assignment 2)

- **Sensors (10):** accelerometer, gyroscope, ambient light, microphone, GPS —
  all load-bearing, none decorative. Each drives a distinct detection.
- **Connectivity (12):** currently the weakest area. Do the backend work early.
- **Technical depth (6):** the multi-phase fall state machine and the
  co-occurrence snatch logic are the arguments here; the TFLite audio classifier
  would be stronger still.
- **Innovation (16):** the hands-off escalation is the novelty — no button press,
  no unlock. The privacy posture (audio never leaves the device, on-device
  inference) is the cross-disciplinary angle.

## Safety and ethics

The app only ever *informs* contacts and shares a location. It never suggests
anyone intervene physically, and the alert text points to emergency services
(000). Treat the detectors as advisory: they will produce false negatives, and no
one should rely on this as their only safety measure.

Do not wire up automated messaging to anyone who has not agreed to it. Test
against your own numbers.
