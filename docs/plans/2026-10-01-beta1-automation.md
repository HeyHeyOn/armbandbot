# 1.5.4-beta1 Automation Implementation Plan

> **For Hermes:** Sol is the sole implementation/release owner; independent read-only review is optional.

**Goal:** Add scheduled bump, keyword category move, and GitHub/Sheets remote lists. Cross-gallery monitoring is excluded.
**Architecture:** Separate pure validated models, HTTPS remote adapter, Android preference integration, native manager-action runner and extracted Compose pages. Keep Room schema unchanged and preserve all local lists. Use existing service/request gates, shared action claims and safe post URLs.
**Tech Stack:** Kotlin, Compose, Jsoup, org.json, SharedPreferences, existing Room claims, JVM JUnit and Android instrumentation.

## 1. Pure policy RED/GREEN
Create `PostAutomation.kt`, `RemoteLists.kt`, `AutomationPolicyTest.kt`, `RemoteListsTest.kt`. Validate strict first-party post/gallery URLs, time tokens, JSON shape, source host restrictions, CSV quoting, allowlisted list types and native payloads. Run `./gradlew testDebugUnitTest --tests '*AutomationPolicyTest' --tests '*RemoteListsTest' --max-workers=2` first while symbols are intentionally absent; retain RED logs. Implement and rerun. No identity monitoring.

## 2. Remote adapter and preference integration
Create `AutomationPreferences.kt` and `RemoteListClient.kt`. Accept GitHub raw JSON, Google Sheets URL-to-CSV and Apps Script JSON without app-embedded credentials. Reject unsafe hosts, redirects, oversized/malformed/HTML/error responses; preserve last good cache only for the same configured source. Merge remote values with local lists; never replace local values. All lists off by default, existing filter enable flags remain authoritative. Per-source poll interval, success/status UI and bounded once-per-policy recheck without clearing saved rows.

## 3. Native request runner
Create `PostAutomationRunner.kt`. Load due daily schedules with occurrence identity and a bounded lateness window, enforce bot running/work gate, explicit managed target gallery and confirmed permissions, exclude fixed/notice posts, persist shared claims before writes and read back each exact target. Unknown responses are not retried blindly. Category rules use one destination per ordered keyword rule and protect exempt/whitelisted authors. Read destination from current gallery metadata; warn about obstruct categories. Delete/block takes priority over category move.

## 4. Service and settings integration
Modify `BotService.kt` at cycle start, list-row recheck and post-completion. Extract `PostAutomationSettingsScreen.kt`, attach one submenu entry without adding remembered state to the giant detail closure. Preserve refresh → independent DB → existing operating schedule ordering. Controls: daily bump URL + times, ordered keyword + fetched category, remote source type/URL/interval/refresh. Save drafts only on valid Save; retain errors without overwriting settings. Export/import only configuration, not cache/claims/execution state, and leave imported automation off until explicitly enabled.

## 5. Full acceptance
Test pure behavior and real Android preference round-trip, cache failure preservation, disabled state, unchanged-row generation and no-secret exports. Run full Debug/Release JVM suites, sequential lint, signed release build, DEX limits. On private-data API24/API35 emulator partitions, exercise new screens light/dark and upgrade data retention. Verify actual sandbox bump and category move only on fresh owned fixtures using the existing authorized laboratory1 administrator. Never touch unrelated posts or restrictions. Record tests not run separately.

## 6. Beta packaging
Set code177/name1.5.4-beta1, create a separate beta guide and release verification report. Commit only intended sources/tests/docs/versioned APK, push feature branch and read remote SHA. Upload versioned APK and guide to the established beta folder using existing Drive auth; read metadata and re-download/hash. No formal Docs changes or public gallery announcement for a beta. Preserve old artifacts and preexisting worktree state.

## 1.5.4-beta2 UI follow-up

User scope: move gallery auto-refresh, scheduled bump and automatic tab classification into a new management-automation section; move remote lists into basic exploration settings. Detail rows only toggle or navigate. Each page contains only its own settings; long explanations are opened with the help icon. No backend-policy, DB-schema or local-list replacement changes.

