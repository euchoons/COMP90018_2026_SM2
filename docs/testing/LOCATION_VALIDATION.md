# Location validation

Owner: Mason Lu. Project plan deliverable: *"Location — Validate GPS accuracy and support the location data consumed by the campus map."*

This document checks the location path end to end: what the reported accuracy means, why the 2 km / 60 s eligibility limits and the 0.001° storage grid suit the app, the defects found and fixed, and the device measurements that validate the limits on real phones. It supports rubric criteria 6 (Sensors), 7 (Connectivity), 9 (Technical depth) and 19 (Impact).

Related: the [missing-context policy](../technical/MISSING_CONTEXT_POLICY.md) for how a usable location feeds ALA ranking, [data handling](../PRIVACY_POLICY.md) for coarsening and debug logging, and [device testing evidence](DEVICE_TESTING_EVIDENCE.md) for the failure-path checks.

## Summary

| Area | Result | Commit |
|---|---|---|
| Eligibility limits | Justified: 2 km admits Android's approximate location and keeps at least 84% of the 8 km ALA search area; 60 s keeps walking drift below one grid cell. Device measurements pending. | — |
| Status | Defect fixed: "Device location ready" stayed on screen after the fix aged out or its provider was switched off, while a capture then skipped ALA. | `fix(location): stop presenting an aged-out fix as ready` |
| Map data | Defect fixed: imported or synced records with finer coordinates were pinned at their exact position. | `fix(map): keep observation pins on the storage grid` |
| Grid | Refactor: the 0.001° rule existed twice; `GeoPoint.coarsened()` is now the only copy. | `refactor(location): define the 0.001° coarsening grid once` |
| Measurement | Debug builds log every fix; `tools/location-accuracy.py` summarises a session. | `feat(location): log fixes in debug builds for accuracy measurement` |
| Map label | Finding for the map owner: the Field Guide map says "Following your location" although tracking stops outside Observe. | not changed |

## How a location reaches ALA and the map

```text
GPS and network providers (LocationManager, foreground only, 2.5 s update requests)
  -> LocationTracker keeps the best usable fix (LocationFreshnessPolicy)
  -> at the shutter, snapshotForObservation() re-checks permission, provider, age and accuracy
  -> a usable fix is coarsened to 0.001° for the ALA query (8 km radius) and the saved observation
  -> the Field Guide map pins observations at their coarse location
```

A missing or unusable location never blocks capture: identification continues image-only and ALA is skipped. A live capture never substitutes the campus demo coordinate.

## What the reported accuracy means

`Location.getAccuracy()` is the radius of 68% confidence around the reported position. It is the provider's own estimate, not a measured error: if it is honest, about two-thirds of fixes lie within it. For a circular Gaussian error the 95% radius is 1.62 times the 68% radius, so a fix reported at ±2,000 m is within about 3.24 km with 95% confidence. The measurements below test that honesty against surveyed reference points.

## Accuracy limit: 2,000 m

Two independent arguments support the limit.

1. **Approximate location must stay usable.** From Android 12 a user can grant approximate location only. The platform then reports a deliberately coarsened position whose accuracy is at least its coarse-accuracy setting, 2,000 m by default in AOSP. Any lower limit would silently switch ALA off for everyone who makes that privacy choice, so 2,000 m is the smallest workable value. Device case DE-04 confirms the value phones actually report.
2. **The ALA search area barely moves.** ALA counts records within 8 km of the query point. If the query point is d km from the true position, the two circles share (2/π)(acos x − x√(1 − x²)) of their area, with x = d / 16 km:

| Offset d | 10 m | 100 m | 500 m | 1 km | 2 km | 3.24 km |
|---|---|---|---|---|---|---|
| Shared search area | 99.9% | 99.2% | 96.0% | 92.0% | 84.1% | 74.4% |

Even at the limit's 95% radius, about three-quarters of the searched area is the true neighbourhood. Counts then pass through `ln(1 + min(count, 50))`, which compresses differences further, so rankings move much less than the raw overlap. Outdoor GPS fixes of a few metres make the effect negligible.

The limit decides only whether ALA context is fetched. The Observe status shows the accuracy ("±2000 m"), and the saved record is still coarsened to the 0.001° grid.

## Freshness limit: 60 s

`LocationTracker` requests updates every 2.5 s, so a fix older than 60 s means about 24 missed updates: the signal is lost (indoors, dense canopy) or the fix is a cached last-known location. Walking at 1.4 m/s covers about 84 m in 60 s, less than one 111 m grid cell, so a fix at the limit still lands in the right cell or its neighbour. Cycling (300 m) or driving (830 m) would not, but neither is a field-observation condition. Age is measured on the monotonic `elapsedRealtimeNanos` clock, so wall-clock changes cannot make a stale fix look fresh.

