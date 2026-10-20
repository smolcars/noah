# iOS platform services

Bark 0.5.0's generated `BarkLogger.log(level:target:message:)` is called synchronously by Rust's `log` bridge on arbitrary worker threads (`src/ffi/logger.rs`). `BarkLog` never calls Bark from a callback. File admission orders concurrent callbacks under a short lock; callbacks never wait for the filesystem queue. The bundled BIP39 dictionary is loaded before registering the sink. The SDK retains the registered sink. Like nitro-ark 0.0.144's `bark-cpp/src/lib.rs::init_logger`, all variants use Debug (Trace maps to verbose but is filtered out). RN forwards these to OSLog under `com.nitro.ark` and collects them during log export; native writes them directly through AppLog.

AppLog admits raw records under a short lock, then redacts messages and tags on the serial writer before OSLog or file persistence. Export reads only those redacted files. The shared detector covers NFKD/case-folded BIP39 word runs across punctuation/JSON/newlines (omitting the whole message when original offsets are uncertain), credential labels with prefixes/suffixes and punctuation/quote/whitespace separators, URL userinfo on any scheme, Bearer credentials/JWTs, Expo tokens, extended/WIF private keys, labelled hex keys/signatures, presigned URL parameters and email/Lightning addresses. Oversized messages are omitted before regex processing. Unlabelled txids/hashes, ordinary prose, amounts and Ark addresses remain available for diagnostics; URL queries remain conservatively omitted.

The iOS writer admits at most 256 pending records, writes in admission order on a serial queue, and emits `[dropped N log records]` on overflow. Disk work holds no admission lock. Export awaits `flushAsync()` and reads through `linesForExport()`. Active/background lifecycle callbacks and SwiftUI scene notifications start an asynchronous drain and return immediately. A UIKit background execution lease bounds the drain to the smaller of 200 ms and the remaining OS execution time; it ends exactly once on drain completion or expiry. Push/BGTask completion uses an asynchronous bounded drain capped by the original deadline; expiry still completes immediately and exactly once. SDK/Rust callbacks only enqueue and never wait on redaction or disk. Files remain `Caches/noah_logs/noah.log` plus `.1`–`.4`, each at most 512 KiB; export keeps the newest 4,000 lines.

## RN Sentry parity

`client/App.tsx` disables Sentry for `__DEV__` and regtest, sets `sendDefaultPii: true`, enables MetricKit and disables the deprecated app-hang detector. It has a committed DSN, not an environment-backed DSN. It does not configure sampling, release, environment, custom breadcrumbs or scrub hooks. Its RN 8.28.0 podspec pins Cocoa 9.29.0; this target uses the same version via SPM.

Native preserves error sampling 1, tracing disabled, production environment and the SDK's `bundleID@version+build` release format (distribution is the build number). The bundle ID distinguishes variants, with an additional enum-valued `app.variant` tag. Round three replaces recursive scrubbing with a fixed event allowlist and disables default PII. Arbitrary payloads, identity, request data and attachments are dropped; approved messages use the shared redactor and every remaining string/key passes a final fail-closed guard. Automatic Cocoa breadcrumbs keep only category, level, timestamp and a redacted message. See the round-three section below for the exact fields, shared detector vectors and verification.

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

### Round-3 telemetry allowlist and detector fixes (base 4f7228c5, 2026-10-20)

Sentry now constructs a fresh `Event` from a fixed allowlist rather than trying to
scrub arbitrary object graphs. Kept fields are event ID, timestamp, severity,
platform, release, dist and environment; SDK name/version, integration/feature
names and package name/version; exception type/module/thread ID and mechanism
(type/handled/synthetic); thread ID/name/current/crashed/main; stack-frame
function/module/filename/line; and the following scalar context fields:

- App: type, app_identifier, app_version, app_build, app_start_time, app_memory,
  in_foreground, build_type.
- Device: type, family, model, model_id, arch, battery_level, charging, simulator,
  memory_size, free_memory, usable_memory, storage_size, free_storage, boot_time,
  processor_count, cpu_description, screen_width_pixels, screen_height_pixels,
  screen_density, screen_dpi, orientation, thermal_state, low_memory.
- OS: type, name, version, build, kernel_version, rooted.

