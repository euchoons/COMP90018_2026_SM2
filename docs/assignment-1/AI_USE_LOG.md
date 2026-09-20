# AI use log and acknowledgement

The assignment permits AI-assisted work only when the tool and examples of use are described and acknowledged. This repository was materially assisted by AI during planning, code scaffolding and documentation, so the final report must not omit this section.

## Working log

Add an entry when an AI tool materially affects submitted ideas, prose, code, tests, diagrams or translations.

| Date | Tool and model/version shown | Task or representative prompt | Output retained or files affected | Human changes and independent verification | Team member |
|---|---|---|---|---|---|
| `[Date]` | `OpenAI ChatGPT — [model shown]` | `Generate a maintainable Kotlin/Compose prototype for an explainable context-aware species-identification app.` | Initial architecture, UI/data scaffolding, tests and documentation. | Team reviewed source, removed generated/local files, ran builds/tests, verified APIs and rewrote scope/claims. | `[Name]` |
| 2026-09-16, rebased 2026-09-20 | Anthropic Claude Code — Claude Opus 5 (`claude-opus-5`) | `Validate the existing CameraX lifecycle, capture, rotation, resize and permission paths, and verify the Firebase photo upload.` | Camera fixes in `CameraCaptureCard.kt` and `ScanScreen.kt`; `docs/CAMERA_VALIDATION.md`. | Unit tests, lint and debug build pass. Physical-device checklist in `CAMERA_VALIDATION.md` still to be run and reviewed by Mason. | Mason |
| 2026-09-20 | Anthropic Claude Code — Claude Opus 5 (`claude-opus-5`) | `Document and justify the accelerometer/gyroscope stability thresholds calibrated in 41c5b9b.` | `MotionStabilityEstimator.kt` and its tests (behaviour-preserving extraction); `docs/MOTION_STABILITY_CALIBRATION.md`; corrected stability constants in `ARCHITECTURE.md`. | Derivations reproduced as unit tests (tolerances, unlock/lock timing). No device measurements yet; the calibration runs in the new document remain Mason's to perform and review. | Mason |
| `[Date]` | `[Tool]` | `[Use]` | `[Affected output]` | `[Review/test]` | `[Name]` |
| 2026-09-20 | OpenAI Codex (GPT-6) | Restore the main build after PR #11 and automate PR checks (issue #24). | Gradle dependency catalog, executable wrapper, Android CI workflow. | Codex ran test/lint/assembleDebug: 36 tests passed in each of debug and release; lint had 0 errors and 18 dependency-update warnings. Human review is pending. | Tom (requested work) |
| 2026-09-20 | OpenAI Codex; Anthropic Claude Code 2.1.271 (Claude model not reported) | Repair account isolation, offline cache/recovery, and integrate PR #23 (issue #25); independently review only selected source and tests. | Room migration and revisions, account-scoped sync, explicit local import/retry, native guest linking, lifecycle-aware UI, thumbnails, regression tests. | Codex independently verified Claude's findings and added regressions; see CACHE_AUTH_REPAIR_VERIFICATION.md for coverage and remaining backend/manual boundaries. Human review is pending. | Tom (requested work) |
| 2026-09-20 | OpenAI Codex | Integrate PR #12 with the repaired account flow and verify actual alpha-composited contrast and large-text layout. | UI merge, light/dark palette, retained Account navigation, wrapping actions and accessible compact labels. | Contrast tests were red before the palette change. Final local run: 71 debug + 71 release tests pass, lint 0 errors / 19 warnings, APK builds; isolated offline emulator smoke checks and 320×568 dp / font-scale 2.0 visual checks. Human review is pending. | Tom (requested work) |

The sample first row must be corrected to match the actual model, dates, prompts and retained files. Add other tools such as code completion, Grammarly, translation or image generation when used materially.

## Acknowledgement draft

> The group used OpenAI ChatGPT (`[model/version shown at time of use]`) during project planning and prototyping. Uses included scope analysis, generation of an initial Kotlin/Jetpack Compose architecture and prototype, suggestions for the context-fusion algorithm and failure handling, drafting unit-test cases, and preparation or editing of project documentation. The group reviewed and modified all retained outputs, ran builds and tests, independently verified external API behaviour and Android device behaviour where claimed, and did not treat AI output as authoritative for ecological facts, model accuracy or assignment interpretation. The current deterministic classifier is explicitly labelled as a prototype and is not represented as a trained image-recognition model. Representative prompts and affected files are summarised in the AI-use log.

Edit this paragraph so that it is factually exact. If multiple tools were used, name each one and describe its contribution.

## Verification standard

For AI-assisted code, record at least one of:

- focused unit or integration tests;
- a reviewed pull request;
- comparison with official API/platform documentation;
- physical-device testing;
- manual reproduction of the algorithm or calculation;
- independent rewriting by a team member who can explain the design.

For AI-assisted prose, verify claims against Canvas, source documentation and actual project status. Translation or grammar assistance must also be acknowledged when required by the assignment rules.

## Viva rule

A tool may accelerate implementation, but the contributor who commits the code must be able to explain why it works, identify its limitations and modify it during the individual viva.
