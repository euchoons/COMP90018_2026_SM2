# Cache and account repair verification

This repair builds on PR #23 and the build/CI recovery in PR #26, and addresses the unresolved synchronization defects from PR #11.

## Data ownership and recovery

- Room version 2 uses `(userId, id)` keys and an explicit migration from both known version-1 layouts. There is no destructive migration fallback.
- Upload/deletion acknowledgements and failure updates require the same owner, revision, and pending state. Deleted rows retain small local tombstones to reject delayed snapshots.
- Every save/delete appends account-specific work. Exhausted uploads remain durable and can be retried from Account; reopening a signed-in session also retries them. Deletion retries stay hidden from the collection.
- Remote listeners exist only for an active observation subscription. The ViewModel cancels the old subscription, clears account-specific UI state, and subscribes for the new account.
- Legacy Preferences rows with an identifiable cloud-photo UID retain that owner. Rows with no recorded owner stay device-local. Account provides a confirmed, explicit import; merely signing in never assigns unowned records to that account.
- Anonymous registration links credentials to the existing Firebase UID. Signing out warns that an un-upgraded guest cannot sign back in. Passwords are not saved in instance state and account identifiers are not retained in session logs.
- Thumbnails prefer a readable local original, then an owned object from the configured Firebase bucket. Downloads are bounded and decoded off the UI thread.
- The guided demo remains local-only and preserves candidate IDs for context ranking.

## Automated coverage

Run `./gradlew test lint assembleDebug --console=plain`.

| Regression | Tests |
| --- | --- |
| Version-1 migration with/without PR #23's extra columns | `FloraGuideMigrationTest` (real SQLite creation followed by Room schema validation) |
| Account keys, stale callbacks, tombstones, duplicate guest import | `ObservationDaoTest` |
| Default worker construction, UID guard, cancellation, independent records, retry-chain preservation | `ObservationSyncWorkerTest` |
| Full metadata, explicit legacy import, queued saves/deletes, failed retry, live Room flow and listener removal | `OfflineFirstObservationRepositoryTest` |
| Native guest linking, cancelled login, failed upgrade, offline entry, existing-account guard | `FirebaseAuthRepositoryTest` |
| Account subscription replacement and offline context ranking | `FloraGuideViewModelTest` |
| Thumbnail ownership, bounded dimensions, corrupt/missing images | `ObservationPhotoLoaderTest` |

The first regression test for dropped scheduling failed against `KEEP` before implementation. A local build-output directory later acquired duplicate generated files such as `ic_launcher 2.xml`; verification was rerun with fresh temporary Gradle build/cache directories, without deleting the original directory.

## Independent review

Claude Code 2.1.271 performed a bounded read-only review of a source/test-only snapshot, with explicit one-time user authorization. No credentials, settings stores, or production data were provided. Codex independently checked the findings:

- Added regression coverage and fixes for duplicate guest leftovers, silently reusing a real account for a guest action, and unrecoverable exhausted uploads.
- Treated photo-less legacy ownership as unknown, rather than accepting the review's unsupported assumption about which account originally owned it; import now requires an explicit user choice in the app.
- The reported pinned-listener issue was already covered by ViewModel cancellation/rebinding and the repository listener-removal test; a flow intentionally represents one account per subscription.
- The review found no migration schema or stale-acknowledgement defect in the supplied revision. This is not a replacement for the actual Room tests.

## Verification boundary

Device checks use an isolated Android emulator data disk with Wi-Fi/mobile data disabled. No production Firebase user was created, no live account was signed in, and no production data or security rules were changed. Actual Firebase credentials, App Check and deployed backend rules still need their own configured-environment acceptance check.

Deleting an observation removes its synced metadata, not its underlying Storage image blob. Blob retention/garbage collection and multi-device conflict reconciliation remain separate features.

Repository policy still requires another teammate's explicit PR approval before merge. Adding the CI workflow does not enable a required status check in branch protection; that requires repository administration.

## PR #12 UI integration

The integration retains PR #12 and PR #23 commit ancestry, all four navigation destinations, Account actions and the offline route. Native ripple and a visible selected indicator remain enabled. The unrelated Java toolchain-resolver plugin was not retained.

Both contrast tests failed against the PR #12 palette before the fix. `ThemeContrastTest` checks actual compositing for status pills and 0.55-alpha ranking panels in both themes, including background/surface variants; `copy(alpha)` replaces the previous alpha. Text now meets the test's 4.5:1 minimum and input outlines meet 3:1. Large-text navigation uses short visible labels while preserving complete accessibility names, covered by `NavigationLabelsTest`.

Final local verification: **71 debug tests and 71 release tests passed, no skips; lint 0 errors / 19 dependency-update warnings; assembleDebug passed.** The offline emulator smoke test covered entry, guided-demo ranking with network skipped, save, confirmation before delete, and immediate collection updates. Small-screen checks used a 320×568 dp viewport and system font scale 2.0; login actions remain scrollable, the hero actions wrap, and navigation labels stay compact.
