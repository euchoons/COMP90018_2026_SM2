# App overview

## One-sentence concept

BioScout is a campus biodiversity observation app that provides an explainable species shortlist by combining camera-derived candidates with location, season, nearby occurrence history and the user's observed microhabitat.

## Problem

Image-only species identification can be ambiguous when multiple plants look similar, a photo is poorly framed, or the model has limited training data. A mobile device already has access to additional context that can reduce this ambiguity: where the observation was made, when it was made, what habitat surrounds it and whether the photo was captured under usable conditions.

BioScout turns these separate cues into one understandable workflow rather than presenting the output of a black-box image service as a definitive answer.

## Target users

The initial target is a student or visitor exploring a university campus who wants to learn about common plants without requiring expert botanical knowledge. A secondary user is a teaching or citizen-science group that wants a structured record of observations while retaining user control over the final identification.

The final MVP should focus on one taxonomic group, preferably 20–50 common campus plant species. Supporting plants, birds and insects simultaneously would create model, taxonomy and evaluation scope that is not credible for the project schedule.

## Core experience

1. The user opens the Observe screen.
2. Sensor feedback indicates whether the phone is steady and the lighting is usable.
3. The user selects the visible microhabitat and captures a photo.
4. An on-device model produces a Top-K candidate list.
5. The app immediately shows an image-only shortlist.
6. The app retrieves nearby Atlas of Living Australia occurrence counts for the candidates.
7. Image, location, season and microhabitat evidence are fused and the Top 3 is reranked.
8. The user opens **Why this species?** to inspect the evidence.
9. The user confirms, corrects or rejects the suggestion.
10. The observation is stored in a personal field guide and, in the final system, synchronised to the team's cloud backend.

## Context cues and their roles

| Cue | Source | Role in the product |
|---|---|---|
| Image | Camera and on-device model | Produces the initial candidate set. |
| Stability | Accelerometer + gyroscope | Reduces motion blur and demonstrates sensor fusion. |
| Light | Ambient-light sensor | Warns about low light or possible glare. |
| Heading | Accelerometer + magnetometer | Records observation direction as optional metadata. |
| Location | GPS/network provider | Selects geographically relevant occurrence history. |
| Date/season | System clock | Adjusts compatibility with seasonal patterns. |
| Microhabitat | Explicit user input | Adds local ecological context that GPS alone cannot provide. |
| Nearby records | Atlas of Living Australia | Supplies an Internet-derived location prior. |

## Innovation position

The project should not claim that image identification, location weighting, badges or field guides are individually new. The stronger and more defensible innovation is the combination of:

- sensor-guided capture;
- campus-scale microhabitat context;
- visible image-only versus fused ranking;
- cue-level explanation and user control;
- explicit live, partial and offline behaviour;
- an evaluation that measures whether context actually improves Top-1 or Top-3 accuracy.

The surprising interaction is that the visual leader can be demoted when ecological evidence strongly supports another candidate, and the user can see why.

## Proposed final MVP

The group should treat the following as the minimum credible final scope:

- one agreed plant group with a controlled species list;
- CameraX capture and an on-device TensorFlow Lite Top-K model;
- GPS, date and ALA nearby-occurrence context;
- accelerometer/gyroscope stability feedback;
- one additional useful sensor cue, such as light or heading;
- explainable context reranking with unknown/genus-level fallback;
- user confirmation and a cloud-backed personal field guide;
- complete permission, sensor and network fallbacks;
- one showcase extension: either a campus biodiversity map or a team observation mission;
- accuracy, latency and usability evaluation.

## Non-goals for the MVP

The project should not attempt all of the following at once:

- identifying plants, birds and insects with separate models;
- public voting, rankings, achievements and daily challenges;
- direct write access to ALA before authentication is verified;
- a complex social network;
- calibrated confidence claims without validation;
- sensitive-species location sharing.

These may remain stretch ideas, but they should not be required for the core demonstration.

## Success measures

A final evaluation should report:

- candidate recall at K;
- image-only versus fused Top-1 and Top-3 accuracy;
- ablation results for location, season and habitat cues;
- model inference, ALA lookup and end-to-end latency;
- behaviour under no network, partial requests and missing sensors;
- task completion and comprehension in a small usability study;
- whether users understand that the score is relative and that they retain final control.

## Safety, privacy and trust

BioScout can make incorrect suggestions. The UI should show multiple candidates, support unknown or genus-level results and avoid authoritative language. Exact coordinates should not be exposed by default, especially for sensitive species. Photos and cloud observations require clear user consent, deletion controls and documented retention rules.
