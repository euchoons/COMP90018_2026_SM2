#!/usr/bin/env python3
"""Summarise FloraGuide stability-gate logs for docs/technical/MOTION_STABILITY_CALIBRATION.md.

Record one condition per log with a debug build: run tools/device-evidence.sh log-start, hold the
phone in that condition for about 30 s, then run tools/device-evidence.sh motion-save A-braced.
Afterwards

    python tools/motion-calibration.py docs/evidence/device/<model>/A-*.log

prints one Markdown row per condition. The first seconds of each log are skipped as setup time.
"""
import argparse
import math
import os
import re

FIELD = re.compile(r"(\w+)=(\S+)")
THRESHOLD = 0.6  # MotionStabilityEstimator.STABLE_THRESHOLD
DEGREES = 180 / math.pi


def parse(path, skip_seconds):
    samples = []
    with open(path, encoding="utf-8", errors="replace") as handle:
        for line in handle:
            if "event=motion" not in line:
                continue
            fields = dict(FIELD.findall(line.split("FloraGuide-Motion", 1)[-1]))
            samples.append({
                "t": float(fields["t"]) / 1000,
                "acc": float(fields["accDev"]),
                "gyro": float(fields["gyro"]) * DEGREES,
                "target": float(fields["target"]),
                "score": float(fields["score"]),
                "open": fields["open"] == "true",
            })
    if samples:
        start = samples[0]["t"] + skip_seconds
        samples = [s for s in samples if s["t"] >= start]
    return samples


def percentile(values, fraction):
    ordered = sorted(values)
    if not ordered:
        return math.nan
    position = (len(ordered) - 1) * fraction
    low = math.floor(position)
    high = min(low + 1, len(ordered) - 1)
    return ordered[low] + (ordered[high] - ordered[low]) * (position - low)


def lags_ms(samples):
    """Delay from the raw target crossing the threshold to the smoothed gate following it."""
    unlock, lock = [], []
    for i in range(1, len(samples)):
        if samples[i]["open"] == samples[i - 1]["open"]:
            continue
        opening = samples[i]["open"]
        j = i
        while j > 0 and (samples[j - 1]["target"] >= THRESHOLD) == opening:
            j -= 1
        (unlock if opening else lock).append((samples[i]["t"] - samples[j]["t"]) * 1000)
    return unlock, lock


def fmt(value, digits=1):
    return "—" if value is None or math.isnan(value) else f"{value:.{digits}f}"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("logs", nargs="+")
    parser.add_argument("--skip", type=float, default=3.0, help="seconds of setup to drop from each log")
    args = parser.parse_args()

    print("| Condition | Samples (Hz) | Shake m/s², median / 95th | Rotation °/s, median / 95th "
          "| Score, median (5th–95th) | Gate open | Open/closed switches | Unlock / lock lag ms, median |")
    print("|---|---|---|---|---|---|---|---|")
    for path in args.logs:
        samples = parse(path, args.skip)
        name = os.path.splitext(os.path.basename(path))[0]
        if len(samples) < 2:
            print(f"| {name} | no motion samples | | | | | | |")
            continue
        duration = samples[-1]["t"] - samples[0]["t"]
        acc = [s["acc"] for s in samples]
        gyro = [s["gyro"] for s in samples]
        score = [s["score"] for s in samples]
        open_share = 100 * sum(s["open"] for s in samples) / len(samples)
        switches = sum(a["open"] != b["open"] for a, b in zip(samples, samples[1:]))
        unlock, lock = lags_ms(samples)
        print(f"| {name} | {len(samples)} ({fmt(len(samples) / duration, 0)}) "
              f"| {fmt(percentile(acc, 0.5), 2)} / {fmt(percentile(acc, 0.95), 2)} "
              f"| {fmt(percentile(gyro, 0.5))} / {fmt(percentile(gyro, 0.95))} "
              f"| {fmt(percentile(score, 0.5), 2)} ({fmt(percentile(score, 0.05), 2)}–{fmt(percentile(score, 0.95), 2)}) "
              f"| {open_share:.0f}% | {switches} "
              f"| {fmt(percentile(unlock, 0.5), 0)} / {fmt(percentile(lock, 0.5), 0)} |")


if __name__ == "__main__":
    main()
