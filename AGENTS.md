# AGENTS.md

## Purpose

This document is the source of truth for autonomous agents working in the Noah monorepo.
It is intentionally operational: where code lives, how the runtime behaves, and how to make safe changes that pass CI.

## Project summary

- Noah is a self-custodial Bitcoin wallet for Ark (L2).
- It ships as two native apps with the same features, plus a Rust server:
  - `apps/ios/`: Swift 6 + SwiftUI app and widget.
  - `apps/android/`: Kotlin + Jetpack Compose app and widgets.
  - `server/`: Rust Axum backend.
- `client/` is the previous React Native app. It is no longer released; don't change it unless asked.

## Monorepo map

- Root
  - `justfile`: primary dev command surface.
  - `flake.nix`: Nix development environments (JDK 17, Android SDK, SwiftFormat/SwiftLint, ktlint/detekt).
  - `Cargo.toml`: workspace root (member: `server`).
  - `package.json`: Bun workspace for repository scripts and translation tooling.
  - `api/openapi.json`: the server's exported API contract; both apps' clients are generated from it.
  - `.github/workflows/`: CI/CD pipelines (`native-*.yml` for the apps, `server*.yml` and `mainnet-railway-deploy.yml` for the server).
  - `SECURITY.md`: vulnerability reporting.
