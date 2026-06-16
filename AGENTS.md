# AGENTS.md

## Purpose

This document is the source of truth for autonomous agents working in the Noah monorepo.
It is intentionally operational: where code lives, how the runtime behaves, and how to make safe changes that pass CI.

## Project summary

- Noah is a mobile Bitcoin wallet for Ark (L2).
- Monorepo contains:
  - `client/`: React Native + Expo mobile app.
  - `server/`: Rust Axum backend.
  - `scripts/`: local regtest/dev stack tooling.
  - `fly/`: Fly.io deployment configs.
  - `docs/`: deep-dive docs for push/widgets/notification coordination.

## Monorepo map

- Root
  - `justfile`: primary dev command surface.
  - `flake.nix`: Nix development environments.
  - `Cargo.toml`: workspace root (member: `server`).
  - `package.json`: Bun workspace root (workspace: `client`).
  - `.github/workflows/`: CI/CD pipelines.
- Client
  - `client/App.tsx`: app root (Sentry wrapping, providers).
  - `client/src/Navigators.tsx`: navigation stacks/tabs + onboarding gate.
  - `client/src/AppServices.tsx`: startup side effects (sync, push, backup, server registration).
  - `client/src/lib/`: APIs, wallet wrappers, backup/sync/tasks/logging.
  - `client/src/hooks/`: feature hooks used by UI.
  - `client/src/store/`: Zustand persisted stores.
  - `client/src/types/serverTypes.ts`: generated from server via `ts-rs`.
  - `client/nitromodules/noah-tools/`: custom Nitro module (native bridge).
- Server
  - `server/src/main.rs`: app bootstrap, dependencies, routers, middleware, cron.
  - `server/src/routes/public_api_v0.rs`: public + semi-public API handlers.
  - `server/src/routes/gated_api_v0.rs`: authenticated/gated handlers.
  - `server/src/routes/app_middleware.rs`: auth/user/email middleware.
  - `server/src/db/`: database repository layer.
  - `server/src/cache/`: Redis-backed stores (k1, invoice, email verification, maintenance).
  - `server/src/types.rs`: shared API payloads and enums exported to TS.
  - `server/src/tests/`: integration-style endpoint/repository tests.
  - `server/migrations/`: SQL migrations.

## Tech stack

### Client

- React Native + Expo (bare-style native projects in `ios/` and `android/`).
- Runtime/package manager: Bun.
- TS strict mode.
- State: Zustand with MMKV persistence.
- Data fetching: TanStack Query.
- Styling: Uniwind/Nativewind + `global.css` theme variables.
- Ark APIs: `react-native-nitro-ark`.
- Custom native bridge: `noah-tools` Nitro module.
- React compiler

### Server

- Rust (edition 2024) + Axum.
- DB: Postgres (sqlx).
- Cache/state: Redis/Dragonfly (deadpool-redis).
- Push: Expo push and UnifiedPush endpoint POST.
- Jobs: `tokio-cron-scheduler`.
- Storage/Email: AWS S3 + SES.
- Errors: `anyhow` + typed `ApiError` for HTTP responses.

## Runbook

### Setup

- Install deps: `just install` (or `bun install`).
- Enter Nix shell (recommended): `direnv allow` or `nix develop`.

### Mobile app execution policy for autonomous agents

- Do not start Android/iOS apps locally as part of autonomous workflow.
- Do not run simulator/emulator commands like `just android`, `just ios`, or variant-specific equivalents.
- Rely on GitHub Actions client pipelines for platform builds (Android and iOS).

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

- Client checks: `just check` (runs lint + typecheck under client).
- Server checks: `just server-check` and `cargo fmt --check`.
- Combined: `just check-all`.

## Client architecture details

### App boot sequence

- `client/index.ts` imports `~/lib/pushNotifications` for task registration before root component registration.
- `client/App.tsx` sets providers (QueryClient, SafeArea, GestureHandler, AlertProvider), configures Sentry in non-debug/non-regtest.
- `client/src/Navigators.tsx`:
  - Determines onboarding vs main app based on wallet state.
  - Handles push-permission gate screen.
  - Initializes services via `<AppServices />` after wallet is loaded.

### Wallet and payments

- Do not call `react-native-nitro-ark` directly from screens/components.
- Use wrappers/hooks in:
  - `client/src/lib/walletApi.ts`
  - `client/src/lib/paymentsApi.ts`
  - `client/src/hooks/useWallet.ts`
  - `client/src/hooks/usePayments.ts`
- Background sync entrypoint: `client/src/lib/sync.ts`.

### Build variants

- App variant comes from native (`getAppVariant()` via Nitro module).
- Android flavors in `client/android/app/build.gradle`: `mainnet`, `signet`, `regtest`.
- iOS schemes/xcconfigs in `client/ios/Config/*.xcconfig` and `Noah-*.xcscheme`.

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

- Source of truth for shared API types: `server/src/types.rs`.
- Export target: `client/src/types/serverTypes.ts` (generated by `ts-rs`).
- If server types changed, run server tests/build flow that regenerates TS output, then verify client compiles.
- Do not hand-edit `client/src/types/serverTypes.ts`.

## Required coding standards

### Client

- Keep TypeScript strict.
- Avoid `any`.
- Prefer `neverthrow` `Result` flows for recoverable errors.
- Use project logger from `~/lib/log`.
- Do not add `console.*` (lint blocks this in app code).
- For wallet/native actions, use existing hooks/wrapper modules.

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

## High-value files to read first for most tasks

- `client/src/Navigators.tsx`
- `client/src/AppServices.tsx`
- `client/src/lib/api.ts`
- `client/src/lib/walletApi.ts`
- `client/src/lib/pushNotifications.ts`
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
