# FloraGuide Data Privacy & Retention Policy

## 1. Executive Privacy Architecture
FloraGuide implements a **Zero-Trust Privacy Model** designed in compliance with the Australian Privacy Principles (APP) and general data minimization standards.

## 2. Location Coarsening & Geo-Privacy
- **Precision Truncation:** All captured GPS coordinates are coarsened at the domain boundary to 3 decimal places ($0.001^\circ \approx 111\text{m}$ grid precision at $37.8^\circ \text{S}$).
- **Purpose:** Prevents precise home/user tracking while maintaining sufficient spatial resolution for Atlas of Living Australia (ALA) ecological context queries.
- **Raw Coordinates:** High-precision hardware GPS coordinates are processed exclusively in-memory for sensor fusion and discarded immediately after shutter capture.

## 3. Ephemeral External Queries
- **Pl@ntNet API:** Captured images are transmitted via TLS to Pl@ntNet for image classification. API keys are injected via secure query arguments and suppressed from all device logs.
- **Atlas of Living Australia (ALA):** Only coarsened coordinates and public scientific taxon names are queried. No user credentials, user IDs, or plant photos are transmitted to ALA.

## 4. Cloud & Local Data Retention Schedule
| Data Category | Local Storage | Cloud Storage (Firebase) | Retention Period | Deletion Trigger |
| :--- | :--- | :--- | :--- | :--- |
| **Observation Metadata** | Encrypted Room DB | Firestore Document | Retained until user deletion | User deletes observation |
| **Plant Photos** | Local App Sandbox (`files/photos`) | Firebase Storage Bucket (`plant_photos/{uid}/`) | Retained while observation active | User deletes observation or purges account |
| **Auth Session** | Encrypted SharedPreferences | Firebase Authentication | Ephemeral / User session | Sign out or account erasure |

## 5. Deletion & "Right to be Forgotten"
- **Observation Deletion:** Deleting an observation triggers an asynchronous tombstone record in Room (`SyncState.PENDING_DELETE`). The `ObservationSyncWorker` atomically deletes both the Firestore metadata document and the Firebase Storage photo object.
- **Account Purge:** Account erasure completely deletes the user credentials from Firebase Authentication and clears local database records.
