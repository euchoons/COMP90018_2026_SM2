# Assignment 1 rubric planning draft

This file covers all 19 Assignment 2 criteria in order. It is deliberately more detailed than the final ten-page section so the team can review feasibility before compressing it. Replace owner placeholders, remove unsupported promises and update the status after group decisions.

## Material — 14 points

### 1. Material — Report and Video — 6 points

**How.** The final submission will include a criterion-numbered report and a demonstration video of no more than ten minutes. The video storyboard will follow the same 19-criterion evidence matrix as the report. It will show the app running on a physical phone, the guided explanation route, live camera and sensor feedback, image-only and fused rankings, ALA live/failure states, user confirmation, cloud persistence and the selected map or mission extension. The report will include build instructions, architecture, evaluation results, limitations and links to the video and repository.

**Why.** A criterion-indexed narrative makes every feature easy to trace and avoids relying on unsupported prose. Showing normal, denied-permission and offline paths demonstrates that the system functions as a mobile application rather than as a static prototype.

**Feasibility.** The baseline already has a repeatable guided route and a four-screen flow. The remaining work is to integrate final features, create an evidence checklist, record a clean physical-device run and edit the report/video together. A prerecorded real-device backup will reduce live-demo risk, but it will not replace evidence for omitted functionality.

**Planned evidence.** Time-coded video checklist, final-system screenshots, report cross-references and a pre-submission audit against all 19 criteria.

**Owner/reviewer.** `[Assign owner] / [Assign reviewer]`

### 2. Material — Screenshot — 2 points

**How.** The team will create a clean clone of the tagged submission commit, run the documented Gradle build in Android Studio and capture the Build/Console output showing a successful compile. The screenshot will include enough context to identify the project and task, without exposing local paths or personal information.

**Why.** A clean-clone build demonstrates that the submitted source is reproducible and not dependent on one member's cached IDE state or generated files.

**Feasibility.** The baseline includes the Gradle wrapper, version catalogue and build commands, while local machine files are excluded. The team will verify the same commit on at least two development machines before capturing final evidence.

**Planned evidence.** Android Studio console screenshot, tagged commit hash and the exact build command recorded in the report.

**Owner/reviewer.** `[Assign owner] / [Assign reviewer]`

### 3. Material — Commit log — 4 points

**How.** Development will occur in a canonical private Git repository using short-lived branches, reviewable pull requests and outcome-oriented commits. Each workstream will preserve meaningful implementation, test and documentation history. The team will export the final commit log in the required format and include all members' Git identities.

**Why.** A clear history demonstrates ongoing, attributable collaboration and supports the individual viva. It is stronger than a single code dump or a small number of vague final commits.

**Feasibility.** The cleaned baseline excludes generated files and includes contribution and pull-request guidance. The team must connect correct Git names/emails, avoid shared accounts, review contribution balance weekly and resolve large unreviewable commits early.

**Planned evidence.** Repository history, pull requests, exported logs, tags for verified milestones and contribution records linked to commits.

**Owner/reviewer.** `[Assign owner] / [Assign reviewer]`

### 4. Material — Itemised Contributions — 2 points

**How.** The final one-page contribution statement will list each member's actual implemented features, tests, integration work, evaluation, documentation and demonstration responsibilities. The team will maintain the Assignment 1 potential-contribution table throughout development and update it using merged pull requests and evidence.

**Why.** Specific outputs such as “implemented Room cache and failure tests” are attributable and verifiable, unlike “helped with coding”. Linking implementation, testing and evidence makes the division of work credible.

**Feasibility.** `CONTRIBUTION_PLAN_TEMPLATE.md` provides a balanced starting structure. The team will review the distribution at each milestone so that no member is limited to administrative work and no critical subsystem has only one knowledgeable person.

**Planned evidence.** One-page final table, matching commits/pull requests and each member's ability to explain their work during the viva.

**Owner/reviewer.** `[Assign owner] / [Assign reviewer]`

