#!/usr/bin/env python3
"""Summarise FloraGuide location fixes logged by a debug build.

Collect a log after an Observe session with

    adb logcat -d -s FloraGuide-Location:I > fixes.log      (or: tools/device-evidence.sh log-save NAME)

then run

    python tools/location-accuracy.py fixes.log --ref -37.79630,144.96140 --label "Phone A, South Lawn"

and paste the Markdown output into docs/testing/LOCATION_VALIDATION.md. Coordinates are used only to
measure distances to the reference point and are never printed.
"""
import argparse
import math
import re
from collections import defaultdict

FIELD = re.compile(r"(\w+)=(\S+)")
EARTH_RADIUS_M = 6_371_008.8
FRESH_MS = 5_000  # older fixes are cached last-known locations, not live deliveries


def parse(path):
    events = []
    with open(path, encoding="utf-8", errors="replace") as handle:
        for line in handle:
            if "event=" in line:
                fields = dict(FIELD.findall(line.split("FloraGuide-Location", 1)[-1]))
                if "t" in fields:
                    events.append(fields)
    return events


def number(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None  # "null" accuracy


def percentile(values, fraction):
    ordered = sorted(values)
    if not ordered:
        return None
    position = (len(ordered) - 1) * fraction
    low = math.floor(position)
    high = min(low + 1, len(ordered) - 1)
    return ordered[low] + (ordered[high] - ordered[low]) * (position - low)


def distance_m(lat1, lon1, lat2, lon2):
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    a = (math.sin((phi2 - phi1) / 2) ** 2
         + math.cos(phi1) * math.cos(phi2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2)
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(a))


def fmt(value, digits=0):
    return "—" if value is None else f"{value:.{digits}f}"


def pair(values, digits=0):
    return f"{fmt(percentile(values, 0.5), digits)} / {fmt(percentile(values, 0.95), digits)}"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("log")
    parser.add_argument("--ref", help="surveyed reference point as LAT,LON for error measurement")
    parser.add_argument("--label", default="Location session")
    args = parser.parse_args()
    ref = tuple(float(part) for part in args.ref.split(",")) if args.ref else None
    events = parse(args.log)

    fixes = defaultdict(list)
    for event in events:
        if event.get("event") == "fix":
            fixes[event.get("provider", "?")].append(event)

    print(f"### {args.label}\n")
    print("| Provider | Fixes (fresh) | Accepted | Reported accuracy m, median / 95th "
          "| Update interval s, median | Error m, median / 95th | Error within reported accuracy |")
    print("|---|---|---|---|---|---|---|")
    for provider, rows in sorted(fixes.items()):
        fresh = [r for r in rows if (number(r.get("ageMs")) or 0) <= FRESH_MS]
        accuracies = [a for a in (number(r.get("accuracyM")) for r in fresh) if a is not None]
        times = [number(r["t"]) for r in fresh]
        intervals = [(b - a) / 1000 for a, b in zip(times, times[1:])]
        errors, within = [], 0
        if ref:
            for r in fresh:
                lat, lon, accuracy = number(r.get("lat")), number(r.get("lon")), number(r.get("accuracyM"))
                if lat is None or lon is None:
                    continue
                error = distance_m(lat, lon, *ref)
                errors.append(error)
                within += accuracy is not None and error <= accuracy
        share = f"{100 * within / len(errors):.0f}%" if errors else "—"
        accepted = sum(r.get("accepted") == "true" for r in rows)
        print(f"| {provider} | {len(rows)} ({len(fresh)}) | {accepted} | {pair(accuracies)} "
              f"| {fmt(percentile(intervals, 0.5), 1)} | {pair(errors)} | {share} |")

    first_fix, first_gps, start = [], [], None
    for event in events:
        kind, t = event.get("event"), number(event["t"])
        if kind == "start":
            start = {"t": t, "fix": None, "gps": None}
            first_fix.append(start)
        elif kind == "fix" and start and (number(event.get("ageMs")) or 0) <= FRESH_MS:
            if start["fix"] is None and event.get("accepted") == "true":
                start["fix"] = (t - start["t"]) / 1000
            if start["gps"] is None and event.get("provider") == "gps":
                start["gps"] = (t - start["t"]) / 1000
    stale = sum(e.get("event") == "stale" for e in events)
    captures = [e.get("usable") == "true" for e in events if e.get("event") == "capture"]
    print()
    print(f"- Starts: {len(first_fix)}. Time to first usable fix (s): "
          f"{', '.join(fmt(s['fix'], 1) for s in first_fix) or '—'}. "
          f"Time to first fresh GPS fix (s): {', '.join(fmt(s['gps'], 1) for s in first_fix) or '—'}.")
    print(f"- Stale reports: {stale}. Captures: {sum(captures)} with a usable location, "
          f"{len(captures) - sum(captures)} without.")
    if ref:
        print("- Error is measured against the reference point, which carries its own uncertainty of a few metres.")


if __name__ == "__main__":
    main()
