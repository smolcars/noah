# iOS platform services

Bark 0.5.0's generated `BarkLogger.log(level:target:message:)` is called synchronously by Rust's `log` bridge on arbitrary worker threads (`src/ffi/logger.rs`). `BarkLog` never calls Bark from a callback. File admission orders concurrent callbacks under a short lock; callbacks never wait for the filesystem queue. The bundled BIP39 dictionary is loaded before registering the sink. The SDK retains the registered sink. Like nitro-ark 0.0.144's `bark-cpp/src/lib.rs::init_logger`, all variants use Debug (Trace maps to verbose but is filtered out). RN forwards these to OSLog under `com.nitro.ark` and collects them during log export; native writes them directly through AppLog.

AppLog redacts before OSLog and at file admission. The shared detector covers NFKD/case-folded BIP39 word runs across punctuation/JSON/newlines (omitting the whole message when original offsets are uncertain), credential labels with prefixes/suffixes and punctuation/quote/whitespace separators, URL userinfo on any scheme, Bearer credentials/JWTs, Expo tokens, extended/WIF private keys, labelled hex keys/signatures, presigned URL parameters and email/Lightning addresses. Oversized messages are omitted before regex processing. Unlabelled txids/hashes, ordinary prose, amounts and Ark addresses remain available for diagnostics; URL queries remain conservatively omitted.

The iOS writer admits at most 256 pending records, writes in admission order on a serial queue, and emits `[dropped N log records]` on overflow. Disk work holds no admission lock. Export awaits `flushAsync()` and reads through `linesForExport()`. Active/background lifecycle callbacks and SwiftUI scene notifications drain accepted records for at most 200 ms while execution time remains. Push/BGTask completion uses an asynchronous bounded drain capped by the original deadline; expiry still completes immediately and exactly once. SDK/Rust callbacks only enqueue and never wait on disk. Files remain `Caches/noah_logs/noah.log` plus `.1`–`.4`, each at most 512 KiB; export keeps the newest 4,000 lines.

## RN Sentry parity

`client/App.tsx` disables Sentry for `__DEV__` and regtest, sets `sendDefaultPii: true`, enables MetricKit and disables the deprecated app-hang detector. It has a committed DSN, not an environment-backed DSN. It does not configure sampling, release, environment, custom breadcrumbs or scrub hooks. Its RN 8.28.0 podspec pins Cocoa 9.29.0; this target uses the same version via SPM.

Native preserves error sampling 1, tracing disabled, production environment and the SDK's `bundleID@version+build` release format (distribution is the build number). The bundle ID distinguishes variants, with an additional `app.variant` tag. Automatic Cocoa breadcrumbs remain enabled. Shared AppLog redaction scrubs breadcrumb messages/data and event messages, parameters, exceptions, tags, contexts and extras recursively, including sensitive dictionary fields. String leaves are collected across nested arrays/dictionaries and the whole event, including breadcrumbs, contexts, extras, tags and exceptions. Mnemonic-bearing containers are redacted; unhandled aggregate phrases, unknown objects and graphs deeper than 32 levels fail closed. Dictionary order is undefined, so independent word fragments are checked conservatively even when metadata separates them. The typed SDK severity is excluded from mnemonic aggregation. A final serialized-event check drops events with remaining secret-shaped data in other SDK fields. This may discard some diagnostics to protect wallet material. No AppLog files, screenshots, view hierarchies or raw MetricKit payloads are attached; SDK Logs stays disabled.

## Local and CI configuration