## Implementation — 44 points

### 5. Implementation — Quality — 10 points

**How.** FloraGuide will retain a layered Kotlin/Compose architecture with framework-independent domain logic, repository interfaces for the classifier/context/store, a single immutable UI state and constructor-injected dependencies. The team will centralise dependency versions, enforce formatting, review pull requests and add focused unit/integration tests for algorithms, network parsing, caching, persistence and error paths. Large bitmap, network and sensor work will remain outside composables and the main thread.

**Why.** These boundaries make responsibilities readable, reduce coupling and allow a real TensorFlow Lite model or Firebase store to replace prototype implementations without rewriting the user flow. Tests and explicit states make behaviour self-explanatory and maintainable.

**Feasibility.** The baseline already demonstrates the architecture and contains 11 JVM test cases. Remaining quality work includes final model/cloud implementations, cache tests, UI/instrumentation tests, consistent string resources, optional continuous integration and team code review. The team will avoid a late architectural rewrite by preserving current interfaces.

**Planned evidence.** Architecture diagram, representative code excerpts, test report, lint/build output, pull-request reviews and a clean-clone build.

**Owner/reviewer.** `[Assign owner] / [Assign reviewer]`

### 6. Implementation — Sensors — 10 points

**How.** The final app will use multiple sensors for distinct, user-visible roles. CameraX captures the observation. Accelerometer and gyroscope readings are fused and smoothed into a stability score that controls capture guidance. The ambient-light sensor provides photo-quality feedback. Accelerometer/magnetometer fusion calculates observation heading. GPS/network location supplies the geographic context for nearby species records. The app will check sensor availability at runtime and provide manual or unavailable states rather than failing.

**Why.** The sensors are not decorative readings. Raw motion values are transformed into a higher-level “stable enough to capture” context, gravity and magnetic vectors are composed into heading, and location changes external data selection. This demonstrates context derivation and heterogeneous context composition.

**Feasibility.** The baseline implements these adapters and UI states. The main remaining risk is device variation: thresholds and sensor availability must be calibrated on multiple physical phones. If a device lacks light, gyroscope or magnetometer data, core capture remains usable and the missing cue is explicitly omitted.

**Planned evidence.** Sensor data-flow diagram, physical-device tests on at least two models, motion/stability demonstrations, missing-sensor fallback, sampling/energy rationale and calibration observations.

**Owner/reviewer.** `[Assign owner] / [Assign reviewer]`

### 7. Implementation — Connectivity — 12 points

**How.** FloraGuide will use the Atlas of Living Australia read API to retrieve nearby occurrence counts for image candidates and a team-controlled cloud backend, proposed as Firebase, to store confirmed observation metadata and photos. Candidate requests will run concurrently with timeouts. A Room cache will reduce repeated lookups and expose freshness. The UI will distinguish live, partial, cached and offline sources, support retry and queue cloud observations when the network is unavailable. The project will not promise direct writes to ALA unless authentication is separately verified.

**Why.** Internet data materially changes the species ranking, while cloud storage enables observations to persist and support the selected map or mission. Explicit caching, partial responses, retry and offline behaviour address the variable connectivity expected in mobile systems.

**Feasibility.** The baseline already contains a read-only ALA client, concurrent repository, telemetry and fallback. Local observation storage is behind an interface. Early feasibility spikes must verify current ALA taxonomy queries, one Firebase photo/metadata write, security rules and offline replay before the report commits to the full backend.

**Planned evidence.** Live request logs, latency/success metrics, cached and offline demonstrations, Firebase console/security-rule evidence, process-restart upload test and a data-flow diagram.

**Owner/reviewer.** `[Assign owner] / [Assign reviewer]`

### 8. Implementation — Responsiveness — 6 points

**How.** The app will return local image candidates first, then update the ranking when ALA context arrives. Classification, network and storage work will run off the main thread through coroutines. Candidate requests will execute concurrently. Habitat changes will reuse existing context and rerank locally. Sensor updates will be smoothed and, where necessary, throttled to prevent excessive recomposition. Cached context will provide faster repeat observations.

