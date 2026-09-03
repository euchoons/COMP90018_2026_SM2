# Potential contribution plan template

The submitted contribution breakdown must fit **one page**. Replace all placeholders with real names and agreed deliverables. Delete unused rows and combine workstreams if the group has fewer than six members.

## Submission table

| Member | Primary ownership and concrete deliverables | Tests, evidence and secondary responsibility |
|---|---|---|
| `[Name 1]` | CameraX capture; accelerometer/gyroscope stability pipeline; light/heading availability and fallback; physical-device calibration. | Sensor/device test matrix; motion and missing-sensor video evidence; review UI permission flow. |
| `[Name 2]` | TensorFlow Lite classifier; bitmap preprocessing; Top-K output; model-label to internal/ALA taxon mapping; unknown/genus fallback. | Model accuracy and latency evaluation; mapping tests; review fusion algorithm. |
| `[Name 3]` | ALA client and taxonomy queries; Room cache and freshness; Firebase observation/photo repository; offline queue and security rules. | Connectivity/failure tests and telemetry; cloud evidence; review privacy/data model. |
| `[Name 4]` | Context-fusion algorithm; prior smoothing and weight selection; cue ablations; labelled evaluation dataset and analysis. | Top-1/Top-3, sensitivity and latency results; unit tests; review model integration. |
| `[Name 5]` | Compose design system; Home/Observe/Results/Field Guide integration; accessibility, language, loading/error states and usability study. | Task-based UI evidence; TalkBack/large-font review; report/video UX material; review sensor feedback. |
| `[Name 6, if applicable]` | Selected showcase extension—campus map **or** team mission; privacy-preserving aggregation; release integration and final evidence matrix. | End-to-end tests; build/release checklist; commit-log export; review cloud integration and demo. |

## How to adapt by group size

**Four members:** combine Camera/Sensors with UI; combine Model with Fusion; keep Connectivity/Cloud as one stream; make the fourth member Integration/Evaluation/Showcase. Ensure each still owns code and tests.

**Five members:** use the first five streams and distribute the selected showcase extension across Cloud and UI, with one named integration owner.

**Six members:** retain all six streams, but avoid isolating the sixth member as documentation-only work.

## Balance rules

Each member should have:

- at least one substantial implementation deliverable;
- tests or measurable evidence for that deliverable;
- a named secondary review or integration responsibility;
- a report/video section they can defend;
- commits that match the contribution description.

Avoid:

- “help with coding” or “assist all members”;
- one person owning all architecture and integration;
- one person doing only documentation, slides or testing;
- assigning a feature without its failure handling and evidence;
- promising work that is absent from the rubric plan.

## Final one-page check

- [ ] Group size and all names are correct.
- [ ] Every line describes concrete outputs, not generic effort.
- [ ] Workload appears comparable after accounting for difficulty and integration risk.
- [ ] Every promised feature has a primary owner.
- [ ] Every member has implementation, test/evidence and review responsibility.
- [ ] The table agrees with `RUBRIC_PLAN.md` and the roadmap.
- [ ] The formatted PDF page is exactly one page.
