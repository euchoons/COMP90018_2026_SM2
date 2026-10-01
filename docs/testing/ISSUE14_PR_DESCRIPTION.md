# Suggested PR title

fix: harden cloud photo lifecycle and validation (issue #14)

## Summary

Refs #14.

- Preserve the already-integrated Firebase upload -> download -> Pl@ntNet workflow.
- Add complete signature reads and remote SHA-256/MIME/path validation.
- Give new live-scan uploads independent ownership without changing historical observation-sync paths.
- Persist pending scan references before upload and clean up abandoned, unreferenced photos through WorkManager.
- Protect photos during saves and preserve uploaded references for retries.
- Ignore stale upload, classification and ALA callbacks after navigation, retry or account changes.
- Recover interrupted sessions and check pending cleanup before explicit account deletion.
- Add core, adapter, ViewModel and cancellation regression coverage.

## Validation

- [x] 27 shared Android-independent core contract cases executed and passed in a portable Kotlin harness.
- [ ] Run focused Android Gradle unit tests in the repository.
- [ ] Run `test lint assembleDebug` in the repository.
- [ ] Complete device acceptance checks in `ISSUE14_FIX_AND_VERIFICATION.md`.
- [ ] Verify deployed Firebase authentication, rules, bucket and App Check configuration.

## Notes

No secrets, `google-services.json`, dependency upgrades, Room migrations, public bucket rules or ALA client/ranking-algorithm changes are included. Client-side cleanup cannot guarantee retention deadlines after uninstall/data clearing or permanent loss of account credentials. Historical shared objects are deliberately not bulk-deleted. Do not close #14 solely on the portable core test result.
