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

The API requests eight results; the current ViewModel keeps the first five for both image-only
and live ranking. Each candidate requires a name-match request followed by an occurrence
request when resolved (before retries). `remainingIdentificationRequests` is
logged after every call so the team can see the budget before a demo.

The key is read from `plantnet.api.key` in the git-ignored `local.properties` and exposed through
`BuildConfig`. It is therefore present inside the APK: acceptable for coursework, not secret
storage. Without a key the app builds, but live identification reports the missing key;
the explicit guided demo remains available.

Measured on 2026-09-07 with a 1123x1600, 963 KB JPEG: HTTP 200 in ~3.4 s.

```bash
./tools/verify-plantnet.sh path/to/plant.jpg
```

## ALA occurrence request

The client first resolves an exact species-level match:

```text
GET https://api.ala.org.au/namematching/api/searchByClassification
    ?scientificName=<candidate scientific name>
```

It then performs a read-only count query with the resolved ID:

```text
GET https://api.ala.org.au/occurrences/occurrences/search
    ?q=taxonConceptID:"<resolved taxon ID>"
    &lat=<latitude>
    &lon=<longitude>
    &radius=8
    &pageSize=0
    &facet=false
```

Only `totalRecords` is required from the occurrence response. `pageSize=0` avoids downloading
occurrence rows. Both requests share cancellable transport, size limits, no redirects and
connection/read timeouts. Candidate lookups run concurrently with a shared per-candidate
timeout/retry budget. `UNRESOLVED_TAXON` is not retried and never becomes a zero count.

Matching accepts an exact or canonical match (authorship or formatting only) on the queried
name or an objective synonym of it, counted under ALA's accepted species, and excludes
fuzzy, higher-rank, subjective, pro parte and misapplied matches. It is
not complete synonym resolution. A successful zero is a zero occurrence-query result,
not proof of ecological absence. See [missing-context policy](MISSING_CONTEXT_POLICY.md).

The optional diagnostic script below uses the older scientific-name text queries, not the
app's full name-resolution path. It checks three names and requires `curl` and `jq`:

```bash
./tools/verify-ala.sh
```

## Fusion formula

### Current live rule (trained in #20)

```text
support(s) = ln(1 + min(count(s), 50)) / ln(51)
season(s) = f if the photo is a flower and every documented flowering month of s
            is more than one month from the capture month, otherwise 1
weight(s) = imageScore(s) * (1 + c * support(s)) * season(s)
relativeScore(s) = weight(s) / sum(weight)
```

Training set `c = 0.5` (its design bound) and `f = 1.0`, with an 8 km radius; see
[fusion training](FUSION_EVALUATION.md). Geographic support is enabled only for complete live
counts; otherwise it is neutral for every candidate. Zero counts give a neutral multiplier of 1;
the maximum is 1.5. The flowering check needs Pl@ntNet's predicted organ to be a flower with a
score of at least 0.5, and species without documented months are never out of season. The
months come from VicFlora flowering statements for 75 common Parkville species (see the
missing-context policy). With `f = 1.0` the check is shown but does not reorder. Habitat is not
an input to `live()`. On held-out photos the trained rule changed no Top-1 answer, so it is not
evidence that context improves identification.

### Synthetic guided demo only

```text
raw(s) = α log(max(Pimage(s), ε))
       + β log(max(Plocation(s), ε))
       + γ log(max(Pseason(s), ε))
       + δ log(max(Phabitat(s), ε))
```

Demo weights are `1.00`, `0.75`, `0.35` and `0.45`. Nearby records use additive smoothing with `λ = 3`, followed by a numerically stable softmax across the candidate set. This is not the current live formula.

These constants are prototype values. The final report should explain how weights were selected, report sensitivity or validation results, and avoid calling the output calibrated confidence unless calibration is actually performed.

## Evaluation measures

The offline fusion evaluation reports candidate recall, Top-1 and Top-3, cue activation and the
radius comparison; see [fusion training](FUSION_EVALUATION.md).

### Identification

- candidate recall at K;
- image-only Top-1 and Top-3 accuracy;
- fused Top-1 and Top-3 accuracy;
- confusion by species;
- unknown/genus fallback performance;
- share of live captures with complete ALA context, where the geographic boost was
  applied at all (one unresolved or failed candidate disables it for the capture);
- share of flower photos where the flowering cue changed a multiplier, with the cases it
  helped or harmed.

### Ablation

Compare the current live model with image-only and any agreed geographic-support variants.
The flowering cue can be ablated with the bundled VicFlora table. Habitat is not a live cue
(#17), so there is nothing to ablate. Changing demo weights does not evaluate live behaviour. Compare with:

- no location prior;
- no flowering cue;
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