**Why.** Progressive results prevent network latency from blocking the user, and local reranking gives immediate feedback. This architecture directly addresses real-time interaction rather than displaying a global loading screen for the entire pipeline.

**Feasibility.** The baseline already exposes image-only and fused states separately and uses asynchronous repositories. The final team must measure instead of assuming performance, particularly after adding bitmap preprocessing, TensorFlow Lite and cloud uploads. Large images will be downsampled and upload will not block confirmation.

**Planned evidence.** P50/P95 time to image-only result, time to fused result, inference and ALA latency, frame/recomposition observations, slow/offline network tests and responsiveness video.

**Owner/reviewer.** `[Assign owner] / [Assign reviewer]`

### 9. Implementation — Technical Depth — 6 points

**How.** Technical depth will come from several integrated algorithms: accelerometer/gyroscope stability fusion and smoothing; gravity/magnetic rotation-matrix heading; a Top-K visual candidate pipeline; smoothed geographic priors; log-linear image/location/season/habitat fusion; numerically stable softmax; cache and partial-network logic; unknown/genus fallback; and coarse-location privacy. The team will compare the full fusion model with image-only and cue-ablation baselines.

**Why.** These techniques transform low-level sensing and external data into higher-level context and make a non-trivial ranking decision. The planned evaluation tests whether the algorithm improves identification rather than treating complexity itself as success.

**Feasibility.** The core fusion and sensor algorithms already exist in prototype form and are unit-tested. The major remaining work is to replace heuristic model input, validate priors and tune weights on labelled data. Smoothing and unknown fallback prevent sparse or missing context from eliminating candidates.

**Planned evidence.** Formula and algorithm diagram, unit tests, ablation table, Top-1/Top-3 comparison, sensitivity analysis, latency measurements and privacy explanation.

**Owner/reviewer.** `[Assign owner] / [Assign reviewer]`

## User Interface — 26 points

### 10. User Interface — Appeal — 4 points

**How.** The final UI will use a consistent Material 3 design system, clear typography, spacing, card hierarchy and nature-oriented visual identity. The camera screen will prioritise the subject and capture guidance. Results will use concise ranking cards and evidence bars rather than dense raw data. The Field Guide and selected showcase extension will reuse the same components and visual language.

**Why.** The visual hierarchy supports the core tasks: capture, compare, understand and confirm. Evidence presentation is part of the product value, not decoration.

**Feasibility.** The baseline already contains reusable Compose components and a coherent four-screen layout. The group will conduct design review on multiple screen sizes, replace provisional imagery/icons where needed and avoid spending core implementation time on unrelated visual effects.

**Planned evidence.** Final screenshots, design-system summary, dark/light or contrast review, device-size comparison and usability feedback.

**Owner/reviewer.** `[Assign owner] / [Assign reviewer]`

### 11. User Interface — Guidelines — 6 points

**How.** FloraGuide will follow Android and Material patterns for runtime permissions, navigation, back behaviour, loading, retry, empty states, touch targets and system bars. Camera and location permissions will be requested in context with a rationale and a usable denied path. The team will externalise strings, provide content descriptions, test large font scaling and TalkBack, and maintain readable contrast.

**Why.** Platform-consistent behaviour reduces surprise and makes complex sensor/network states understandable. Accessibility and permission recovery are essential for a usable mobile application, not optional polish.

**Feasibility.** The baseline uses Compose Material components, explicit permission handling, back navigation and status messages. A formal accessibility and large-font pass remains. The team will create a checklist and test on at least one current Android phone and one smaller/older configuration.

**Planned evidence.** Permission-state screenshots, accessibility checklist, TalkBack/large-font test notes, touch-target and contrast review, and a navigation walkthrough.

**Owner/reviewer.** `[Assign owner] / [Assign reviewer]`

### 12. User Interface — Flow — 6 points