The only tag is `app.variant`, restricted to mainnet/signet/regtest. Event and
exception formatted messages and breadcrumb messages use the shared redactor;
uncertain decoding or oversized messages are omitted entirely. Breadcrumbs keep
only category, severity, timestamp and the redacted message. All other fields
are dropped: extras, arbitrary contexts, user (no app-set anonymous install ID
exists), request, transaction, fingerprint, logger/server name, modules/debug
images, message templates/parameters, mechanism data/meta/help, stack registers,
frame variables/source context and breadcrumb data/type/origin. SDK and context
objects accept only their explicitly listed shapes and scalar fields.
`sendDefaultPii` is false. Screenshots, hierarchy, SDK Logs and raw MetricKit
payloads remain disabled. Both zero attachment allowance and the authoritative
`beforeSendWithHint` attachment-list removal prevent scope/crash attachments.
The production DSN, debug/regtest gates, sampling and release format are unchanged.

The final guard checks every remaining string, including dictionary keys, and
rejects changed strings, unknown types, deep graphs and oversized aggregation.
Keys and values are aggregated separately so fixed schema names (e.g. `message`)
cannot turn a safe 11-word excerpt into a phrase. Keys still undergo aggregate
mnemonic detection, including the reviewer's twelve distinct mnemonic keys.
Conservative aggregate detection can discard otherwise useful diagnostics.

Both loggers inspect up to four JSON-unescaped views, covering escaped quotes,
backslashes, controls and `\\uXXXX` labels. Hits in decoded views redact the whole
original message because offsets cannot be mapped safely. Decoder errors or
remaining escapes beyond the bound fail closed. The redaction-only mnemonic
skeleton strips default-ignorable code points and combining marks after NFKD and
maps common Cyrillic/Greek/Latin lookalikes. Skeleton phrase hits omit the whole
message. `StoredSeed` validation, logger ordering/overflow and lifecycle drains
were not changed.

The shared fixture has **147 cases**: all original **99 input/name pairs** plus
48 new public/synthetic probes and negatives. With explicit user approval, only
`r2-negative-cyrillic` and `r2-negative-combining` changed expected output to
`[redacted]`: both are complete disguised public mnemonics and are positives
under round three. Every other original expected output is unchanged, including
dotless-letter text, ordinary words, amounts, txids, Bitcoin/Ark addresses and
BOLT11 invoices. Both platforms load the same fixture with no platform skips;
new negatives include ordinary Cyrillic, Greek, Arabic, Chinese, Japanese,
accented prose and safe escaped diagnostics.

Permanent probe-derived coverage includes the mnemonic dictionary-key shape,
large dropped extras, escaped telemetry JSON, all eleven escaped-label probes,
single/double/triple JSON escaping, Unicode-escaped credential labels, internal
ZWJ/ZWNJ/ZWSP/word joiner, soft hyphens, combining marks, variation selectors and
homoglyph public vectors. iOS also asserts that visually recoverable spellings
remain invalid `StoredSeed` inputs, unknown/deep final-guard objects fail closed,
source/variable context and identity/request data are removed, and attachments
are removed by the installed hook.

Verification evidence is retained as text/JSON under
`/private/tmp/noah-privacy-allowlist-f558/`:

- Baseline iOS: **23 tests / 18 passed / five failed**, with 251 assertion issues
  across the allowlist, dictionary-key and escaping/shared-fixture probes.
  Baseline Android logger: **10 tests / one failed**, plus both independently
  selected escaped-credential and skeleton probes failed (**two / two**).
- The first combined post-fix focused run exposed the two now-obsolete fixture
  expectations, two new negative inputs with trailing spaces, and the fixed
  `message` schema key completing an 11-word negative. These were corrected
  before final verification. No detector exception or platform skip was added.
- Final focused iOS: **33 passed / zero failures**, three suites, all 147 shared
  vectors including exact output, idempotence and persistence. Final focused
  Android logger: **12 passed / zero failures**, all 147 vectors including exact
  output, idempotence and persistence. The copied Android test resource's SHA-256
  matches the shared source fixture.
- Full `NoahTests` ran **once after the last code change**, with no suite
  exclusions: **641 total / 618 passed / 23 existing opt-in skips / zero failed**,
  123 suites, 108.742 seconds of Swift Testing execution. No observer-teardown
  panic or host restart occurred in this run.
- The exact requested Android full test/build command ran **once** through Nix
  with `--max-workers=2`: **2,956 tests / 92 suites / zero failures or skips**,
  followed by successful `:app:assembleRegtestDebug` (2 minutes 37 seconds total).
