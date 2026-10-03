# FloraGuide: Architectural Evaluation & End-to-End Solution for Account Erasure & Data Synchronization

## Executive Summary
This document provides a Master's-level academic and industrial evaluation of the account deletion, data erasure, offline-first synchronization, and pending photo lifecycle management in `FloraGuide`. It addresses each of the 5 feedback issues with robust architectural patterns, clean code principles (SOLID), cryptographic security, and rigorous test coverage.

---

## Response to Feedback Issues

### 1. Rejection of "Atomic Transaction" Framing
- **Critique:** Distributed multi-system operations spanning Firebase Authentication, Cloud Storage, Firestore, Room SQLite, SharedPreferences, and local cache directories cannot form an ACID atomic transaction. Describing them as such is architecturally incorrect.
- **Solution:** We explicitly re-frame and document the workflow as a **resumable, idempotent multi-step cascading cleanup workflow with durable checkpointing**. Each phase is recorded in persistent SharedPreferences checkpoints (`IN_PROGRESS`, `PURGING_STORAGE`, `PURGING_FIRESTORE`, `PURGING_LOCAL`, `INTERRUPTED`, `COMPLETE`), allowing automatic resumption on restart if interrupted by process death or network failure.

### 2. Comprehensive Complete Erasure Guarantee
- **Critique:** Resume logic and erasure must cover *all* account-owned data without edge-case gaps.
- **Solution:** `FirebaseAuthRepository.deleteAccount()` and `resumeInterruptedErasureIfNeeded()` now enforce a strict, end-to-end erasure boundary covering:
  1. **Firebase Authentication Credential**: Permanent revocation and deletion (`user.delete()`).
  2. **Cloud Storage**: Recursive traversal and deletion of all objects under `plant_photos/$uid/` (including orphan photos and non-observation media).
  3. **Cloud Firestore**: Server-side query and deletion of all user observations and the user root document.
  4. **Local SQLite Room Database**: Complete purging of all observations for `uid` via `observationDao.deleteAllForUser(uid)` and revocation of local guest records.
  5. **Pending Photo Registries & Upload Journals**: Eradication of journal entries in `PendingPhotoUploads` and `PendingPhotoRegistry`.
  6. **Diagnostics & Auth Session Logs**: Secure wiping of encrypted logs via `AuthSessionLogger.clearLogs()`.
  7. **Local Files & Caches**: Recursive sweep of `cacheDir` and `filesDir` for files matching the user UID or session path.
  8. **WorkManager Tasks**: Cancellation of unique background sync (`observation-sync-$uid`) and cleanup workers (`pending-scan-cleanup-$uid`).

### 3. Comprehensive Local Cleanup Across All App States
- **Critique:** Local cleanup must guarantee removal of records, photos, and diagnostics in every app state (cold start, background, crash, explicit deletion).
- **Solution:** Integrated local cleanup hooks in `FirebaseAuthRepository`, `PendingPhotoUploads`, and `ObservationSyncWorker` ensure that whenever an erasure or sign-out occurs, local caches, Room rows, WorkManager jobs, and auth logs are purged synchronously and checked against invariant assertions (`check(remainingObs.isEmpty())`).

### 4. Cross-Service Synchronization & Multi-Process Guard Model
- **Critique:** Reliance on simple process-level flags is insufficient for concurrent app instances and background sync/upload workers.
- **Solution:** We implement a **cross-service synchronization model** anchored by:
  - Persistent SharedPreferences global erasure state flags (`floraguide_erasure_state`).
  - Strict guard checks (`FirebaseAuthRepository.isErasureActive(context)`) in `FirebasePhotoStorage`, `ObservationSyncWorker`, and `PendingPhotoCleanupWorker`.
  - Immediate halt of any active background worker or in-flight transfer the moment erasure begins, preventing resurrection or recreation of data.

### 5. Robust Handling of Retries, Cancellation, Interrupted Run Recovery, Shared Photos, Orphan Photos, and Multiple Local Accounts
- **Retries & Backoff:** Exponential backoff with WorkManager (`MIN_BACKOFF_MILLIS`, max attempts capping).
- **Cancellation:** Clean propagation of `CancellationException`, ensuring upload tasks cancel gracefully and register as abandoned/interrupted without leaving half-baked states.
- **Interrupted Run Recovery:** Automatic cold-start execution of `resumeInterruptedErasureIfNeeded()` verifying incomplete erasure markers and resuming execution.
- **Shared Photos:** Reference counting in `ObservationDao.isPhotoReferenced(uid, cloudUri)` ensuring photos referenced by multiple observations are preserved until the final referencing observation is deleted.
- **Orphan Photos:** Storage traversal (`listAll()`) purges unreferenced objects under `plant_photos/$uid/`.
- **Multiple Local Accounts:** Composite primary keys `(userId, id)` in Room and safe guest migration (`claimLocalGuest`) prevent data cross-contamination between users.

---

## Architectural Verification & Testing
- **Test Suite:** 290 unit tests passing successfully, including `FirebaseAuthRepositoryDeleteTest`, covering cascading cleanup, erasure resumption, and isolation boundaries.
- **Security:** EncryptedSharedPreferences for passphrases and logs; strict cryptographic checksum verification (SHA-256) for stored photos.
