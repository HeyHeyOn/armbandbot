# 1.5.2 formal release verification

## Artifact
- Version: **1.5.2**, versionCode **174**, minimum Android API **24**.
- Source commit: `ab3e8d175635eab58d6af8ee07010c2fb519aaf2`.
- APK: `releases/완장봇_v1.5.2.apk`, **13,289,634 bytes**.
- SHA-256: `4052dc23ebe25640138d72a223b7a42ea052784f6f186d0e95f30213d9938533`.
- Only versionCode/versionName changed from the accepted beta4 runtime source. The authoritative reset-inventory correction is included; no runtime feature or data migration was changed for formal promotion.
- Android v2 signing verification passed. The existing legacy Android-debug certificate was retained for update compatibility; it matches both 1.5.1/code169 and beta4/code173. This is not a newly provisioned production signing key.

## Checks executed for this formal artifact
- `testDebugUnitTest`: **617 tests, 0 failures/errors/skips**.
- `testReleaseUnitTest`: **617 tests, 0 failures/errors/skips**.
- `assembleRelease`: passed.
- Sequential `lintRelease --max-workers=1 --no-parallel`: **0 Fatal, 0 Error, 45 Warning, 28 Information**, matching the accepted beta4 release baseline.
- Source-manifest hashes remained unchanged through build, tests, lint and the source commit.
- Exact DEX incoming-word verification passed: maximum **248**, headroom **7**. Five DEX files; minimum method-ID headroom **70**. Capacity remains narrow and is not described as comfortable.
- Allocated disposable **emulator-5556/API35** and **emulator-5558/API24** upgraded from beta4 with `adb install -r`. No uninstall, data clear, fixture reseed or connected-test package cleanup was performed.
- Installed pm-path APKs were pulled back from both devices and matched the formal APK SHA-256.
- On both APIs: launcher displayed 1.5.2; DB, filter chooser, independent reset chooser and All confirmation opened; Cancel returned to the identical rendered DB rows. API35 retained the prior authorized test history; API24 had an empty history. No reset was executed.
- Six exact-release screenshots were visually inspected: DB, filter and reset confirmation on both APIs. This is a dark-theme upgrade/navigation smoke, not a repeated full visual matrix.
- Both bot UIs remained stopped; exact-package services were absent after force-stop. Crash-buffer evidence was bounded to each upgrade interval and contained no app crash. Existing IPv4/IPv6 external-network drops remained active.
- The first device helper attempt failed before installation because the shell date format contained an unquoted space. The helper was corrected; the subsequent real upgrade/smoke completed. No app-source change was made for that tooling error.

## Retained evidence and boundaries
The beta4 full device suite (**53 API35 / 51 API24**, with two API26+ pixel tests filtered from API24) and full light/dark visual matrix remain applicable to the unchanged runtime source. See `beta4-release-verification.md`. These suites were **not rerun as new code174 instrumentation**. No new authenticated moderation or server fixture operation was performed during formal promotion. Prior live-operation evidence remains separate and is not an all-features/live-all-pass claim.

The pre-inventory-fix beta4 source/APK evidence remains superseded. Neither it nor any older artifact was deleted or repackaged as this release.

## Known limitation
Saved **original HTML** still depends on remote DC styling/logo resources and can lose its layout offline. The native snapshot retains stored text/comment content. This observed limitation was disclosed before release and is stated in the formal manual, user guide and APK Drive description; it was not claimed fixed.

## Distribution and documentation
The versioned APK was uploaded to the established **formal** Drive folder. Exact filename, parent, MIME and byte size were read back; the uploaded bytes were downloaded and matched the local SHA-256. Existing release files were preserved.

The existing Google Docs manual and patch-note tabs and the user guide were updated in place. Readback verified full expected text, unchanged tab IDs, paragraph styles, bullet presence and explicit text styles. Patch notes summarize the user-visible changes from 1.5.1: individual DBs, multiple operating windows, DB/gallery filters, selected reset and shared duplicate-action protection. Raw Docs snapshots, upload IDs and device evidence remain in the local release artifact directory rather than being committed.