- iOS used XcodeGen and Xcode 27.1 through Nix, normal ad hoc simulator signing
  (`CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- CODE_SIGNING_REQUIRED=YES`),
  `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, two jobs, no parallel testing,
  `-packageAuthorizationProvider netrc` and `-collect-test-diagnostics never`.
  Individual Swift Testing selectors included the required trailing `()`.
  One initial setup command found no `xcodegen` on Nix PATH; using
  `/opt/homebrew/bin/xcodegen` resolved it before any baseline tests executed.
- Sentry symbol-script/project checks: **five passed / zero failures**.

Android still has logger privacy only; adding Sentry is outside this round.
Android cancellation/stage admission, iOS lifecycle main-thread drain blocking
and the SDK observer-teardown crash were explicitly excluded; WalletSession,
PushJobs, RecurringExecutor, AppDelegate, MMKV recovery, client and server were
not edited. No live Sentry ingestion, credentialed release archive/symbol upload,
real push or OS background scheduling was attempted.

Owned simulator: **Noah Privacy Allowlist f558**, iPhone 17 / iOS 27.0,
`3450F549-460C-4921-AE7E-C118E8814BDC`. Its exact name/UDID and `Shutdown`
state were verified before deletion, and absence was verified afterward. The
owned DerivedData and all **six** owned result bundles were removed after
exporting summaries. Cleanup evidence, logs and test summaries remain under the
evidence prefix. Protected/shared simulators, Android emulators, physical devices
and Docker were untouched.

## Native background admission, lifecycle drains and observer teardown (2026-10-20)

Base: `feat/native-rewrite` at `32e1b6ef`. Scope: native wallet admission,
Android push stages, iOS lifecycle drain and SDK observer lifetime only.
RecurringExecutor/RecurringStore/RecurringPolicyLedger, settings/MMKV/launch
gates, log redaction/telemetry, RN and server were unchanged.

Android carries the caller's cancellation and push budget across uncancellable
native settlement, checks immediately before opening or starting a new body,
after discard cleanup, and after waiting for a send reservation. Creation and
verification preserve the same admission boundary. Once admitted, native work
and cleanup still finish before close. FCM and UnifiedPush enqueue the same
WorkManager runner; every independent push stage checks the inherited 25-second
budget and cancellation, including reporting, notification publication and
widget refresh. This does not impose a 25-second interruption on an admitted
native call.

iOS lifecycle callbacks no longer wait for the writer semaphore. They start a
UIKit background task and use the existing exactly-once BackgroundExecution
completion path with a deadline capped at 200 ms and remaining execution time.
The writer barrier and dropped-count marker retain serial admission order.
`LogFile.drainAsync` races the writer barrier with a dispatch timer through an
atomic once-only continuation, without parking a main or worker thread. Push
and BGTask expiry still complete immediately even if the writer has stalled.

Receive and state-change readers stop via the SDK's per-wait native cancel
method, then return normally before wallet close. They are never cancelled as
Swift tasks while an FFI future is pending. Native stop retries every 5 ms cover
the SDK's unregistered-wait gap. Closing from a cancelled caller still suspends
between retries. Stream consumer cancellation follows the same shutdown path.
Permanent coverage includes 50 immediate receive shutdowns, 25 pending
receive/state-change shutdown cycles (both close and consumer cancellation),
and a deterministic native-wait-registration-gap test.

The upstream draft [bark-bolt-ffi-notification-teardown.md](../../../docs/native-rewrite/upstream/bark-bolt-ffi-notification-teardown.md)
records the generated Swift driver's cancellation/queued-repoll lifetime race,
historical crash stacks, suggested upstream fix and Android audit. The atomic
Swift continuation prevents a second Swift resume; it does not protect a queued
native poll from cancellation/free. A literal second call of the native
completion export was not established. Android's shipped AAR has a sequential
coroutine poll loop and removes continuation handles once; it has no equivalent
queued Swift poll task. Its existing observer cancellation/close implementation
is retained, with JVM regressions rather than a claim of native-race immunity.

Verification evidence and commands: `/private/tmp/noah-reliability-6ec1/`.
The prior agent's evidence is `/private/tmp/noah-background-1504133e/`; its long
observer stress run was interrupted and is not used as a completed measurement.

Results on the owned iPhone 17 / iOS 27.0 simulator `Noah Reliability 6ec1`
(`2DFDE5EB-544B-49EC-B2BF-8E4DD4CC9719`), Xcode 27.1:

- Original observer implementation: **0 failures / 50 repetitions (0%)** of
  `closeStopsReceiveObserversAndWaitsForThem()`. Fixed implementation: **0 / 50
  (0%)** using the same selector and `-test-iterations 50`. Xcode reports one
  aggregate test for each 50-repetition run; the logs contain all 50 repetition
  starts. Neither sample reproduced the intermittent crash, so no measured
  reduction in failure rate is claimed. Two historical faulting stacks confirm
  that the notification-poll crash has occurred; the exact interleaving remains
  inferred from the driver source.
- Lifecycle baseline: **7 test functions / 5 passed / 2 failed**, with failures
  in all three stalled-writer callback cases (about 200 ms each) and both
  delayed-writer cases (over 50 ms). Fixed focused iOS: **32 passed / 0 failed**,
  four suites, including lifecycle latency, ordered overflow-marker drain,
  expiry/exactly-once completion, wait-registration race and repeated shutdowns.
- Android added admission regressions: **two / two failed** before the extra
  reserved/create admission checks; the mutex regression independently failed
  with its new check removed. Final focused wallet/push run: **45 passed / 0
  failed**. The cherry-picked commits' earlier failing wallet/push evidence is
  retained under the prior evidence prefix.
- Full `NoahTests` ran **once after the last code change**: **655 total / 632
  passed / 23 existing opt-in skips / 0 failed**, 125 suites, 168.383 seconds of
  Swift Testing execution. No SDK observer panic or test-host restart occurred.
- The exact requested final Android command ran **once** through Nix:
  `nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'`.
  **2,972 tests / 95 suites / 0 failures / 0 skips**, followed by a successful
  regtest debug APK assembly; 3 minutes 30 seconds total.
