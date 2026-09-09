# 1.5.2-beta4 release verification

- Version code: **173** (previous code173 candidate was not distributed).
- Source commit: `d3c591e7d4a171825eab5473d4e5e92bdd8cb213`.
- APK: `releases/완장봇_v1.5.2-beta4.apk`, **13,289,642 bytes**.
- SHA-256: `3c876e90027c21e868ac4fb2ef82ec7c2e2f2923f05aedba4fbb272f4295144a`.
- Signing certificate matches beta3; minimum SDK 24. Signed APK verification and multidex limits passed.
- Prior APK/source acceptance is superseded by the authoritative reset-inventory fix. Prior local APK and evidence were archived, not deleted.

## Completed gates

- Full Debug unit suite: **617 passed**, no failures/errors/skips.
- Full Release unit suite: **617 passed**, no failures/errors/skips.
- API35 (`emulator-5556`): **53 passed**, no failures/errors/skips.
- API24 (`emulator-5558`): **51 passed**, no failures/errors/skips. The two API26+ PixelCopy tests were explicitly filtered out, not counted as skipped.
- XML method lists were compared with source annotations: no missing or unexpected device tests.
- Debug lint: 0 Fatal, 0 Error, 50 Warning, 28 Information.
- Release lint: 0 Fatal, 0 Error, 45 Warning, 28 Information.
- Debug, Release and instrumentation APK builds and DEX invoke checks passed. Final release was not rebuilt after verification.
- Frozen source file hashes remained unchanged throughout all final gates.

The initial combined Debug/Release lint invocation failed inside Android Lint with `ConcurrentModificationException` in `TomlUtilities.pickVersionVariableName`. Its logs and successful unit XML were preserved. Separate unchanged-source `lintDebug` and `lintRelease` invocations with `--max-workers=1 --no-parallel` passed.

## Reset-inventory regression

The previous early-open/All flow was reproduced on API35 before the fix. The chooser now requires an independent fresh DAO inventory, unions stored scopes with known bot options, fails closed on delayed/failed reads, and offers retry. IDs and labels are frozen at Next. Focused unit and device tests cover early opening before the general loader, loading/error gating, complete retry inventory and frozen confirmation. Existing shared-claim retention, reference-safe transactional deletion and live-work guards remain covered.

## Exact signed release UI

The installed APK was pulled back from both disposable guests and matched the release SHA-256. **24 screenshots** (six screens × light/dark × two APIs), plus matching UI XML, were inspected: DB, filter, reset chooser, reset confirmation, individual DB settings and schedule page.

Only newly created synthetic offline fixtures were used. Independent DB was toggled true/false/true and checked after restart; initialization and enabled state persisted. Running and restore flags stayed false; no bot was started. The light capture preceded enabling private DB, while the dark capture followed it, so the private option appears only in the latter by design. Android notification permission and navigation recovery affected only the visual runner, not application source.

No authenticated live moderation was performed for this UI-focused beta. Existing lint warnings are disclosed above rather than treated as a clean-warning baseline.

## Distribution

The APK and `완장봇_v1.5.2-beta4_사용안내.md` were uploaded to the development/beta Drive folder. Each exact target was read back for name, MIME, parent and size, then downloaded and SHA-256 compared with the local file. Both matched. Drive IDs and raw device/log evidence remain in the local release checkpoint rather than being published in this public repository.
