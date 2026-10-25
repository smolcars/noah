# iOS platform services

Logging, telemetry, Sentry symbol upload, background tasks and App Intents.

## Coverage

- `AppLogTests`, `BarkLogTests`, `AppTelemetryTests`: Bark's logger callback on
  arbitrary worker threads, level parity, bounded admission (256 pending records,
  `[dropped N log records]` on overflow), redaction before OSLog or file persistence,
  rotation (`Caches/noah_logs/noah.log`, 512 KiB per file, export of the newest
  4,000 lines), lifecycle drains, and Sentry options, allowlist and
  fail-closed scrubbing. Redaction runs the shared `apps/fixtures/log-redaction.json`
  cases, which Android's `AppLogTest` also runs.
- `BackgroundTaskTests`, `HeadlessWalletTests`, `PushJobsTests`, `AppDelegatePushTests`,
  `RecurringBackgroundRegressionTests`: the `.refresh` (app refresh) and `.maintenance`
  (processing) BGTask identifiers, headless wallet admission, the shared 25-second push
  deadline and submission budget, OS expiry, and cancellation that never abandons an
  admitted native call.
- `AppIntentTests`: RN and native App Intent names, parameters, authentication and Siri
  phrases, runtime identifiers, and the balance, address and invoice adapters. No intent
  can submit a payment.
- `WalletSessionTests`: wallet admission and SDK observer teardown (see below).
- `PlatformTests/test_sentry_symbols.py`: the generated project's archive-only symbol
  upload phase and `Scripts/upload-sentry-symbols.sh`.

## Configuration

- `Config/Sentry.xcconfig` contains the production project's public DSN. Debug and
  regtest disable telemetry; a blank `NOAH_SENTRY_DSN` also disables it. CI may override
  the setting, or use the git-ignored `Config/Sentry.local.xcconfig`. In xcconfig URL
  values, write `https:/$()/…` so `//` doesn't start a comment.
- For **device Release-Mainnet and Release-Signet archives**, put `sentry-cli` on PATH
  (or set `SENTRY_CLI_EXECUTABLE`) and provide `SENTRY_AUTH_TOKEN`, `SENTRY_ORG` and
  `SENTRY_PROJECT`. Keep the token in CI secrets or the process environment, never in
  xcconfig or the app plist. Eligible archives fail on missing credentials or CLI, or
  on upload failure; Debug, simulator, non-archive and regtest builds skip the upload.
  See [Sentry's symbol-upload documentation](https://docs.sentry.io/platforms/apple/guides/ios/dsym/).

## Running

Run `xcodegen generate` from `apps/ios`, then `python3 PlatformTests/test_sentry_symbols.py -v`
(no credentials or network).

Swift suites run on a simulator with code signing enabled (Keychain tests need the
entitlements), `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, `-parallel-testing-enabled NO` and a
private DerivedData directory. Use `-packageAuthorizationProvider netrc` for public SPM
packages when shared Keychain lookups block. Select suites with
`-only-testing:NoahTests/<Suite>`; a single Swift Testing function needs its trailing
`()`, e.g.
`-only-testing:'NoahTests/BackgroundTaskTests/expiryRevokesInheritedBudgetBeforeShieldedWorkSettles()'`.
Always check the reported count, because a malformed function ID silently runs zero
tests.

## Reading results

`AppLogTests/redactionPerformance()` prints one
`REDACTION <input> CPU 1 KiB … ns; 16 KiB … ns` line per input and fails if the 16 KiB
time exceeds 64 times the 1 KiB time (quadratic rescans). Caller admission must stay
under 5 ms with the writer paused. Android's equivalents are the JVM `AppLogTest` and
the instrumented `AppLogMainThreadTest`, which needs a disposable emulator.

## SDK observer teardown

`WalletSessionTests/closeStopsReceiveObserversAndWaitsForThem()` opens a real wallet,
so it needs the regtest stack (`NOAH_REGTEST_LIVE=1` forces it on, `=0` skips it). To
stress the teardown race described in
[bark-bolt-ffi-notification-teardown.md](../../../docs/native-rewrite/upstream/bark-bolt-ffi-notification-teardown.md),
add `-test-iterations 50` to that selector; Xcode reports one aggregate test, and the
log contains each repetition. On iOS 27.0 / Xcode 27.1 the 50-repetition sample showed
0/50 failures both before and after the observer fix, so it didn't reproduce the
intermittent crash and doesn't measure a reduction; the interleaving is inferred from
the generated Swift driver source. Android's shipped AAR polls sequentially and has no
equivalent queued Swift poll task; it is covered by JVM regressions only.

## Limits

The simulator doesn't deliver BGTasks: `_simulateLaunchForTaskWithIdentifier:` via
LLDB and explicit `BGTaskScheduler` submissions were rejected, so OS callbacks and
expiry are covered by injected entry-point tests. Physical BGTask scheduling, real
silent-push delivery, replaying a stored Siri shortcut, live Sentry ingestion and a
credentialed archive upload need real devices and the team's credentials.
