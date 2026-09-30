# FloraGuide data handling and current limitations

This document describes implementation behavior, not a certification of privacy-law compliance.
The application is a coursework prototype. The limitations below must be resolved before claiming
complete account erasure or a safe encrypted-storage upgrade.

## Location and network requests

- Saved observation coordinates and live ALA request coordinates are rounded to three decimal
  places. This reduces precision; it does not make locations anonymous. Capture-time location
  remains in memory while the current scan is available for analysis or retry.
- ALA receives approximate coordinates, a query radius and taxon information. It does not need
  the user's Firebase UID or plant photo.
- Live identification uploads photos to Firebase Storage and sends image data to Pl@ntNet over
  HTTPS. Pl@ntNet identification requires a network connection; the guided demo is local.
- Android's normal certificate validation is used. Custom certificate pinning is not implemented.

## Local storage and diagnostics

- Room uses SQLCipher when its native library loads. **Existing plaintext databases have no
  encryption conversion yet.** The current library/key fallback paths also need hardening; do
  not claim all observation storage is encrypted or suggest clearing app data as an upgrade.
- Authentication diagnostics use a separate encrypted preferences file. Before persistence,
  UIDs are SHA-256 hashed and email addresses in details are replaced with `[REDACTED_EMAIL]`.
  Hashes remain linkable pseudonymous identifiers, not anonymous data.
- Existing diagnostic entries are sanitized and migrated before their plaintext source is
  removed. If secure storage is unavailable, new diagnostics are omitted rather than written
  in plaintext. A failed migration retains its old source for recovery.
- At most 100 diagnostic events are retained. Signing out does not itself purge those events.
- Database files, preferences and `files/photos/` are excluded from Android cloud backup and
  device transfer. This avoids transferring an encrypted database without its device-bound key.

## Deletion limitations

- Observation deletion queues a local tombstone. The worker deletes the cloud photo and then
  the Firestore document; transient failures are retried. These separate operations are not an
  atomic transaction. Shared-photo references still require coordination.
- Account deletion enumerates server-side observations and deletes their referenced photos,
  documents, user document and finally Firebase Auth credentials. A failed photo deletion stops
  the cascade; already-missing photos are treated as deleted.
- **Account erasure is not complete or transactional:** local records/logs, unreferenced cloud
  photos, concurrent sync and recent-login requirements still need coordinated handling.
  Partial cloud deletion can occur before a later step fails. Do not promise that the button
  erases every copy of the user's data.
