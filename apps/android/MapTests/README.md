# BTC Map checks (Android)

MapLibre Native Android is pinned in the version catalog, and Gradle verifies its
SHA-256 checksums. Gradle packages the committed RN assets by source-directory
reference: `client/assets/btcmap/places.btcmap` and
`client/assets/geonames/cities.geonames` (GeoNames, CC BY 4.0).

## Coverage

The test resource directories reference `apps/fixtures`, `client/assets/btcmap` and
`client/assets/geonames` directly; no fixture is forked.

- `BtcMapFixtureTest` runs the shared cases as individual JUnit tests: RN-generated
  `btc-map.json` / `city-search.json`, `btc-map-review.json` (web/phone validation
  and dateline bounds) and `btc-map-limits.json` (64-container JSON depth limit).
  They cover UTC cursors, categories, distances, snapshot validation, sorted patch
  insertion/replacement/deletion, viewport validation, Unicode normalization, city
  ranking and both bundled datasets.
- `BtcMapRepositoryTest`, `BtcMapModelTest`, `BtcMapLimitsTest` and `BtcMapLinksTest`
  cover resource limits (32 MiB transfers and cache, 100,000 records, 16 KiB strings),
  cooldown/cache preservation, the delayed single retry, cancellation, concurrent
  requests and freshness, optional comments, safe links, city-selection filtering,
  viewport persistence, deleted selection and every visible merchant.

## Running

From the repository root:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'
```

Regenerate the RN-derived fixtures with
`bun --tsconfig-override client/tsconfig.json apps/fixtures/tools/generate-btc-map.ts`.

## Manual checks

A debug-only activity opens the public map without wallet setup or wallet services:

```sh
adb -s <owned-emulator> shell am start -n \
  com.noahwallet.regtest/com.noahwallet.btcmap.BtcMapPreviewActivity
```

It is absent from release source sets. The normal entry is Settings → **Spend bitcoin
nearby**. Use only a disposable emulator and a synthetic location, e.g.
`adb -s <owned-emulator> emu geo fix -84.39 33.75`. Never include real location or
wallet data in verification artifacts.

Opening the map never requests location; only My location requests foreground coarse
and fine together, and coarse alone is enough (network provider, no GPS). Requests
stop after 20 seconds. Street tiles are not bundled: offline, a failed or stalled
style load falls back to a plain local style, and merchant data and city search keep
working. External link, dialer, email and share handoffs are unit-tested, not
exercised by these checks.
