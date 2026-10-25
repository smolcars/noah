# Native rewrite research (2026-10-19)

Source-level research that informed [../PLAN.md](../PLAN.md). Snapshot of the repo at `d367766a`; source code wins where these notes and the code disagree. Paths starting with `/` are relative to the repo root. References to `/tmp/noah-native-research/...` are scratch clones/artifacts that were not committed.

| Report | Topic |
|---|---|
| [inventory.md](inventory.md) | Every screen, lib/hook/store, Nitro method, endpoint, persisted key, invariant and test in the RN app |
| [keychain.md](keychain.md) | Exact react-native-keychain storage on iOS/Android and how to read the existing seed natively |
| [ffi-mapping.md](ffi-mapping.md) | Wallet datadir/DB continuity, backup format, nitro-ark → bark-bolt-ffi API map, regtest changes |
| [expo-push.md](expo-push.md) | Keeping Expo push tokens across the native update; native push handlers; UnifiedPush |
| [typegen.md](typegen.md) | Rust → Swift/Kotlin API type generation options and trials |
| [ui-stack.md](ui-stack.md) | SwiftUI/Liquid Glass, keyboard handling, Compose stack, RN dependency replacements, project layout |
| [swift-android.md](swift-android.md) | Swift SDK for Android feasibility (not adopted: logic is written twice) |
| [e2e-vs-maestro.md](e2e-vs-maestro.md) | TesterArmy `e2e` (agentic testing) vs Maestro for Android CI, with a local trial (not adopted now; smoke-job trial later) |
