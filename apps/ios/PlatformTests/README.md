# iOS platform services

Bark 0.5.0's generated `BarkLogger.log(level:target:message:)` is called synchronously by Rust's `log` bridge on arbitrary worker threads (`src/ffi/logger.rs`). `BarkLog` serializes callbacks and never calls Bark from the callback. The SDK retains the registered sink. Like nitro-ark 0.0.144's `bark-cpp/src/lib.rs::init_logger`, all variants use Debug (Trace maps to verbose but is filtered out). RN forwards these to OSLog under `com.nitro.ark` and collects them during log export; native writes them directly through AppLog.

AppLog redacts before OSLog and again at the file boundary. It hides mnemonic-shaped text, labelled secrets (including multiline values), long hex material, WIF/extended private keys, and URLs containing queries. Oversized messages are omitted before regex processing. Writes apply backpressure, with no growing asynchronous log queue. Files remain `Caches/noah_logs/noah.log` plus `.1`–`.4`, each at most 512 KiB; export keeps the newest 4,000 lines. Redaction deliberately also hides long hashes and ordinary URL queries.

## RN Sentry parity

`client/App.tsx` disables Sentry for `__DEV__` and regtest, sets `sendDefaultPii: true`, enables MetricKit and disables the deprecated app-hang detector. It has a committed DSN, not an environment-backed DSN. It does not configure sampling, release, environment, custom breadcrumbs or scrub hooks. Its RN 8.28.0 podspec pins Cocoa 9.29.0; this target uses the same version via SPM.

Native preserves error sampling 1, tracing disabled, production environment and the SDK's `bundleID@version+build` release format (distribution is the build number). The bundle ID distinguishes variants, with an additional `app.variant` tag. Automatic Cocoa breadcrumbs remain enabled. Shared AppLog redaction scrubs breadcrumb messages/data and event messages, parameters, exceptions, tags, contexts and extras recursively, including sensitive dictionary fields. A final serialized-event check drops events with remaining secret-shaped data in other SDK fields. This may discard some diagnostics to protect wallet material. No AppLog files, screenshots, view hierarchies or raw MetricKit payloads are attached; SDK Logs stays disabled.

## Local and CI configuration

- Provide `NOAH_SENTRY_DSN` with the same project DSN currently used by RN. The committed `Config/Sentry.xcconfig` defaults to empty, which disables telemetry. Supply it as an `xcodebuild` setting (for example from CI's `SENTRY_DSN`), or in ignored `Config/Sentry.local.xcconfig`. In xcconfig URL values use `https:/$()/…` to prevent `//` becoming a comment. No credential is committed here.
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