- [x] Add isolated page routes, shared themed navigation rows, saved-setting preservation and concise/help-only instructions.
- [x] RED: new navigation APIs and DC list-template parsing failures reproduced before fixes. Final complete Debug/Release JVM suites: 666 each, zero failures/errors/skips; both sequential lint variants: zero errors; signed build and DEX gate pass.
- [x] Final-source API35: 10 UI cases, one real GitHub JSON/CSV case and one real-service sandbox case; zero failed/skipped tests. The service reached the natural future clock boundary, verified server changes and persistent claims, then stopped/restored only the owned fixture.
- [x] Exact signed beta2 SHA `624084212162d315693f38ade453aea44b0c603c2063808426f30d0300dad242`: beta1 upgrade retains registration/settings; actual main switches, separate pages, reservation edit, real gallery-tab selection/rule save, real Sheets reception/local-list preservation and restart retention passed. Six unchanged native PNG captures are prepared for Telegram attachment.
- [ ] Release closeout: versioned APK/guide are packaged; selective commit/push and independent GitHub artifact readback remain at this checkpoint. Drive upload is separately blocked: existing Python OAuth and gws/keyring both returned `invalid_grant`; user reauthentication is required. Do not claim a Drive upload or broaden scopes.

## Implementation/verification checkpoint
- [x] Pure models, URL/time/CSV/list validation and normal/bypass storage-key aliases.
- [x] Remote source-scoped cache, stale-source rejection, local list retention, private per-preference locks (API24 disk-write regression) and per-HTTP-attempt work gates.
- [x] Native manager controls parsed from actual DC templates; explicit action claims and exact-target readback; imported/copied automation remains disabled.
- [x] Latest Debug/Release JVM suites: 664 each, no failures/errors/skips; full Debug/Release lint: 0 errors, classic UAST used to avoid the K2 CleanupDetector hang; signed beta and DEX gate.
- [x] Latest debug APK: API24 13 completed cases; API35 8 UI/client/GitHub cases plus the separate real-service scenario below. All opt-in acceptance runs have zero skipped cases. Light/dark, narrow screen/large font, Room rows, cache failure and disabled state are covered.
- [x] Native move/bump were first exercised through direct runner calls. The real-service acceptance below supersedes that earlier evidence; injected claims or skipped GitHub checks are not reported as full live acceptance.
- [x] Latest real `BotService` API35 scenario: 1 complete test, 0 skips. Natural future-clock bump, exact server-top readback, persistent Room claims, exemption protection, competing enabled/disabled/stopped bot safety and restart duplicate prevention passed; owned tab and guest secrets restored/removed. Debug SHA `b9eedebb9086cb901b66e59e03c9059818dcd8e793445f2799c73e8c69a49602`. RED reproduced a disabled-competitor reversal; saved policies now remain in the shared move identity while election alone uses enabled/running flags.
- [x] Final signed release SHA `cfe313db1b8add5da8bbb058853de3d51e7de88019eb5cce4b44243e3a2736e0`: v1.5.3 upgrade smoke retains bot registration and existing settings; certificate matches the previous release. Real GitHub JSON/CSV reception and local-list retention passed against immutable source commit `02b963d86a8a52339eb7e860d2f1c460a65d01bf`. Feature source push was read back; APK/guide were uploaded to the established beta Drive folder and independently re-downloaded with matching hashes. Final metadata push is verified at closeout.
- [x] Live Google Sheets endpoint: following the user's later explicit approval, a dummy-only sheet was created in the existing development folder and exercised. Exact signed beta2 now receives its six rows through the actual native UI, retaining local lists. No pre-existing private sheet was published or given broader permissions.

The new settings page exposes source type, URL and manual refresh; automatic polling uses the bounded stored interval. The old interrupted Google-sheet idea was not silently re-enabled. Existing operating schedules, local lists, user exceptions and delete/block priority are retained. No formal Google Docs edits or public gallery announcement are part of this beta.
