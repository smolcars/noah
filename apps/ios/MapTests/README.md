# BTC Map checks (iOS)

The map uses the committed RN assets by resource reference, without copying them:
`client/assets/btcmap/places.btcmap` and `client/assets/geonames/cities.geonames`
(GeoNames, CC BY 4.0).

## Coverage

- `BtcMapFixtureTests`, `BtcMapRepositoryTests` and `BtcMapModelTests` (all in
  `NoahTests/BtcMapTests.swift`): the shared `btc-map.json` and `city-search.json`
  cases ported from RN's `btcMap.test.js` and `citySearch.test.js` (snapshot
  validation, UTC sync cursors, patch deletion/replacement/insertion, Unicode
  normalization, viewport-biased city ranking), plus atomic-cache failures, request
  deduplication, sync/detail freshness, optional comments, viewport continuity and
  city selection.
- `BtcMapReviewTests`: web/phone link sanitization and viewport bounds, including
  dateline crossings (`btc-map-review.json`, shared with Android).
- `BtcMapLimitsTests`: the 32 MiB transfer/cache limit, 100,000-record and 16 KiB
  string limits, the 64-container JSON depth limit (`btc-map-limits.json`, shared
  with Android), and the five-minute oversize cooldown with its cache preservation.

## Running

Regenerate the RN-derived fixtures (`btc-map.json`, `city-search.json`):

```sh
bun --tsconfig-override client/tsconfig.json apps/fixtures/tools/generate-btc-map.ts
```

Run the suites on your own simulator (enter `nix develop` for the repository toolchain):

```sh
cd apps/ios
xcodegen generate
xcodebuild -scheme Noah-Regtest \
  -destination 'platform=iOS Simulator,id=<your-own-simulator>' \
  -derivedDataPath DerivedData -packageAuthorizationProvider netrc \
  -parallel-testing-enabled NO ARCHS=arm64 ONLY_ACTIVE_ARCH=YES test \
  -only-testing:NoahTests/BtcMapFixtureTests -only-testing:NoahTests/BtcMapRepositoryTests \
  -only-testing:NoahTests/BtcMapModelTests -only-testing:NoahTests/BtcMapReviewTests \
  -only-testing:NoahTests/BtcMapLimitsTests
```

`-only-testing` takes suite (struct) names, not file names. Keep simulator signing
enabled: existing Keychain tests require the app's entitlements. The public package
resolver can use `netrc` to avoid a host Keychain credential prompt.

## Manual checks

Debug simulator builds accept the `-btc-map-preview` launch argument, which opens only
the public map without creating or opening a wallet or starting wallet services. Normal
launches reach the map from Settings → **Spend bitcoin nearby**.

Use an owned simulator and a synthetic location, e.g.
`xcrun simctl location <id> set 33.75,-84.39`. Never use a real user's location in
test evidence. Opening the map never asks for location permission; only **My location**
requests it. Offline street tiles are not bundled, so a failed basemap load
falls back to a plain style while merchant data and search keep working.