- iOS (`apps/ios/`)
  - `project.yml`: XcodeGen spec. `Noah.xcodeproj` is generated and not committed (only its `Package.resolved` is).
  - `Config/{Mainnet,Signet,Regtest}.xcconfig`: per-variant bundle IDs, app groups, display name, Sentry DSN.
  - `Noah/App/`: app entry (`NoahApp.swift`, `AppDelegate.swift`, `AppModel.swift`, `AppVariant.swift`).
  - `Noah/Features/`: screens by area (`Shell/RootView.swift`, `Home`, `Send`, `Receive`, `History`, `Onchain`, `Recurring`, `Settings`, `Onboarding`, `BtcMap`, `QRHub`, `Push`).
  - `Noah/Services/`: `Wallet` (`WalletSession`), `Server` (`ServerClient`, auth, registration), `Push`, `Background`, `Backup`, `Storage`, `Launch`, `Logging`, `AppIntents`, plus `AppServices.swift`.
  - `Noah/API/Generated/`: generated server client (don't hand-edit).
  - `Noah/DesignSystem/`, `Noah/Localization/`, `Noah/Resources/` (`Localizable.xcstrings`, entitlements, privacy manifest).
  - `NoahWidget/`: widget extension. `NoahTests/`: unit tests. `*Tests/` directories: device, live-stack and upgrade suites with their own READMEs.
- Android (`apps/android/`)
  - `app/build.gradle.kts`: flavors `mainnet`, `signet`, `regtest`; version name/code; signing.
  - `app/src/main/java/com/noahwallet/`: `MainActivity.kt`, `NoahApplication.kt`, `ui/` (screens), `services/` (`wallet/WalletSession.kt`, `server/`, `push/`, `background/`, `backup/`, `storage/`, `launch/`, `logging/`, `AppServices.kt`), and feature packages (`send`, `history`, `onchain`, `recurring`, `btcmap`, `widgets`).
  - `app/src/main/java/com/noahwallet/api/generated/`: generated server client (don't hand-edit).
  - `gradle/libs.versions.toml` and `gradle/verification-metadata.xml`: pinned, checksum-verified dependencies.
  - `*Tests/` directories: device and live-stack suites with their own READMEs.
- Shared app assets (`apps/`)
  - `apps/fixtures/`: JSON cases both platforms' tests load, so shared behavior stays identical.
  - `apps/maestro/`: Maestro UI flows run against both apps.
  - `apps/i18n/` and `apps/tools/translations.mjs`: English source copy and translations.
- Server
  - `server/src/main.rs`: app bootstrap, dependencies, routers, middleware, cron.
  - `server/src/routes/public_api_v0.rs`: public + semi-public API handlers.
  - `server/src/routes/gated_api_v0.rs`: authenticated/gated handlers.
  - `server/src/routes/app_middleware.rs`: auth/user/email middleware.
  - `server/src/db/`: database repository layer.
  - `server/src/cache/`: Redis-backed stores (k1, invoice, email verification, maintenance).
  - `server/src/types.rs`: shared API payloads and enums.
  - `server/src/openapi.rs`: OpenAPI export.
  - `server/src/tests/`: integration-style endpoint/repository tests.
  - `server/migrations/`: SQL migrations.
- Other: `scripts/` (regtest stack, release tooling), `.railway/` (deployments), `docs/` (design and operations notes; native release runbook in `docs/native-rewrite/RELEASING.md`).

## Tech stack

### iOS

- Swift 6 with warnings as errors, SwiftUI, iOS 18 deployment target, Xcode 27+.
- XcodeGen generates the project; Swift Package Manager pins dependencies with `exactVersion` in `project.yml`.
- Wallet SDK: bark-bolt-ffi. Other packages: Sentry, MapLibre, MMKV, ZIPFoundation, swift-openapi-runtime.
- Lint/format: SwiftFormat and SwiftLint (`--strict`).

### Android

- Kotlin, Jetpack Compose, Navigation 3, Gradle with strict dependency verification, JDK 17, compile/target SDK 37, min SDK 24.
- Wallet SDK: bark-bolt-ffi (`io.github.niteshbalusu11:bark-bolt-ffi-android`). Server client generated with Fabrikt.
- Lint/format: ktlint and detekt (all rules, `Config/detekt/detekt.yml`).

### Server

- Rust (edition 2024) + Axum.
- DB: Postgres (sqlx).
- Cache/state: Redis/Dragonfly (deadpool-redis).
- Push: direct APNs and FCM ([docs/direct_push.md](docs/direct_push.md)), Expo for apps that have not updated, and UnifiedPush endpoint POST.
- Jobs: `tokio-cron-scheduler`.
- Storage/Email: AWS S3 + SES.
- Errors: `anyhow` + typed `ApiError` for HTTP responses.

## Runbook

### Setup

- Enter the Nix shell (recommended): `direnv allow` or `nix develop`.
- Install Bun dependencies for scripts and translations: `just install`.
- iOS also needs Xcode 27+ and XcodeGen (`brew install xcodegen`).

### Mobile app execution policy for autonomous agents

- Feel free to run simulator/emulator commands.
- If the Nix shell is available, run commands inside it.
- iOS: `just ios-native-generate` after changing `project.yml`, then build/test with `just ios-native-build <Variant>` / `just ios-native-test <Variant>`, or `xcodebuild` against `apps/ios/Noah.xcodeproj` with schemes `Noah`, `Noah-Signet`, `Noah-Regtest`.
- Android: `just android-native-build <Variant>`, `just android-native-install <Variant>`, `just android-native-test`, or `./gradlew` from `apps/android`.
- Use the Regtest variant against the local stack. Native builds share bundle/application IDs with the old React Native app, so installing one replaces it on that simulator or device.
- Rely on GitHub Actions for signed builds and releases.

### Server run commands

- Run server locally with live rebuild loop: `just server` (uses `bacon`).
- Build server: `just server-build`.
- Test server: `just server-test` or `just test`.

### Full local regtest stack

- Bring up infra: `just up`.
- Full bootstrap: `just setup-everything`.
- Tear down: `just down`.
- Helpful wrappers: `just bcli`, `just bark`, `just aspd`, `just lncli`, `just cln`.

### Quality checks

- Native lint: `just native-lint` (the pre-commit hook runs the same checks); fix formatting with `just native-format`.
- Native tests: `just ios-native-test Regtest` and `just android-native-test`.
- Translations: `bun apps/tools/translations.mjs --check`.
- Server checks: `just server-check`, `cargo fmt --check` and `just server-test`.

### Releases

- `just bump-native <x.y.z>` updates both apps' versions and build numbers; tagging `vX.Y.Z` builds Android artifacts, archives both iOS apps and deploys the mainnet server. Follow `docs/native-rewrite/RELEASING.md`.

## App architecture details

### Launch and services

- iOS: `NoahApp` / `AppDelegate` start the app; `AppModel` and `Features/Shell/RootView.swift` choose onboarding vs the main app; `Services/AppServices.swift` runs startup side effects (sync, push, backup, server registration) once the wallet is open.
- Android: `NoahApplication` and `MainActivity` start the app; `ui/AppModel.kt` and `ui/NoahApp.kt` choose onboarding vs the main app; `services/AppServices.kt` runs the startup side effects.

### Wallet

- Open and drive the wallet through `WalletSession` (iOS `Services/Wallet/`, Android `services/wallet/`); don't open a separate bark-bolt-ffi wallet elsewhere.
- Bark's daemon never runs (`runDaemon = false`). While the app is in front, the foreground pass in `WalletSession` syncs every 60 s and on resume, runs delegated maintenance and progresses exits. Background refresh and push-triggered jobs run their own bounded passes (`Services/Background`, `services/background`).
- Send, exit, board and recurring flows keep journals so an interrupted operation is reconciled, never resubmitted blindly. Keep that coordination intact when changing them.

### Build variants and identity

- iOS variants come from `Config/<Variant>.xcconfig`; Android from the product flavors in `app/build.gradle.kts`.
- Bundle/application IDs, app groups, widget IDs, keychain items, MMKV files and the Android signing key match the React Native app so the native apps install as in-place updates. Never change them.

### Server API clients

- Clients are generated from `api/openapi.json`: `just generate-api-ios` (into `apps/ios/Noah/API/Generated`) and `just generate-api-android` (into `com.noahwallet.api.generated`).

## Server architecture details

### Router/middleware layout

- Main app in `server/src/main.rs` mounts:
  - `/health` and `/` basic routes,
  - `/v0/*` API routes,
  - `/.well-known/lnurlp/{username}` LNURL pay endpoint.
- Middleware order matters:
  - trace middleware (`trace_layer`) for structured events,
  - Sentry layers,
  - auth middleware on protected routers,
  - user-exists middleware,
  - email-verified middleware (currently warn-only, not hard-blocking).
- Rate limiters (`server/src/rate_limit.rs`):
  - public (stricter),
  - suggestions-specific,
  - authenticated.

## Shared types and contract generation

- Source of truth for shared API types: `server/src/types.rs` (with `utoipa` schemas).
- After an intentional API change, run `just generate-api` to rewrite `api/openapi.json`, then regenerate both app clients and check both apps compile. The `Native API drift` workflow fails when any of these are stale.
- Server tests also rewrite the legacy `client/src/types/serverTypes.ts` through `ts-rs`; commit it with the change and never hand-edit it.

## Required coding standards

### Native apps

- Keep the two apps in step: a behavior change lands on both platforms, with shared cases in `apps/fixtures/` when the rule is platform independent.
- iOS: Swift 6 strict concurrency with warnings as errors; SwiftFormat/SwiftLint clean.
- Android: ktlint and detekt clean; keep `verification-metadata.xml` updated when dependencies change.
- Log through the app loggers (`Services/Logging/AppLog.swift`, `services/logging/AppLog.kt`). `apps/tools/check-log-output.mjs` fails CI on new print/log sinks.
- Write copy in English in native code, then run the translation tooling; don't hand-edit generated `.xcstrings` / `strings.xml` entries.
- Don't hand-edit generated API clients or the generated Xcode project.

### Server

- Use `anyhow` for internal error handling.
- Use `ApiError` for HTTP errors.
- Use `tracing` for logs.
- Keep SQL in `server/src/db/*` repositories (including test-only queries via `#[cfg(test)]` where needed).
- Add/maintain endpoint tests for behavior changes.

## Security and safety constraints

- Never log mnemonics, private keys, raw signatures, or presigned URLs.
- Preserve k1 one-time-use + TTL behavior.
- Respect route gating and middleware layering.
- Validate user-provided lightning addresses through existing validation paths.
- Keep background job coordination logic intact when touching push/wallet load flows.
- Never change the apps' bundle/application IDs, app groups, keychain/storage identifiers or signing identity.
- Report vulnerabilities as described in `SECURITY.md`.

## High-value files to read first for most tasks

- `apps/ios/project.yml`
- `apps/ios/Noah/App/AppModel.swift`
- `apps/ios/Noah/Features/Shell/RootView.swift`
- `apps/ios/Noah/Services/AppServices.swift`
- `apps/ios/Noah/Services/Wallet/WalletSession.swift`
- `apps/ios/Noah/Services/Server/ServerClient.swift`
- `apps/android/app/build.gradle.kts`
- `apps/android/app/src/main/java/com/noahwallet/ui/AppModel.kt`
- `apps/android/app/src/main/java/com/noahwallet/services/AppServices.kt`
- `apps/android/app/src/main/java/com/noahwallet/services/wallet/WalletSession.kt`
- `apps/android/app/src/main/java/com/noahwallet/services/server/ServerClient.kt`
- `server/src/main.rs`
- `server/src/routes/public_api_v0.rs`
- `server/src/routes/gated_api_v0.rs`
- `server/src/routes/app_middleware.rs`
- `server/src/types.rs`
- `server/src/tests/common.rs`

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:

- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:

- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:

- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:

- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:

```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```
