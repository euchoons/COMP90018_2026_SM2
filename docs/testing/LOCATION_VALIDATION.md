# Location validation

Owner: Mason Lu. Project plan deliverable: *"Location — Validate GPS accuracy and support the location data consumed by the campus map."*

This document checks the location path end to end: what the reported accuracy means, why the 2 km / 60 s eligibility limits and the 0.001° storage grid suit the app, the defects found and fixed, and the device measurements that validate the limits on real phones. It supports rubric criteria 6 (Sensors), 7 (Connectivity), 9 (Technical depth) and 19 (Impact).

Related: the [missing-context policy](../technical/MISSING_CONTEXT_POLICY.md) for how a usable location feeds ALA ranking, [data handling](../PRIVACY_POLICY.md) for coarsening and debug logging, and [device testing evidence](DEVICE_TESTING_EVIDENCE.md) for the failure-path checks.

## Summary

| Area | Result | Commit |
|---|---|---|
| Eligibility limits | Justified: 2 km admits Android's approximate location and keeps at least 84% of the 8 km ALA search area; 60 s keeps walking drift below one grid cell. Device measurements pending. | — |
| Approximate location | Defect fixed: approximate fixes arrive only about every 10 min, so the 60 s limit made approximate location unusable (DE-04). They now keep for 15 min and feed ALA, but cannot be saved as a map pin. | `fix(location): use approximate location for ALA without pinning it` |
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

1. **Approximate location must stay usable.** From Android 12 a user can grant approximate location only. The platform then reports a deliberately coarsened position whose accuracy is at least its coarse-accuracy setting, 2,000 m by default in AOSP. Any lower limit would silently switch ALA off for everyone who makes that privacy choice, so 2,000 m is the smallest workable value. Device case DE-04 confirms the value phones actually report. Approximate fixes also arrive only about every 10 minutes, so they have their own freshness limit, described in the next section.
2. **The ALA search area barely moves.** ALA counts records within 8 km of the query point. If the query point is d km from the true position, the two circles share (2/π)(acos x − x√(1 − x²)) of their area, with x = d / 16 km:

| Offset d | 10 m | 100 m | 500 m | 1 km | 2 km | 3.24 km |
|---|---|---|---|---|---|---|
| Shared search area | 99.9% | 99.2% | 96.0% | 92.0% | 84.1% | 74.4% |

Even at the limit's 95% radius, about three-quarters of the searched area is the true neighbourhood. Counts then pass through `ln(1 + min(count, 50))`, which compresses differences further, so rankings move much less than the raw overlap. Outdoor GPS fixes of a few metres make the effect negligible.

The limit decides only whether ALA context is fetched. The Observe status shows the accuracy ("±2000 m"), and the saved record is still coarsened to the 0.001° grid.

## Freshness limit: 60 s

`LocationTracker` requests updates every 2.5 s, so a fix older than 60 s means about 24 missed updates: the signal is lost (indoors, dense canopy) or the fix is a cached last-known location. Walking at 1.4 m/s covers about 84 m in 60 s, less than one 111 m grid cell, so a fix at the limit still lands in the right cell or its neighbour. Cycling (300 m) or driving (830 m) would not, but neither is a field-observation condition. Age is measured on the monotonic `elapsedRealtimeNanos` clock, so wall-clock changes cannot make a stale fix look fresh.

**Approximate location: 15 minutes.** Android sends an app with approximate permission only about one fix every 10 minutes, already blurred to a grid of about 2 km. Under the 60 s limit each fix expired long before the next arrived, so approximate location never became usable (DE-04). Approximate fixes now stay usable for 15 minutes, the 10-minute delivery interval plus a margin. A walker covers about 1.3 km in that time, less than the 2 km blur, so an older approximate fix is no worse than a new one.

## Storage grid: 0.001°

Saved observations and ALA queries round to three decimal places. At Parkville (37.8° S) a cell is about 111 m north–south by 88 m east–west (about 0.98 ha), and rounding moves a point by at most 71 m. That costs ALA almost nothing (99.4% shared area at 71 m) and stops saved records revealing a plant's exact position. It reduces precision but does not anonymise; see [data handling](../PRIVACY_POLICY.md).

## Defects and findings

### Defect: an aged-out fix stayed "ready"

The status was set when a fix arrived and never revisited. Under canopy or indoors, fixes stop; after 60 s `snapshotForObservation()` correctly returns nothing at the shutter, so the capture skipped ALA while the screen still said "Device location ready". Switching off the provider of the last fix had the same effect while another provider stayed on.

