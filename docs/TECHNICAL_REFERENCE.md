# Technical reference

## Toolchain

The version catalogue currently pins:

- Android Gradle Plugin 8.13.2;
- Gradle 8.13;
- Kotlin 2.2.21;
- compile/target SDK 36;
- minimum SDK 26;
- Core KTX 1.18.0;
- Lifecycle 2.10.0;
- Activity Compose 1.13.0;
- Compose BOM 2026.06.01;
- CameraX 1.6.1;
- ExifInterface 1.4.2;
- Coroutines 1.10.2.

Versions are centralised in `gradle/libs.versions.toml`. Update them through a reviewed dependency change rather than editing versions throughout the project.

## Pl@ntNet identification request

Image recognition is a cloud call to the Pl@ntNet v2 API:

```text
POST https://my-api.plantnet.org/v2/identify/all
    ?api-key=<key>&nb-results=8&lang=en
multipart/form-data:
    organs=auto
    images=<captured JPEG>
```

Only `results[].score` and `results[].species.scientificNameWithoutAuthor` (plus the optional
`commonNames`) are consumed. Scores are per-species confidences and do **not** sum to 1;
normalisation is the ranking use case's job.

`nb-results=8` bounds the downstream ALA fan-out, because one occurrence request is issued per
candidate. A free key allows 500 identifications a day; `remainingIdentificationRequests` is
logged after every call so the team can see the budget before a demo.

The key is read from `plantnet.api.key` in the git-ignored `local.properties` and exposed through
`BuildConfig`. It is therefore present inside the APK: acceptable for coursework, not secret
storage. Without a key the app builds and runs on the labelled demo adapter instead.

Measured on 2026-09-07 with a 1123x1600, 963 KB JPEG: HTTP 200 in ~3.4 s.

```bash
./tools/verify-plantnet.sh path/to/plant.jpg
```

## ALA occurrence request

The baseline client performs a read-only count query:

```text
GET https://api.ala.org.au/occurrences/occurrences/search
    ?q=scientificName:"<candidate scientific name>"
    &lat=<latitude>
    &lon=<longitude>
    &radius=8
    &pageSize=0
    &facet=false
```

Only `totalRecords` is required. `pageSize=0` avoids downloading occurrence rows. Requests use connection/read timeouts and run concurrently for the candidate set.

The exact endpoint, accepted parameters and taxonomy behaviour must be reverified against the current ALA documentation before final submission. A successful exact-name query does not guarantee that the name is the accepted taxon; a zero result may indicate a synonym or mapping problem rather than absence.

The optional script below checks three representative names and requires `curl` and `jq`:

```bash
./tools/verify-ala.sh
```

## Fusion formula

```text
raw(s) = α log(Pimage(s) + ε)
       + β log(Plocation(s) + ε)
       + γ log(Pseason(s) + ε)
       + δ log(Phabitat(s) + ε)
```

Current weights are `1.00`, `0.75`, `0.35` and `0.45`. Nearby records use additive smoothing with `λ = 3`, followed by a numerically stable softmax across the candidate set.

These constants are prototype values. The final report should explain how weights were selected, report sensitivity or validation results, and avoid calling the output calibrated confidence unless calibration is actually performed.

## Evaluation measures

### Identification

- candidate recall at K;
- image-only Top-1 and Top-3 accuracy;
- fused Top-1 and Top-3 accuracy;
- confusion by species;
- unknown/genus fallback performance.

### Ablation

Compare the full model with:

- no location prior;
- no season prior;
- no habitat prior;
- different search radii;
- different fusion weights.

### Performance

- image preprocessing and inference latency;
- ALA request latency and success rate;
- time until image-only result;
- time until fused result;
- cache hit rate;
- energy or sampling considerations for sensors.

### Robustness

- precise, approximate and denied location;
- live, slow, partial and unavailable network;
- devices with missing optional sensors;
- poor light and motion;
- process restart and offline observation queue.

### Usability

- task completion rate and time;
- whether users understand the ranking explanation;
- whether users can correct a result;
- whether users understand live versus fallback data;
- accessibility with large fonts and TalkBack.