- iOS regenerated with `xcodegen generate` through Nix before the final suite,
  with `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, two jobs, no parallel testing,
  `CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- CODE_SIGNING_REQUIRED=YES`,
  `-packageAuthorizationProvider netrc` and `-collect-test-diagnostics never`.
  Individual method selectors retained the required trailing `()`.

The evidence directory keeps text logs, result summaries, Android XML results,
the generated Swift driver excerpt, Android driver bytecode and historical
stack excerpts. Owned DerivedData and all six result bundles were deleted. The
owned simulator's exact name/UDID and Shutdown state were verified before
deletion, and absence was verified afterward. All owned builds completed;
protected/shared simulators, physical devices, emulators and Docker were untouched.
No real push delivery, OS scheduling, Android JNI stress test or external issue
filing was attempted. Client/server checks and their Husky hook were omitted
for these native-only commits. SDK crash reproduction is still intermittent;
the measured baseline was clean, and the upstream diagnosis is explicitly an
inference rather than a demonstrated second completion call.


## Native logging performance and background redaction (2026-10-20)

Base: `feat/native-rewrite` at `b6b3538d`. The fixture
`apps/fixtures/log-redaction.json` is unchanged: **147/147 exact outputs on each
platform**, including persisted output. Both original detectors also matched
the revised detector on **10,280 generated synthetic inputs per platform**.
The temporary differential harnesses were removed; punctuation/boundary probes
remain in both platform test suites. Only public BIP39 vectors and fake tokens
were used.

Each detector scans maximal label, email, scheme and JWT tokens once, instead of
retrying an unbounded regex at every internal word boundary. Constant-size
keyword alternatives and possessive runs handle the remaining patterns. Domain
labels are scanned without recursive domain-group backtracking; successful
replacements are assembled in one pass. Swift also caches the NSString view
used for mnemonic lookup and assembles decoded views without repeated suffix
copies. The BIP39 sets, **16 KiB UTF-8 omission limit**, and **four decode layers**
remain unchanged. No fixture expectations, seed-validation rules or Sentry
options changed.

Both writers admit at most **256 raw records**, preserving admission order under
a short lock. Raw text exists only in the bounded memory queue. Redaction of
both message and tag runs on the serial background writer before OSLog/Logcat
and file persistence; iOS telemetry can only observe the redacted app-log sink
text. Its independent Cocoa event/breadcrumb scrub hooks retain their existing
allowlist and fail-closed guard. Overflow still emits `[dropped N log records]`
in order. Swift retains `flushAsync()` and bounded lifecycle drains; Kotlin adds
a suspending `flushAsync()` and ordered file/export barriers. Existing synchronous
snapshot/flush APIs intentionally wait for the writer; logging calls do not.

With explicit user approval, the iOS SDK error call path uses
`SafeErrorDescription.forLogging` for AppModel/receive log messages. It returns
SDK text only for immediate admission to AppLog's queue. `SafeErrorDescription.of`
continues to redact before server reports; changing it to return raw text would
have leaked outside the logger.

### Timing method and budgets

`redactionPerformance` covers ASCII punctuation (`!@#$%^&*()[]{}:;,?`), hyphens
(`x-`), dots (`x.`), digits (`1234567890`), mixed words
(`wallet sync completed xyzzy `), and literal Unicode escapes
(`\u0078\u002e`), repeated and truncated to exactly **1, 4 and 16 KiB**.
Missing terminators/repeated candidates (`seed.`, `http://`, `ExpoPushToken[`,
`x@x.`, `xprv`, separated Unicode escapes) have separate regressions.