**Fix:** after each accepted fix, `LocationTracker` schedules a re-check for the moment the fix would expire (`LocationFreshnessPolicy.millisUntilStale`, plus 50 ms because a fix is still usable at exactly 60 s). If no newer fix has replaced it, or its provider is switched off, the status changes to "Waiting for a new device location. A capture now would skip ALA." The next fix restores "Device location ready". Tested in `LocationFreshnessPolicyTest` and `FloraGuideViewModelTest`; the timing itself needs the device check DE-16.

### Defect: map pins could show finer coordinates than storage

The map grouped observations by their stored coordinates as given. Records created by the app are already coarse, but imported legacy records or cloud documents could carry more precision, and the map would pin them exactly. `observationMapLocations()` now re-coarsens every point, so pins never show more precision than storage. Coarsening is idempotent, so existing pins do not move. Tested in `ObservationMapLocationsTest` and `GeoPointTest`.

### Defect: approximate location never became usable

On Phone A, Observe never reported a usable fix with approximate location granted (DE-04). The 60 s limit rejected each approximate fix long before the next one arrived. Approximate fixes now keep for 15 minutes in `LocationFreshnessPolicy`, feed the ALA query, and show as "Approximate location ready · ±2000 m". A fix blurred by up to about 2 km is fine for an 8 km search but would pin the plant in the wrong place, so a capture with only approximate location cannot be saved: Results explains why, and Observe offers **Use precise location**, which asks Android to upgrade the permission. Tested in `LocationFreshnessPolicyTest` and `FloraGuideViewModelTest`; DE-04 needs a re-run.

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

Phone A is a OnePlus PGP110 on Android 15, running a debug build of `e0bb84a`, measured on the Parkville campus on 2026-10-05. Each reference point was long-pressed in Google Maps at the standing point and cross-checked against a feature in the satellite image. Logs stay under `docs/evidence/` (git-ignored) because they contain coordinates. Accuracy and error are given as median / 95th percentile, and "within" is the share of fixes whose error is inside their reported accuracy.

| Phone | Point / mode | Summary |
|---|---|---|
| Phone A | Open sky | GPS: 65 fixes, reported accuracy 4 / 4 m, error 0 / 1 m, 100% within; first fresh GPS fix after 3.2 s. Network: 12 fixes, 100 / 129 m reported, 31 m error |
| Phone A | Tree canopy | GPS: 51 fixes, 4 / 4 m reported, error 2 / 4 m, 94% within; first fresh GPS fix within 3.0 s. Network: 14 fixes, 64 / 100 m reported, 10 m error |
| Phone A | Beside a building | GPS: 43 fixes, 10 / 10 m reported, error 4 / 7 m, 81% within; a fresh GPS fix was already available. Network: 9 fixes, 34 / 135 m reported, 8 m error |
| Phone A | Indoors | GPS: 45 fixes, 13 / 55 m reported, error 31 / 36 m, 8% within. Network: 21 fixes, 100 m reported, 6 m error. DE-16: 1 stale report and a capture without location |
| Phone A | GPS only; approximate | GPS only: 44 GPS fixes, 4 / 4 m reported, error 0 / 1 m, 100% within; first fresh GPS fix after 1.1 s. 8 network fixes also arrived, so Wi-Fi probably stayed on in airplane mode. Approximate: not recorded; see DE-04 (FAIL) |
| Phone B | Same five sessions | not run |

Against the acceptance criteria, Phone A:

- **Outdoors: passes.** The median reported GPS accuracy is 4–10 m, and the first fresh GPS fix arrives within 3.2 s.
- **Honest accuracy: mixed.** Beside the building, 81% of GPS fixes fall within their reported accuracy, inside the expected band. In open sky and under canopy 94–100% do, because the phone reports a flat 4 m, more cautious than its actual error. Indoors only 8% do: GPS claims about 13 m but is about 31 m off, while network fixes claim 100 m and are about 6 m off.
- **Network and approximate fixes: partly met.** Network fixes of about 100 m were accepted, but approximate fixes never became usable (DE-04 FAIL). Fixed in `63f3fb1`; the approximate session needs a re-run.
- **Indoors without GPS: passes** (DE-16).

## Remaining limitations

- Phone A's results are in, but Phone B and the approximate session are still to be measured, so the limits are only partly confirmed by measurement.
- Indoors, reported GPS accuracy is optimistic (Phone A: about 13 m claimed, about 31 m actual), so it is a poor guide to which indoor fix is better. The impact is small: stored coordinates use a grid of about 100 m, and ALA searches 8 km.
- Reference points read from a satellite map carry a few metres of uncertainty. That is negligible against the 2 km limit but blurs the "within reported accuracy" share for GPS fixes.
- The approximate-location argument relies on the AOSP default; a manufacturer could configure a coarser value, which DE-04 would reveal.