## Storage grid: 0.001°

Saved observations and ALA queries round to three decimal places. At Parkville (37.8° S) a cell is about 111 m north–south by 88 m east–west (about 0.98 ha), and rounding moves a point by at most 71 m. That costs ALA almost nothing (99.4% shared area at 71 m) and stops saved records revealing a plant's exact position. It reduces precision but does not anonymise; see [data handling](../PRIVACY_POLICY.md).

## Defects and findings

### Defect: an aged-out fix stayed "ready"

The status was set when a fix arrived and never revisited. Under canopy or indoors, fixes stop; after 60 s `snapshotForObservation()` correctly returns nothing at the shutter, so the capture skipped ALA while the screen still said "Device location ready". Switching off the provider of the last fix had the same effect while another provider stayed on.

**Fix:** after each accepted fix, `LocationTracker` schedules a re-check for the moment the fix would expire (`LocationFreshnessPolicy.millisUntilStale`, plus 50 ms because a fix is still usable at exactly 60 s). If no newer fix has replaced it, or its provider is switched off, the status changes to "Waiting for a new device location. A capture now would skip ALA." The next fix restores "Device location ready". Tested in `LocationFreshnessPolicyTest` and `FloraGuideViewModelTest`; the timing itself needs the device check DE-16.

### Defect: map pins could show finer coordinates than storage

The map grouped observations by their stored coordinates as given. Records created by the app are already coarse, but imported legacy records or cloud documents could carry more precision, and the map would pin them exactly. `observationMapLocations()` now re-coarsens every point, so pins never show more precision than storage. Coarsening is idempotent, so existing pins do not move. Tested in `ObservationMapLocationsTest` and `GeoPointTest`.

### Finding for the map owner: "Following your location" on the Field Guide

With no saved observations, the Field Guide map shows the last location from Observe. Location tracking stops when the user leaves Observe, so the point never updates, yet the overlay reads "Following your location". "Last device location" would be accurate. Not changed here because the map UI belongs to the map workstream.

### Known behaviour: providers enabled after Observe opens

`LocationTracker` registers the providers that are on when tracking starts. Turning GPS on afterwards, with network location already on, adds nothing until the user taps **Enable / refresh**, which restarts tracking. Turning location off entirely is reported immediately.

## Device measurement procedure

Use a debug build; [device testing evidence](DEVICE_TESTING_EVIDENCE.md#evidence-helper) explains installation and the helper script. Run from the repository root.

1. Choose reference points and read each one's coordinates from a satellite map at maximum zoom: open sky (e.g. South Lawn), under tree canopy (System Garden), beside a tall building, and indoors near a window.
2. At each point, holding the phone still: `tools/device-evidence.sh log-start`, open Observe, wait two minutes, capture once, then `tools/device-evidence.sh log-save <phone>-loc-<point>`, where `<point>` is `opensky`, `canopy`, `building` or `indoors`. The `loc-` prefix keeps these logs apart from the motion calibration logs saved in the same folder.
3. Run `python tools/location-accuracy.py docs/evidence/device/<model>/<phone>-loc-<point>.log --ref LAT,LON --label "<phone>, <point>"` and paste the output under Results.
4. At the open-sky point, repeat with the open-sky reference once with Wi-Fi and mobile data off (GPS only, `<phone>-loc-gpsonly`) and once with approximate location granted (`<phone>-loc-approx`).

Acceptance criteria:

- Outdoors: median reported GPS accuracy of 10 m or better, and a first fresh GPS fix within 30 s of opening Observe.
- Honest accuracy: 50–85% of GPS fixes fall within their reported accuracy (68% expected).
- Network and approximate fixes up to 2,000 m are accepted and shown with their accuracy.
- Indoors without GPS: the status changes to "Waiting for a new device location" within about 61 s of the last fix, and a capture then records no location.

### Results

Pending device runs. Paste each session's summary here; logs stay under `docs/evidence/` (git-ignored) because they contain coordinates.

| Phone | Point / mode | Summary |
|---|---|---|
| Phone A | Open sky | not run |
| Phone A | Tree canopy | not run |
| Phone A | Beside a building | not run |
| Phone A | Indoors | not run |
| Phone A | GPS only; approximate | not run |
| Phone B | Same five sessions | not run |

## Remaining limitations

- Until the results are filled in, the limits are justified analytically, not measured.
- Reference points read from a satellite map carry a few metres of uncertainty. That is negligible against the 2 km limit but blurs the "within reported accuracy" share for GPS fixes.
- The approximate-location argument relies on the AOSP default; a manufacturer could configure a coarser value, which DE-04 would reveal.
