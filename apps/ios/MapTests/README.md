# BTC Map verification

M8 uses MapLibre Native iOS 6.31.0 (exact SPM pin) and the committed RN assets
by resource reference, without copying or regenerating them:

- `client/assets/btcmap/places.btcmap`: 29,705 merchants, 4,871,499 bytes.
- `client/assets/geonames/cities.geonames`: 2,158,467 bytes, GeoNames CC BY 4.0.

## Repeatable checks

Generate the shared parity fixtures from RN TypeScript:

```sh
bun --tsconfig-override client/tsconfig.json apps/fixtures/tools/generate-btc-map.ts
```

The 61 cases port RN's `btcMap.test.js` and `citySearch.test.js`, adding snapshot
validation, UTC sync cursors, patch deletion/replacement/insertion, Unicode normalization and
viewport-biased city ranking. Swift Testing also verifies atomic-cache failure
behavior, request deduplication, five-minute sync freshness, 15-minute detail
freshness, optional comment fetching, MMKV viewport continuity and city selection.

```sh
cd apps/ios
xcodegen generate
xcodebuild -scheme Noah-Regtest \
  -destination 'platform=iOS Simulator,id=<your-own-simulator>' \
  -derivedDataPath DerivedData -packageAuthorizationProvider netrc \
  -parallel-testing-enabled NO test \
  -skip-testing:NoahTests/UpgradeCompatibilityTests
```

Keep simulator signing enabled: existing Keychain tests require the app's
entitlements. The public package resolver can use `netrc` to avoid a host Keychain
credential prompt. Enter `nix develop` for the repository toolchain.

Debug simulator builds accept `-btc-map-preview`, which opens only the public map
without creating/opening a wallet or starting wallet services. Normal launches
reach it from Home's map button. This route is absent from device/release builds.
Use an owned simulator and `xcrun simctl location <id> set 33.75,-84.39` for a
synthetic location. Never use a real user's location in test evidence.

## Behavior and native differences

- The cache remains `Documents/btcmap-places-v1.btcmap`; viewport remains raw JSON
  under MMKV `btc-map-viewport-v1`. Invalid viewports are discarded. Writes are
  atomic; a malformed update never advances the cursor or replaces usable data.
- Endpoints, requested fields, deletion patches, one retry, initial foreground
  location, city/merchant ranking, categories and zoom levels follow RN. A local
  snapshot loads immediately; sync refreshes on opening/foreground when stale.
- OpenFreeMap liberty/dark styles, radius-46 clustering through zoom 14, orange
  merchant dots and cluster counts match RN. SF Symbols replace RN category/action
  icons. A failed initial basemap load falls back to a plain local style; merchant
  data and search remain available, but offline street tiles are not bundled.
- Native 390pt/large sheets adapt to a side pane at 700pt. The selected merchant
  stays above the compact sheet; selection survives layout changes. Native
  navigation and GlassCompat supply the system material and accessibility fallback.
- Merchant web/social/payment-app links accept HTTP(S); phone and Maps actions
  construct their own schemes. Arbitrary schemes from public merchant data are
  not opened. Phone calls still require the platform handler/user action.
- No wallet access and no application logging of location coordinates.

## Size

Assets add **7,029,966 bytes (6.70 MiB)** uncompressed. The embedded arm64 MapLibre
framework in the Release-Mainnet device build is **8,091,506 bytes (7.72 MiB)**.
Together these are **14.42 MiB**, plus the feature's compiled Swift code. This is
installed payload accounting, not an App Store compressed-download estimate.

## Verification record (2026-10-19)

- Regtest simulator: full Swift Testing suite passed (207 reported tests; upgrade
  tests excluded; opt-in live integration tests retain their own skips).
- Release-Mainnet: unsigned generic iOS device build passed, including Swift 6
  compilation and the exact MapLibre package.
- Dedicated iPhone 17 / iOS 27 simulator: live v4 sync (29,741 places), denied and
  granted When-In-Use location, synthetic Atlanta location, local city search,
  nearby selection and live merchant details; light portrait / dark landscape;
  selected-city filtering, sheet-to-pane rotation and selected-marker visibility.
  Two successive live syncs saved UTC cursors; the next-cursor request returned
  HTTP 200 (empty patch set), confirming continuation beyond the bundled cursor.
- Fixture regeneration is deterministic. Required pre-commit checks run normally.
- Physical-device location/phone behavior, iOS 18 runtime and full VoiceOver
  navigation remain unverified. No screenshots are committed.

## Review hardening (2026-10-19)

Public BTC Map transfers stream with a **32 MiB** hard limit on received bytes,
including responses without Content-Length. Cache reads have the same limit.
Typed lists stop at **100,000 records** and decoded strings are limited to
**16 KiB UTF-8**. The full v4 list with the requested map fields and deletions
measured **7,487,631 bytes / 43,178 records** on 2026-10-19; the largest list string
was 192 bytes. The bundled snapshot remains 4,871,499 bytes / 29,705 merchants.
The limits leave room for growth while bounding transfer and decoding work.
Oversize responses are canceled, never immediately retried, and put that sync
or merchant detail request on a five-minute cooldown. Usable cached data and
its sync cursor are preserved. Tests cover streamed overflow, exact byte
boundaries, record/string limits, and cooldown/cache preservation.