**How.** The primary flow will remain Home → Observe → Results → Confirm → Field Guide. A guided demo will explain the concept, while the normal route uses live inputs. The app will keep one clear primary action per stage, preserve the captured image and selected habitat through analysis, and offer retake, retry and correction paths. The selected map or mission will be reachable from the Field Guide without interrupting capture.

**Why.** This flow matches the user's mental model of making and reviewing an observation. Progressive disclosure keeps sensor diagnostics and evidence available without overwhelming the capture task.

**Feasibility.** The baseline already implements the complete local flow. The remaining work is integrating real model/cloud states and validating the navigation through task-based usability sessions. The team will avoid adding extra top-level screens unless a user task requires them.

**Planned evidence.** User-flow diagram, task completion rate/time, observed navigation errors, retake/retry demonstration and revised designs based on testing.

**Owner/reviewer.** `[Assign owner] / [Assign reviewer]`

### 13. User Interface — Language — 4 points

**How.** Labels will use plain, consistent English and distinguish candidate, relative score, nearby records, live/cached/fallback data and confirmed observation. The UI will avoid unsupported certainty such as “identified with 92% confidence”. Errors will explain the consequence and recovery action, for example that image-only results remain available when ALA fails. Scientific and common names will be displayed together.

**Why.** Meaningful language is critical in a system that combines uncertain AI output and ecological data. Clear provenance and uncertainty wording helps users make the final decision and prevents demo data from being mistaken for live data.

**Feasibility.** The baseline already labels the prototype adapter and relative ranking. The team must move remaining text into string resources, review terminology with users and maintain the same wording in the report and video.

**Planned evidence.** Terminology guide, final string review, error-state screenshots and usability questions testing whether users understand ranking and provenance.

**Owner/reviewer.** `[Assign owner] / [Assign reviewer]`

### 14. User Interface — Reactiveness — 6 points

**How.** The interface will react continuously to stability, lighting, heading, sensor availability and location status. It will expose classification and context-loading stages separately, update image-only rankings when local inference completes, rerank when ALA/cache data arrives, and show partial/offline warnings and retry. Changing microhabitat will immediately recompute the ranking without another network request. Cloud upload state will be visible after confirmation.

**Why.** These responses make context awareness observable and give the user feedback and control over implicit sensing. The UI does not merely display a final static result.

**Feasibility.** Most reactive states already exist in `FloraGuideUiState`. Remaining work includes real model progress, cache/cloud state and measured throttling of high-frequency sensor updates. The single-state architecture reduces inconsistent screen states.

**Planned evidence.** Video showing motion-to-stability changes, light/location updates, image-only-to-fused transition, habitat reranking, network retry and upload queue state.

**Owner/reviewer.** `[Assign owner] / [Assign reviewer]`

## Innovation — 16 points

### 15. Innovation — Novelty — 3 points

**How.** FloraGuide will position its novelty as an explainable, campus-scale context-fusion workflow rather than claiming that photo identification or location weighting is new. The system combines sensor-guided capture, nearby occurrence history, date and explicit microhabitat; shows image-only versus fused rankings; exposes cue contributions; and keeps the user responsible for confirmation.

**Why.** The value comes from making heterogeneous mobile context visible and interactive. Microhabitat and evidence-level explanation distinguish the proposal from a simple wrapper around a classification API.

**Feasibility.** The baseline already demonstrates ranking changes and evidence breakdown. The team must complete a concise competitor comparison, validate that users understand the difference and resist diluting the concept with generic badges or social features.

**Planned evidence.** Related-app comparison, before/after ranking demonstration, explanation usability results and a clear novelty statement in the report/video.

**Owner/reviewer.** `[Assign owner] / [Assign reviewer]`

### 16. Innovation — Surprise — 3 points

**How.** The key surprising interaction is a visible ranking reversal: the visually highest candidate may move down when nearby records, season and microhabitat support another species. Users can change the habitat and watch the explanation update, revealing that identification is a context-dependent inference rather than a single black-box prediction.