The automated CI budget is **100 ms for the median of three warm redactions of
each input**, with one warmup outside measurement. This leaves margin above the
roughly 50 ms target on shared, unoptimized CI machines. A single-sample version
observed unrelated scheduling/GC pauses above 200 ms during concurrent builds;
three samples prevent one such pause from failing a fast detector. Sustained
quadratic work still fails. Caller admission is timed separately against a
**5 ms maximum**, with the writer paused and both long tags and messages; iOS
runs on `@MainActor` and also exercises Bark callbacks. Android has a JVM caller
test and a separate instrumented `Looper.getMainLooper()` test.

The following values are **milliseconds** on this shared Apple Silicon host;
load was not controlled, so small differences are noise. Swift before/after are
single warm samples of the exact iOS source in a macOS `swiftc`/Foundation/OSLog
harness (`-Onone`; only the bundled dictionary URL is substituted); its 16 KiB
pathologies were stopped at 10 seconds. Android before is the failing original
Gradle/JVM performance test, and after is the final focused test's median of
three. These are host measurements, not physical-device benchmarks.

| Input | KiB | Swift before | Swift after | Android JVM before | Android JVM after |
| --- | ---: | ---: | ---: | ---: | ---: |
| punctuation | 1 | 0.628 | 0.420 | 4.764 | 0.577 |
| punctuation | 4 | 1.708 | 1.278 | 3.910 | 1.932 |
| punctuation | 16 | 3.921 | 5.023 | 10.304 | 2.753 |
| hyphen | 1 | 729.416 | 0.594 | 99.100 | 0.290 |
| hyphen | 4 | 9444.029 | 1.900 | 692.387 | 1.134 |
| hyphen | 16 | >10,000 | 21.382 | 9235.973 | 9.130 |
| dot | 1 | 331.592 | 1.031 | 38.367 | 0.299 |
| dot | 4 | 6243.736 | 4.713 | 480.522 | 1.171 |
| dot | 16 | >10,000 | 16.541 | 7777.336 | 8.288 |
| digit | 1 | 0.810 | 0.349 | 0.771 | 0.201 |
| digit | 4 | 5.154 | 2.249 | 2.112 | 0.777 |
| digit | 16 | 10.570 | 4.033 | 4.721 | 15.092 |
| mixed | 1 | 0.812 | 1.216 | 0.739 | 0.747 |
| mixed | 4 | 5.128 | 8.662 | 1.349 | 1.090 |
| mixed | 16 | 13.121 | 31.044 | 4.891 | 32.547 |
| escaped | 1 | 6.307 | 3.062 | 12.188 | 0.368 |
| escaped | 4 | 158.182 | 10.982 | 20.480 | 1.765 |
| escaped | 16 | 3492.642 | 36.901 | 233.123 | 9.756 |

The signed iOS 27.0 simulator's expanded focused run independently measured
16 KiB medians of **4.232 / 5.994 / 12.827 / 3.449 / 14.069 / 18.171 ms** in the
same input order. Its maximum main-actor/SDK admission was **0.002458 ms**;
Android's JVM admission maximum was **0.020167 ms**. A separate Swift host probe
of 2 KiB dot text measured redaction **2,741.435 → 1.997 ms**, and `AppLog.e`
**12,760.543 → 0.0335 ms** (flush excluded from caller timing). These samples were
also taken under shared host load.