- `Config/Sentry.xcconfig` contains the production project's public DSN. Debug and regtest still disable telemetry; a blank `NOAH_SENTRY_DSN` also disables it. CI may override the setting, or use ignored `Config/Sentry.local.xcconfig`. In xcconfig URL values use `https:/$()/…` to prevent `//` becoming a comment. Upload credentials remain outside the repository.
- For **device Release-Mainnet and Release-Signet archives**, install `sentry-cli` on PATH (or set `SENTRY_CLI_EXECUTABLE`) and provide `SENTRY_AUTH_TOKEN`, `SENTRY_ORG`, and `SENTRY_PROJECT` for RN's Sentry project. Keep the token in CI secrets/the process environment, never xcconfig or the app plist.
- The generated app target has one archive-only symbol phase shared by all three schemes, matching RN's archive/debug/simulator gates and also skipping regtest. It waits for the generated app dSYM and uploads the build's dSYM directory (including extension symbols), without source bundles or wallet/log data. Eligible archives fail on missing credentials/CLI or upload failure. Script environment logging is disabled. App archives do not require a JavaScript/source-map upload.
- See [Sentry's symbol-upload documentation](https://docs.sentry.io/platforms/apple/guides/ios/dsym/). A CI archive and a real Sentry ingestion/symbolication check still require the team's credentials and distribution signing.

## Verification

Run `xcodegen generate` from `apps/ios`, then `python3 PlatformTests/test_sentry_symbols.py -v` (5 tests, no credentials/network). Swift coverage is in `AppLogTests`, `BarkLogTests`, and `AppTelemetryTests`: worker concurrency, level parity, secret examples, oversized messages, rotation limits, feature gates, release/environment/sampling, installed scrub hooks and fail-closed behavior.

Simulator verification uses code signing, `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, `-parallel-testing-enabled NO`, and a private `/private/tmp` DerivedData directory. Use `-packageAuthorizationProvider netrc` for public SPM packages when shared Keychain lookups block. Swift Testing method selectors must end in `()`.

Verified 2026-10-20 on Xcode 27.1 / iOS 27.0, disposable iPhone 17 `Noah Platform Agent 8ab0d3b9`:

- Focused Swift: **14 passed / 0 failed**, in 3 suites (both runs); generated-project/symbol script checks: **5 passed / 0 failed**.
- Full NoahTests, run once at the end: **563 total / 541 passed / 22 skipped / 0 failed**, 117 suites, 52.9 seconds. The skipped tests are the existing opt-in live/integration/upgrade cases; no server, wallet funding or prior RN installation was provisioned for them.
- `xcodegen generate`, signed arm64 simulator build, dSYM generation and the final generated upload-phase dependency all passed. Full-suite SDK wallet tests exercised the installed Bark callback path.
- No live Sentry traffic, device archive or credentialed symbol upload was attempted: these require the team's DSN, upload credentials and distribution signing. Client/server checks and their Husky hook were omitted for these iOS-only commits.
- Owned simulator was identified by name/UDID, shut down and deleted; `/private/tmp/noah-platform-8ab0-dd` and all owned `.xcresult` bundles were removed. Protected/shared devices were untouched.

## iOS platform verification

### Background tasks and App Intents (base 37ab2125, 2026-10-20)

This section covers the native iOS port only. RN sources were read as references.
Android, server and `client/` were not changed.

### RN behavior and native mapping

RN's `client/src/lib/tasks.ts` exports delegated maintenance, forwarded invoice
creation/submission, Lightning claims and backup flush. On iOS those run through
`pushNotifications.ts`/Expo's silent notification task, with heartbeat and
`recurring_payment_due`. Maintenance/backup report the push's k1 outcome; invoice
and claim pushes retain their existing native implementations. Push backup keeps
RN's `requireEnabled: false` behavior.

`recurringBackgroundTask.ts` explicitly restricts its 15-minute periodic
WorkManager task to Android. RN iOS uses server recurring wakes, activation and
one-minute foreground checks; it has no periodic Expo fetch/maintenance task.
The native iOS scheduler adds opportunistic fallback wakes:

- `$(PRODUCT_BUNDLE_IDENTIFIER).refresh`: `BGAppRefreshTaskRequest`, earliest
  15 minutes; sync, the existing `RecurringExecutor.shared.execute()`, widget.
- `$(PRODUCT_BUNDLE_IDENTIFIER).maintenance`: `BGProcessingTaskRequest`, earliest
  6 hours, network required, external power optional; additionally delegated
  maintenance and automatic backup through the existing coordinator, only when
  enabled.

These are earliest dates, not guaranteed intervals. Requests register before
launch returns, reschedule on launch/background/task delivery, preserve pending
dates, and cancel when the persisted wallet is ineligible. XcodeGen owns both
per-variant permitted identifiers and `remote-notification`/`fetch`/`processing`
modes. Existing Info.plist properties were preserved unchanged.

### Admission, lifetime and payment invariants

`WalletSession.withBackgroundAccess` owns admission across awaits. It refuses
an active call, open/close transition, restore/creation reservation, prepared
review or Send hold. Only inherited owner tasks can open/use/close the session;
other jobs and foreground wallet calls see `busy`. Foreground launch/activation
wait for release and reopen if necessary. The existing background-activity
stream covers the whole job and cleanup for push, scheduler and intent work.

Headless entry points validate stores, initialization, suspension, existing DB,
conflicts and readable valid seed. Restore/deletion journals defer to foreground
recovery. They never invoke seed creation, migration, writes or deletion recovery.
`createNew` is also denied under headless ownership. New headless opens force
`runDaemon: false` and close after settlement. An already-open foreground
session retains its existing daemon and remains under foreground lifetime
ownership; headless work does not replace it.

Push and scheduler use the same 25-second monotonic deadline and inherited
`RecurringSubmissionBudget`. OS expiry revokes the budget synchronously, then
cancels the Swift worker and completes exactly once. Foreground entry revokes
active executions too. Cancellation cannot abandon Rust: session ownership
continues until admitted native calls and backup passes settle. No subsequent
stage begins after cancellation. Backups retain their enabled gate, deduplication,
single-flight behavior and cleanup.

Recurring wakes only call the existing executor. Its stop generation, local
policy, durable occurrence/Send journals, ArkInfo fee cap and final submission
budget checks are unchanged. There is no additional payment submission API.
Tests cover expiry during quote followed by one successful later trigger,
revoked budgets inherited by shielded tasks, and real offline SDK handles
closing/reopening after cancellation.

### App Intent continuity

The port keeps the RN type names, parameter names/types, authentication policies,
iOS 26 availability, snippet/copy behavior and all 15 Siri phrases. The app and
widget bundle IDs/kinds remain unchanged. RN's widget is static configuration;
it ships no additional widget intent. There is no RN send intent, and the native
adapter exposes only address creation, receive-invoice creation and balance.
No intent can submit a payment. Copy intents retain immediate foreground mode;
all wallet intents retain `requiresAuthentication`.

Xcode-generated `Metadata.appintents/extract.actionsdata` contains these exact
identifiers, also asserted at runtime against `persistentIdentifier`:

- `CreateNoahArkAddressIntent` — no parameters
- `CreateNoahArkAddressWithSnippetIntent` — no parameters
- `NoahArkAddressSnippetIntent` — `address: String`
- `CopyNoahArkAddressIntent` — `address: String`
- `GetNoahBalanceIntent` — no parameters
- `CreateNoahLightningInvoiceIntent` — `amountSats: Int`, `invoiceDescription: String?`
- `CreateNoahLightningInvoiceWithSnippetIntent` — same invoice parameters
- `NoahLightningInvoiceSnippetIntent` — `invoice: String`, `amountSats: Int`
- `CopyNoahLightningInvoiceIntent` — `invoice: String`

Generated metadata also contains `NoahAppShortcuts` and all three automatic
shortcuts. A source-contract test compares the RN and native names, parameters,
authentication declarations and Siri phrases. Adapter tests exercise balance,
address and both invoice variants, zero/negative amounts, UTF-16 memo limits,
overflow/error handling and clipboard copies. The balance uses the current SDK's
full Ark/onchain totals through the shared session; syncing does not repair or
write a seed.

### Verification and limits


Final targeted run: **85 total / 85 passed / zero failures or skips**, **10
suites**, 9.021 seconds of test execution. Suites: BackgroundTask, HeadlessWallet,
AppIntent, WalletSession, PushJobs, AppDelegatePush, RecurringBackgroundRegression,
RecurringExecutor, RecurringReservation and RecurringFeePolicy.

The full `NoahTests` suite ran **once**, with no suite exclusions: **573 total /
551 passed / 22 opt-in skipped / zero failed**, **118 suites**, 37.781 seconds of
test execution. All runs used Xcode 27.1 through Nix, XcodeGen, normal ad hoc
simulator signing (`CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=-
CODE_SIGNING_REQUIRED=YES`), `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, `-jobs 2`, and
`-parallel-testing-enabled NO`. Owned DerivedData was
`/private/tmp/noah-platform-37ab-dd`. Result/log prefix:
`/private/tmp/noah-platform-37ab-`.

The first focused build caught an async/non-Sendable test-seam isolation issue;
the seam now returns identifier strings on the main actor. The first executed
84-test run had one test-only timing failure: it retriggered before the original
executor invocation finished returning. Waiting for that invocation's settlement
fixed the test; the next 84-test run and final expanded 85-test run passed.
The failed run's Xcode finalizer stalled in package resolution and was stopped
after its complete console results had been retained. No failing full-suite run
was retried.

Suite selection uses `-only-testing:NoahTests/<Suite>`. When selecting a single
Swift Testing function, include the final `()`, for example:
`-only-testing:'NoahTests/BackgroundTaskTests/expiryRevokesInheritedBudgetBeforeShieldedWorkSettles()'`.
Always verify the reported count; a malformed function ID can silently run zero
tests. The full run used only `-only-testing:NoahTests`.

Disposable iPhone 17 / iOS 27.0 simulator: **Noah Platform Tasks 37ab2125**,
UDID `1EDAFB56-687E-412A-BADB-6A7C85A3E59F`. The native app launched normally and
showed Welcome without creating a wallet. LLDB attached only to this app process.
Both `_simulateLaunchForTaskWithIdentifier:` calls were attempted. Explicit
`BGAppRefreshTaskRequest` and `BGProcessingTaskRequest` submissions returned
`NO`; system logs then reported:

> No task request with identifier com.noahwallet.regtest.refresh has been scheduled

The corresponding maintenance identifier had the same result. Thus no actual OS
BGTask callback or OS expiration is claimed. An additional NSError-inspection
expression stalled after suspending the app and was stopped; the owned app and
debugger were terminated. Launch, admission, deadline and expiry behavior is
covered by injected production entry-point tests instead. Physical BG scheduling,
real silent-push delivery and replaying an existing user's stored Siri shortcut
were not tested. Identifier continuity was verified through RN/native source
contracts, runtime identifiers and the native build's extracted metadata; an RN
binary metadata comparison was not performed.

After summaries were exported, cleanup confirmed the simulator's exact name and
UDID, shut it down, verified `Shutdown`, deleted it and verified its absence.
The owned DerivedData and all **five** owned `.xcresult` bundles were removed.
Logs, available summary JSONs, debugger transcripts and cleanup evidence remain
under the `/private/tmp/noah-platform-37ab-` prefix. No protected simulator,
Android emulator, physical device or Docker container was touched.


### Privacy and expiry review fixes (base 1d9aff5d, 2026-10-20)

`apps/fixtures/log-redaction.json` has **67** synthetic/public-vector cases. Both
platforms test exact output and persisted output, including negative examples.
iOS also checks idempotence, recursive telemetry arrays/dictionaries, exception
messages, breadcrumbs, user identity fields and rejection of unhandled serialized
fields. Android changes are confined to the logger and its tests; Sentry is not
implemented there.

Push and BGTask stages check task cancellation, the monotonic deadline and the
synchronously revoked inherited budget. `withOpenWallet` checks before admission
and again after discard cleanup. Already-admitted native work still settles.
The reviewer's `open → maintenance → expire → report → widget` probe now ends
after expiry/maintenance settlement. Regression tests exercise the production
push dispatcher, shielded BG work before Swift cancellation arrives, rejected new
wallet calls, and existing close-after-settlement behavior.

Verification:

- Baseline standalone redaction probes reproduced 24 fixture failures; the push
  probe reproduced the exact five-stage sequence. Both probes pass after the fix.
- Focused iOS: **54 passed / 0 failed**, seven suites. The first expanded build
  caught a Swift 6 test-only isolation error; a lock-protected recorder fixed it.
- Final full `NoahTests`, run **once**: **593 total / 571 passed / 22 existing
  opt-in skips / 0 failed**, 120 suites, 45.000 seconds. Normal simulator signing,
  `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, `-jobs 2`, no parallel testing, XcodeGen and
  `-packageAuthorizationProvider netrc` were used through Nix. Swift Testing
  function selectors require the trailing `()`; these runs selected whole suites.
- Sentry symbol-script/project checks: **5 passed / 0 failed**.
- Android's requested full `:app:testRegtestDebugUnitTest
  :app:assembleRegtestDebug` command ran once through Nix with `--max-workers=2`
  and passed. Final focused logger tests: **7 passed / 0 failed**, all 67 vectors.
  A later focused run replaced the full JUnit XML, so **2,894 tests / 84 suites**
  were counted with Gradle's `--test-dry-run` discovery (no second full execution).
  The final credential-label expansion also passed focused tests and APK assembly.
- No release archive, live Sentry ingestion, real silent push or physical BGTask
  scheduling was attempted. These checks used injected expiry and offline SDK
  handles. Existing integration skips require separately provisioned environments.

Owned simulator: **Noah Privacy t3-0c29**, iPhone 17 / iOS 27.0,
`1276729F-75DA-4C05-9289-E153B4343542`. The Device panel twice could not find this
new CLI-created device; verification used Xcode CLI. Cleanup identified the exact
name/UDID, shut it down, verified `Shutdown`, deleted it and verified absence.
Owned `/private/tmp/noah-privacy-0c29/dd` and all four owned `.xcresult` bundles
were removed after summary export. Text logs and summary JSON remain under that
prefix. Protected/shared devices, Android emulators, physical devices and Docker
were untouched.


### Round-2 privacy and expiry fixes (base 172446b6, 2026-10-20)

The shared logger fixture now has **99 cases**: the original **67 are unchanged**,
plus 32 public/synthetic regressions and negatives. Both platforms load the same
file and assert exact redaction and persisted output, with no platform-specific
case exclusions. Added coverage includes prefixed/camelCase secret labels,
whitespace/quote/JSON separators, fullwidth/mathematical/circled BIP39 phrases,
mixed-script negatives, scheme-independent URL userinfo (IPv4/IPv6/localhost),
and real-format Bitcoin/Ark addresses and BOLT11 invoices.

Permanent probe-derived coverage:

- `AppLogTests.sharedSecretRedactionVectors` and Android `AppLogTest` cover the
  label, Unicode and URL probes. `normalizedWalletPhraseCannotReachLogs` also
  verifies that the fullwidth public vector is accepted by `StoredSeed`.
  `sharedSecretsAreScrubbedAtEveryTelemetryBoundary` checks each fixture in
  messages, exceptions, contexts and breadcrumbs, plus the serialized-event guard.
  Boundaries are tested independently: repeating an 11-word excerpt across an
  event now intentionally triggers aggregate detection.
- `AppTelemetryTests.nestedWordObjectsMustNotReachTelemetry` retains the exact
  nested word-object probe. `detectsMnemonicLeavesAcrossNestedContainers` adds
  contexts, tags, breadcrumbs, exception values and a phrase split across extra
  and context; unknown-object/request-header rejection remains covered.
- `HeadlessWalletTests.expiredShieldedTaskCannotStartWalletOpen` covers revoked
  budgets, elapsed deadlines and cancellation for both `ensureOpen` and
  `openExisting` (six cases). No opener runs, and a later reservation succeeds.
  Open admission is checked again immediately before the native opener; caller
  cancellation revokes admission without cancelling already-started native work.
  Existing real-handle settlement/close/reopen tests still pass. Android checks
  cancelled WorkManager/push entry and revoked detached recurring budgets before
  wallet opening; its session and `RecurringExecutor` were not modified.
- `AppDelegatePushTests.backgroundCompletionDrainsAcceptedRecords` replaces the
  probe's permanently suspended writer with a writer released inside the budget,
  proving both push and BGTask completion wait for accepted records. Separate
  tests cover active/background callbacks, bounded stalled-writer draining with
  ordered overflow markers, and exactly-once completion when expiry interrupts
  a drain. Expiry never waits for disk; timed-out drains remain best effort.

Verification evidence is retained as text/JSON under
`/private/tmp/noah-privacy-r2-9499/`:

- Before fixes, the iOS targeted baseline executed 23 Swift Testing functions
  and recorded **90 assertion issues**, including the nested objects, native
  open after revocation and both missing completion drains. Android's expanded
  baseline executed **23 tests / 6 failures**, including the three redaction
  probes and both background-admission regressions.
- The combined focused Android run passed **23 tests** (10 logger, 10 push, three
  deadline), and the requested full test/build command ran **once** and passed:
  **2,916 tests / 89 suites / zero failures or skips**, plus regtest APK assembly.
- The focused iOS run passed 78 of 79 functions; the remaining test duplicated a
  short mnemonic excerpt across fields. After separating boundary checks and
  excluding the typed SDK severity from aggregation, the complete telemetry
  suite passed **11 / 11**. All logger vectors, six wallet-open expiry cases,
  lifecycle drains, native settlement and exactly-once completion passed.
- Sentry symbol/project checks: **5 passed / zero failures**. One intermediate
  build encountered a stale initializer link while the test seam was changing;
  rebuilding its affected module resolved it. The baseline's Xcode finalizer
  stalled collecting simulator diagnostics after tests had finished; its stack
  sample and complete console results were retained before stopping that owned
  process. Later runs used `-collect-test-diagnostics never`.

- Full `NoahTests` ran **once**, with no suite exclusions: **611 total / 587
  passed / 23 existing opt-in skips / one failure**, 122 suites. All privacy,
  wallet-open expiry and log-drain regressions passed. The single failure was a
  host crash in the existing
  `WalletSessionTests.closeStopsReceiveObserversAndWaitsForThem()` test: the
  pinned Rust SDK panicked in Tokio `oneshot.rs:1291` with `called after complete`.
  Xcode restarted the host and completed the remaining tests; the original
  failure is retained. That test passed in the earlier focused run and again
  in a final isolated run (**1 / 1 passed**, 0.255 seconds). Its root cause was
  not established; no SDK or receive-observer implementation changes were made,
  and no second full suite was run.
- iOS used Xcode 27.1 / iOS 27.0, XcodeGen, normal ad hoc simulator signing,
  `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, `-jobs 2`, no parallel testing and
  `-packageAuthorizationProvider netrc`, through Nix. Android used the exact
  requested Nix/Gradle command with `--max-workers=2`.

No release archive, live Sentry ingestion, real silent-push delivery or physical
BGTask scheduling was attempted. The production DSN and debug/regtest gates are
unchanged. The bounded drain can time out on a stalled disk; expiry completion
and SDK/Rust callbacks still never wait for that disk work.

Owned simulator **Noah Privacy R2 9499**, iPhone 17 / iOS 27.0,
`037EB7C8-5110-4861-A796-F83C0F560FB9`, was identified by name/UDID, shut down,
verified `Shutdown`, deleted and verified absent. The owned DerivedData and all
**seven** owned `.xcresult` bundles were deleted after exporting summaries.
`cleanup.json`, test summaries and text logs remain under the evidence prefix.
Protected/shared simulators, Android emulators, physical devices and Docker were
untouched. `client/`, server, recurring executors and MMKV recovery code were not
changed.

A final audit retained the older space-separated `seed phrase` label grammar and
added a 99th shared regression. Before this correction the standalone Swift
harness failed exactly that case; afterward all **99** passed exact, idempotence
and persistence checks. The final focused Android logger suite passed **10 / 10**
with all 99 vectors. This correction followed the one full-suite run; that run
used the earlier 98-vector fixture and is not represented as testing this final
spelling. The final native iOS logger/telemetry run passed **20 / 20 tests**, with all
99 cases passing exact, idempotence and persisted-output checks.

That final native run used a second owned iPhone 17 / iOS 27.0 simulator,
**Noah Privacy R2 Final 9499**, `50A53815-AD20-4E29-AA38-5E788882C1A0`.
Its name/UDID and `Shutdown` state were confirmed before deletion; absence was
verified afterward. Its separate `dd-final` and result bundle were removed.
Across both owned devices, **two DerivedData directories and eight result
bundles** were removed. Temporary standalone harness sources/binaries were also
removed; their before/after result logs and native summaries remain.
