# iOS launch, Home, version and QR parity

Base: `feat/native-rewrite` at `c7b5f3e6`. Verified 2026-10-20 on the dedicated **Noah iOS Parity t3-307ba163** simulator, UDID `AF608E65-4B31-4D39-94B6-1961879F7935` (iOS 27.0, arm64). Existing simulators, Android devices and Docker containers were not operated on.

## Behaviour

- **Launch:** RN's `Navigators.tsx`, `PushNotificationsRequiredScreen.tsx` and `pushNotifications.ts` were read first. Only an initialized wallet on a physical device with **denied** general permission shows the blocking notification screen. Checking, undetermined, authorized, provisional and ephemeral states do not show it. Expo Notifications **58.1.1** was checked: only `.authorized` maps to granted; provisional/ephemeral map to undetermined. Registration requests full authorization instead of treating provisional as granted. Simulators skip registration before requesting permission. The three RN actions and explanatory copy are preserved; recheck and foreground return from Settings resolve the gate. Tabs and foreground service startup/backup scheduling are gated. Native seed/storage/restore decisions still precede this screen, and the existing biometric window also covers the permission screen.
- **Home:** uses the existing fiat formatter and 48 shared currency cases; preferred-currency quotes cache for five minutes. Hidden fiat uses RN's symbol plus four bullets, with the same four-bullet bitcoin mask. Settings, map and QR shortcuts work. All 24 RN facts are selected on mount and refresh. Recent transactions remain in place.
- **Banners:** backup progress/failure/retry and five-second success; automatic boarding success for five seconds; pending rounds with singular/plural copy, status/final/success/error fields, funding copy, funding/unsigned explorer links, and 30-second polling only while active rounds exist. Background jobs gate progression; completion revisions and committed wallet-change events resume round queries, including jobs completed entirely between UI updates. The existing persisted emergency-email verification/dismissal flow is reused. Suspended wallets hide these Home operations.
- **Version:** the shared generated client posts the current version to `/v0/app_version`. One attempt per app process/version, no retries, failure allows use. The server's decision produces a Home warning, never a blocking update screen. Copy, current/latest version information and mainnet/signet TestFlight defaults match RN; regtest uses signet's destination like RN.
- **QR hub:** raw Lightning-address-only QR, profile display name, public address copy with 1.8-second feedback, and the unavailable-address Profile route. Uses the existing native scanner and Send recipient importer. Camera denial, cancellation and focus loss return to My code. Empty paste is ignored, other inputs reach Send unchanged for validation. Fresh scans reset the old draft; busy or unresolved payments remain protected. Only SendView's model-injection boundary was changed, together with shell routing; Send, Recurring and Onchain implementation logic was left alone.

## Deliberate native differences

- Seed recovery and secure-storage states are retained, including the safe stored-seed-without-database flow. RN's stale-Keychain deletion is not ported. Native session identity verification still occurs before the notification UI decision, and shared wallet/daemon lifetime remains under the existing session owner; this change does not implement RN WalletLoader's best-effort unmount close. Foreground UI/services are gated without changing the launch/session internals under separate review.
- Backup presentation consumes saved status snapshots from the existing coordinator; automatic boarding presentation rereads receipts after successful journal-write events. Neither path polls persisted presentation state. One-shot timers expire success banners after five seconds; historical receipts do not replay. The additive hooks enqueue updates without calling UI observers, adding persistence work, or changing producer decisions. The existing native boarding review/receipt messages remain.
- The existing native scanner recognizes QR only; RN additionally recognizes EAN-13. Scanner paste uses iOS PasteButton consent, and the reused native scanner retains its Cancel/navigation wording. An in-progress native payment is never replaced by a scan.
- Existing native styles are used throughout. This is a behaviour pass, with no design system work.

## Verification