**Why.** This is an unexpected but understandable extension of conventional “take a photo and receive one answer” applications. The explanation turns surprise into learning rather than confusion.

**Feasibility.** The guided demo already produces a repeatable reversal. The final application must also find genuine field examples and evaluate whether users perceive the behaviour as useful. The team should not manufacture extreme weights solely to guarantee a dramatic change.

**Planned evidence.** Genuine case studies, image-only/fused comparison, habitat interaction and short user feedback on perceived surprise and trust.

**Owner/reviewer.** `[Assign owner] / [Assign reviewer]`

### 17. Innovation — Tech Knowledge — 4 points

**How.** The project integrates Android sensor APIs, CameraX, on-device computer vision, Kotlin coroutines and state management, REST/JSON parsing, cloud persistence, geographic context, cache/offline design, privacy transformation, explainable ranking and automated testing. Interfaces isolate these concerns while the ViewModel coordinates them into one mobile workflow.

**Why.** The application demonstrates breadth and integration within Computing and Information Systems, while the fusion/evaluation pipeline demonstrates depth. The components affect one another rather than appearing as disconnected technical demonstrations.

**Feasibility.** The baseline already integrates sensors, ALA, ranking and UI with replacement seams. TensorFlow Lite, cloud security and systematic evaluation are the largest remaining knowledge areas and should be validated through early spikes and shared code review.

**Planned evidence.** Architecture/data-flow diagrams, implementation excerpts, tests, model and API integration, security rules, performance results and viva-ready ownership.

**Owner/reviewer.** `[Assign owner] / [Assign reviewer]`

### 18. Innovation — Cross-Disciplinary — 3 points

**How.** FloraGuide applies ecology and biodiversity concepts—species occurrence, seasonality, habitat compatibility, taxonomy and sensitive-location concerns—through GIS queries, computer vision and human-computer interaction. The team will obtain feedback from a knowledgeable source where possible and document the limitations of heuristic priors.

**Why.** Ecological ideas are used to change the algorithm and interface, not added as theme. Citizen-science practices also shape user confirmation, provenance and responsible location handling.

**Feasibility.** ALA provides occurrence data and the prototype already represents season and microhabitat. The group must verify species scope, accepted taxonomy and ecological assumptions rather than treating the demo catalogue as authoritative. If expert access is limited, the report will state that limitation and rely on documented sources.

**Planned evidence.** Taxonomy mapping, cited ecological sources, expert or knowledgeable-user feedback, habitat/season rationale and limitations discussion.

**Owner/reviewer.** `[Assign owner] / [Assign reviewer]`

### 19. Innovation — Impact — 3 points

**How.** FloraGuide aims to make campus biodiversity exploration more understandable, help non-experts learn common species and support structured observation activities. The personal field guide and selected map or mission can encourage repeated outdoor engagement. The design will include uncertainty, correction and privacy controls to reduce harm from misidentification or precise location sharing.

**Why.** The application improves an existing task—learning and recording biodiversity—by combining immediate mobile guidance with an explanation of ecological context. The potential impact is educational and community-oriented rather than merely entertaining.

**Feasibility.** A campus-limited field study is practical within the course. The team will define realistic participants and tasks, measure completion and learning/comprehension indicators, and avoid claiming broader conservation impact without evidence.

**Planned evidence.** Small field/usability study, observation completion, repeated-use or mission results, qualitative feedback, privacy safeguards and a balanced impact/limitations statement.

**Owner/reviewer.** `[Assign owner] / [Assign reviewer]`

## Final compression checklist

Before moving this content into the report:

- retain all 19 numbered headings;
- replace every owner placeholder;
- remove features not accepted into the MVP;
- distinguish baseline evidence from future work;
- add dates or milestones where feasibility depends on sequence;
- cross-check each promise against the contribution table;
- cite external sources and related applications;
- keep the final criterion section within ten pages.
