# Demo guide

The demo has two purposes: explain the proposal reliably and prove which mobile components are genuinely implemented. Keep those purposes separate.

## Route A — guided concept demonstration

Use this route first because it is repeatable and does not depend on permissions or network quality.

1. On Home, select **Run the 60-second guided demo**.
2. Explain that the current image adapter produces a deterministic Top-K list for the prototype.
3. Point out the image-only Top 3 before context arrives.
4. Show the fixed campus location, date, habitat and offline demonstration records.
5. Point out that context changes the ranking.
6. Open **Why this species?** and show image, location, season and habitat evidence.
7. Change the habitat and show the immediate local rerank.
8. Confirm the candidate and open the Field Guide.

Suggested statement:

> This guided route demonstrates the interaction and fusion architecture. The visual candidate generator is currently a deterministic adapter, so we are not presenting it as a trained model. The final project will replace the adapter behind the same interface.

## Route B — live mobile-system demonstration

Use a physical phone.

1. Open Observe and grant camera permission.
2. Move the phone, then hold it steady to show the stability score and capture gate.
3. Show ambient-light and heading values when the device supports them.
4. Grant precise, approximate or denied location in separate tests and explain the visible fallback.
5. Capture a real JPEG.
6. Show the image-only result appearing before the ALA context lookup completes.
7. Show the data-source label, request success count and latency.
8. Disable network or trigger retry to demonstrate partial/offline behaviour.

This route proves camera, sensors, location and connectivity. It still does not prove visual recognition until a real model is integrated.

## Three-minute team pitch

### Problem

Image-only recognition is ambiguous. Mobile context can narrow the candidate set, but users need visibility and control.

### System

BioScout uses camera, motion, light, heading, location, date, microhabitat and ALA records. It first responds with local candidates, then reranks when network context arrives.

### Technical depth

The app performs sensor fusion, concurrent network requests, smoothing, log-linear evidence fusion, softmax ranking and coarse-location persistence with explicit fallbacks.

### Innovation

The user sees how context changes the ranking and can inspect or change the microhabitat, rather than receiving a black-box answer.

### Honest status

Camera, sensors, ALA client, ranking and local persistence are present in the baseline. The trained model, cloud backend and formal evaluation remain planned work.

## Failure preparation

Before any live presentation:

- build and install on at least two phones;
- test airplane mode and denied permissions;
- charge devices and enable Do Not Disturb;
- prepare a short screen recording of the real phone as backup;
- retain the guided demo as the offline explanation route;
- never substitute an emulator recording for physical-sensor evidence.
