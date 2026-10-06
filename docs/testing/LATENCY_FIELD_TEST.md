# Latency field test (#53)

This test measures how long a live capture waits for its results on a real phone. Each live result writes one `FloraGuide-Latency` line to logcat, so a tester only takes photos, notes the network, and exports the log at the end.

## What you need

- An Android phone with USB debugging enabled, a USB cable, and a computer with this repository and `adb`.
- `local.properties` with the Pl@ntNet key (`plantnet.api.key` or `PLANTNET_API_KEY`). Without it, live identification fails.
- A FloraGuide account to sign in with. Photo upload needs a signed-in session.
- About 20 identifications per network tested, from Pl@ntNet's shared daily quota of 500.

## 1. Install the test build

```bash
git fetch origin
```

```bash
git switch feat/53-latency-logging
```

```bash
./gradlew installDebug
```

If more than one device is connected, set `ANDROID_SERIAL` to the phone's serial from `adb devices` first. Installing over an existing FloraGuide keeps its data.

Then enlarge the phone's log buffer, so the walk does not push earlier records out:

```bash
adb logcat -G 16M
```

## 2. Take the photos

1. Sign in on the Account screen, and allow camera access and precise location.
2. Take about 20 captures on each network you were asked to test. For mobile data, turn Wi-Fi off first. If you test both, note the time you switch.
3. Photograph different plants: some garden plants, and some common natives or weeds such as eucalypts, wattles, grasses or dandelions.
4. For each capture, open Observe, take the photo, agree to send it, and wait until the ALA result appears, with the app open and the screen on. You do not need to save the observation.
5. If a step fails, retry as usual. The log labels retries, and they are analysed separately.

Keep people, faces, number plates and house numbers out of the photos.

## 3. Export the log

Connect the phone and run this from the repository root:

```bash
adb logcat -d -v time -s FloraGuide-Latency FloraGuide-PlantNet FloraGuide-ALA FloraGuide-Storage > latency-log.txt
```

The log contains timings, HTTP status codes, Pl@ntNet's first choices and ALA outcomes. It contains no coordinates, account details or API keys.

## 4. Fill in the record sheet and send it

Copy the sheet below into a comment on #53, fill it in, and attach `latency-log.txt`. The timings come from the log; the per-capture table is optional, but it helps judge whether location evidence changed the answer for the better.

```markdown
### Latency field test

- Tester:
- Date:
- Phone model and Android version:
- Build commit (`git rev-parse --short HEAD`):
- Area (suburb or campus, no street address):

| Block | Network (for example UniWireless, home Wi-Fi, carrier name) | Start time | End time | Captures |
|---|---|---|---|---|
| A | Wi-Fi (if tested): | | | |
| B | Mobile data (if tested): | | | |

| # | Time | What you photographed (common name if known) | Final first choice looked right? (yes / no / unsure) | Notes (errors, retries, very slow) |
|---|---|---|---|---|
| 1 | | | | |
| 2 | | | | |
```