`xcodegen generate` ran before builds. All tests used `Noah-Regtest`, `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, code signing enabled (verified ad hoc arm64 signature), `-parallel-testing-enabled NO`, and DerivedData under `/private/tmp/noah-ios-parity-t3-307ba163`. Function-level Swift Testing filters included `()`.

Targeted: **33 passed / 0 failed / 0 skipped**, across six runs:

- Launch/permission/biometrics: 6.
- Version: 3.
- Home plus existing fiat fixtures: 9.
- QR plus existing Send recipient safety tests: 12.
- Background-job round resumption and uncertain-payment protection regressions: 2.
- Shared version-response/failure cases: 1.

Fixtures generated by the new `apps/fixtures/tools/generate-{push-gate,home-parity,qr-hub,app-version}.mjs` scripts cover 24 push cases, 25 backup cases, 12 round cases, 24 facts, 12 hidden-fiat symbols, 8 QR handoffs and 4 version outcomes. The existing 48 fiat cases were also run.

Full NoahTests, **once after the final code changes**: **537 total / 515 passed / 22 opt-in skipped / 0 failed**, 113 suites. Opt-in skips are existing funded/live payment, S3 backup/restore, server/email integration and RN-upgrade acceptance tests. Machine-readable [targeted results](evidence/targeted-summary.json) and [full results](evidence/full-summary.json) are retained.

Live UI used the Docker Noah server at `127.0.0.1:3000` without operating on containers: created a new wallet through beta acceptance, skipped email, continued with the server-assigned Lightning identity, loaded live fiat, hid/revealed balances, copied the Lightning QR, denied camera permission and recovered, then granted camera permission on this owned simulator. Pasting a malformed Bitcoin request reached Send validation; copying the actual Receive unified request and pasting it through QR hub reached Send's amount step. No payment was submitted.

The real version endpoint returned `minimum_required_version: 0.0.1, update_required: false` for `0.2.0`. To inspect the warning, a temporary loopback proxy forwarded requests to port 3000 and substituted only the version response (`update_required: true`, minimum `1.0.0`). Receive and Send remained accessible while the warning was shown. The proxy was stopped afterwards. This was not a server change.

The local backup upload returned `ClientError`; the live Home failure/Retry state and in-progress banner were visible. A successful S3 round trip was not established. A funded automatic board and an actual pending round were not created; their presentation, expiry and polling are covered by fixtures/view-model tests. Physical push denial/provisional/APNs delivery and camera capture remain device acceptance work; the simulator deliberately bypasses the push gate and has no camera. The preserved seed-safety/beta/biometric logic tests passed, but no new funded upgrade or destructive seed scenario was attempted.

## Screenshots

All ten images were captured on the dedicated simulator, with recovery-phrase screens excluded:

- [Beta warning](evidence/beta-warning.png).
- [Home: fiat, shortcuts, email, facts and backup failure](evidence/home.png).
- [Hidden fiat and bitcoin](evidence/home-hidden.png).
- [Lightning-address QR](evidence/qr-address.png).
- [Copied feedback](evidence/qr-copied.png).
- [Camera denial recovery](evidence/camera-denied.png).
- [Existing scanner with paste fallback](evidence/qr-scanner.png).
- [Scan-to-Send validation](evidence/scan-to-send-validation.png).
- [Actual Receive request routed to Send](evidence/receive-request-to-send.png).
- [Fixture-backed update warning and backup progress](evidence/update-warning.png).

## Cleanup

The simulator's exact name/UDID and Shutdown state were checked before deletion. Its DerivedData and all seven `.xcresult` bundles were removed; only JSON summaries and screenshots remain. See [cleanup evidence](evidence/cleanup.json).

## M1 review fixes — round 1

Background completion is observable through a buffered running/revision snapshot; overlapping jobs still use the existing counter. Home also observes committed wallet changes and retains a refresh requested during an in-flight query. The short-job regression starts and finishes a job before consuming another event and discovers a new pending round with no timer tick.

Idle Home has no presentation timer. The round deadline exists only while rounds are active; wallet events discover rounds from idle and otherwise retain the 30-second deadline (the SDK round query itself writes pending state). Observation and deadlines stop outside the active scene or while the wallet is suspended. Backup and boarding successes share a one-shot earliest-expiry deadline; cancellation leaves current state intact. Hook tests verify uploaded/deduplicated backup snapshots and durable journal receipts/repeat protection.

Shared M1 fixtures now come directly from `home-status.json` (43 predicates, 24 facts, 12 symbols; RN-source generator `tools/generate-home-status.mjs`) and `platform-gates.json` (all 64 rows checked for the iOS push outcome; battery outcomes remain Android-only). The two iOS `future_status` round cases and 24 authorization/permission mapping cases remain. No RN-derived expectation mismatches were found.

Round-1 base: `feat/native-rewrite` at `17e7b76f`. Verified 2026-10-20 on the owned **Noah iOS M1 Fix t3-c145e4aa** iPhone 17 simulator, UDID `12FF3F24-2223-4555-8B6B-0AC028DB463C` (iOS 27.0). `xcodegen generate` preceded builds; `Noah-Regtest`, `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, code signing enabled (arm64 ad hoc signature verified), serial test execution and DerivedData under `/private/tmp/noah-ios-m1-fix-t3-c145e4aa`. Nix development-shell startup did not complete during setup, so the installed XcodeGen and system Xcode tools were used.

Targeted: **50 passed / 0 failed / 0 skipped** across five successful runs: 11 Home tests; 16 Home/status-hook tests; 1 SDK-wallet-event cadence regression; 21 Home/hooks/shared-fixture/push tests; 1 stale-snapshot ordering regression. Function-level Swift Testing filters included `()`. One intermediate test build caught incorrect use of the existing FakeSeed test helper; it was corrected before the successful runs. Background snapshots carry an ordered revision so an older refresh snapshot cannot overwrite a newer start/completion event.

Full NoahTests ran **once after the final code changes**: **553 total / 531 passed / 22 opt-in skipped / 0 failed**, 115 suites. The skips remain the existing funded/live/S3/server/upgrade acceptance tests. No additional live payment, funded boarding, physical push or upgrade acceptance run was attempted in this fix round. Fixture outcomes matched RN; no ambiguous RN predicate required a behaviour change.

Cleanup: the exact simulator name/UDID and **Shutdown** state were confirmed before deletion. The simulator, owned DerivedData and all **seven** `.xcresult` bundles (including the intermediate build failure) were removed. Small JSON result summaries and build logs remain under the same `/private/tmp/noah-ios-m1-fix-t3-c145e4aa-` prefix. Protected simulators, Android devices and Docker were not operated on.