### Changed code and verification

- `apps/ios/Noah/Services/Logging/AppLog.swift:25`, `:76`: admission before
  redaction/OSLog; `:239`, `:319`, `:378`, `:428`, `:457`: bounded decoding,
  mnemonic lookup and token/domain/label scans.
- `apps/ios/Noah/Services/Logging/SafeErrorDescription.swift:14`,
  `apps/ios/Noah/App/AppModel.swift:207`, `:258`, and
  `apps/ios/Noah/Features/Receive/ReceiveView.swift:131`: defer log-only SDK
  inspection, preserving server-report scrubbing.
- `apps/ios/NoahTests/AppLogTests.swift:10`: performance, main-actor/SDK
  admission, SDK-error routing and boundary regressions; existing fixture,
  overflow, lifecycle, rotation and export tests remain in place.
- `apps/android/app/src/main/java/com/noahwallet/services/logging/AppLog.kt:31`,
  `:51`, `:103`, `:121`: bounded writer, sink ordering and barriers; `:195`,
  `:239`, `:261`, `:296`: detector scans and one-pass replacements.
- `apps/android/app/src/test/java/com/noahwallet/services/AppLogTest.kt:18`:
  performance/caller latency, sink isolation, ordered overflow/async flush and
  boundary regressions; rotation/export tests explicitly await the async writer.
- `apps/android/app/src/androidTest/java/com/noahwallet/services/AppLogMainThreadTest.kt:18`:
  real Android main-looper admission test with paused writer/sinks.

Evidence: `/private/tmp/noah-redaction-1a25/` (text, JSON and JUnit XML).
Expanded focused iOS: **38 functions / 43 expanded runs / zero failures** in
three suites; Android: **17/17 focused tests**, **1/1 differential harness**, plus
successful instrumentation APK assembly. Final suite/build results and cleanup
are recorded below.


Final verification after the last code change:

- Full `NoahTests` ran **once**: **675 total / 652 passed / 23 existing opt-in
  skips / zero failures**, 131 suites, 140.563 seconds of Swift Testing execution
  (950 expanded passing runs). The final full run's six 16 KiB medians were
  **7.738 / 11.107 / 18.861 / 6.471 / 29.204 / 38.489 ms**; maximum main-actor/SDK
  admission **0.002375 ms**. All shared-fixture persistence and telemetry checks
  passed. Existing opt-in live/integration/upgrade skips were not provisioned.
- iOS ran through Nix with `xcodegen generate`, signed arm64 simulator builds,
  `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, `-jobs 2`, no parallel testing,
  `CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- CODE_SIGNING_REQUIRED=YES`,
  `-packageAuthorizationProvider netrc` and `-collect-test-diagnostics never`.
  Whole-suite selectors were used; individual Swift Testing selectors still
  require trailing `()`.
- The exact final Android command ran **once**:
  `nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'`.
  **2,993 tests / 103 suites / zero failures or skips**, plus successful APK
  assembly, in 2 minutes 12 seconds. All 147 shared fixtures were checked.
  Full-run 16 KiB medians were **4.806 / 7.555 / 6.111 / 4.678 / 7.489 / 7.301 ms**;
  JVM caller admission maximum **0.003167 ms**.
- `:app:assembleRegtestDebugAndroidTest` built the actual Android main-looper
  regression. It was **not executed**: this environment has no Android emulator
  binary or installed system image, and protected/shared physical devices were
  explicitly excluded. The JVM caller/paused-sink regressions did run. No physical
  performance measurements, live Sentry ingestion or credentialed release archive
  was attempted; simulator/JVM tests and installed Sentry scrub-hook assertions
  cover this change. Sentry options/session tracking are untouched.

Owned iPhone 17 / iOS 27.0 simulator **Noah Redaction 1a25**,
`AE58E872-5073-4EE4-8C52-E4E1956C36C6`, was identified by name/UDID, shut down,
verified `Shutdown`, deleted and verified absent. The Device panel could not
resolve this CLI-created simulator; Xcode used its exact UDID. Owned
`/private/tmp/noah-redaction-1a25-dd` and all **three** result bundles were removed
after exporting summaries. No owned build remains running. Protected simulators,
Android devices, physical devices and Docker were untouched. Settings/MMKV,
launch gating, recurring payments, `server/` and `client/` were not edited.
Client/server Husky checks were skipped for the native-only platform commits.
