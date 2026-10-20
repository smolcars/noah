# Native Android BTC Map verification

M8 uses MapLibre Native Android **13.7.1**, pinned in the version catalog.
Gradle verifies SHA-256 checksums for MapLibre, GeoJSON, Turf, gestures, Timber,
Fragment and Gson. Checksums were independently compared with the publisher's
Maven artifacts. The newly covered groups also include checksums for existing
scaffold/plugin versions resolved during compilation.

## Repeatable checks

From the repository root:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'
```

The test resource directories directly reference `apps/fixtures`,
`client/assets/btcmap`, and `client/assets/geonames`. No fixture is forked.
`BtcMapFixtureTest` runs **108 shared cases** as individual JUnit tests: 61
RN-generated `btc-map` / `city-search` cases, 32 `btc-map-review` cases
(including all five dateline bounds cases and seven added phone vectors), and 15
`btc-map-limits` cases.
They cover UTC cursors, categories, distances, snapshot validation, sorted patch
insertion / replacement / deletion, viewport validation, Unicode normalization,
city ranking, web/phone validation and both bundled datasets. Another 28 tests
cover resource limits, cooldown/cache preservation, delayed retry, cancellation,
concurrent requests and freshness, optional comments, safe links, city-selection
filtering, viewport persistence, deleted selection and every visible merchant.

A debug-only activity opens the public map without wallet setup or wallet services:

```sh
adb -s <owned-emulator> shell am start -n \
  com.noahwallet.regtest/com.noahwallet.btcmap.BtcMapPreviewActivity
```

It is absent from release source sets. The normal entry is Home's **Bitcoin Map**
toolbar action. Use only a disposable emulator and synthetic location, e.g.
`adb -s <owned-emulator> emu geo fix -84.39 33.75`. Never include real location or
wallet data in verification artifacts.

## Contracts and platform adaptations

- Gradle packages the same committed RN assets as iOS, by source-directory
  reference: `places.btcmap` (29,705 merchants, 4,871,499 bytes) and
  `cities.geonames` (34,006 cities, 2,158,467 bytes; GeoNames CC BY 4.0).
  Each asset occurs once in the APK; no second repository copy is added.
- The RN-compatible cache is `filesDir/btcmap-places-v1.btcmap`. Same-directory
  temporary writes are flushed and renamed before the in-memory snapshot or UTC
  cursor advances. Invalid updates or disk failures retain the previous data.
  The v4 endpoints, requested fields, deletions, one retry, five-minute sync and
  fifteen-minute detail freshness match iOS. Ordinary failures wait one second before
  their single retry. Resource-limit failures skip that retry and enter a five-minute
  cooldown (sync-wide or per merchant for details). Downloads and cache/asset reads
  stop at 32 MiB, including missing or dishonest Content-Length headers; decoding
  caps arrays at 100,000 records and strings at 16 KiB of UTF-8. The merged cache's
  record count and encoded bytes are checked before the atomic write. Every failure
  preserves the previous cache and cursor. Network work is canceled with its
  caller; sync and detail locks deduplicate concurrent successful requests.
- Viewport JSON retains MMKV key `btc-map-viewport-v1` in the existing settings
  instance. Invalid values cannot reach the camera. City selection uses zoom 11,
  location 13, and merchant selection 15. Choosing a city does not filter merchants
  by the city's display label. City suggestions re-rank when the viewport center
  changes or the search field regains focus. Search parsing and ranking run off
  the main thread.
- OpenFreeMap liberty/dark styles, radius-46 clusters through zoom 14, merchant dots,
  cluster counts and cluster-tap expansion follow RN/iOS. The selected marker and
  optional user-location marker have separate layers. MapView receives lifecycle
  and memory callbacks. A failed or 15-second stalled style load uses a plain local style, preserving
  public merchant data/search. Street tiles are **not bundled**; cluster glyphs may
  also be unavailable without cached fonts. Attribution remains accessible.
- Material 3 replaces SwiftUI/RN controls. Details use a phone bottom sheet and a
  340 dp side pane at 700 dp width. The selected merchant survives rotation.
  **Places in view** lists every matching merchant inside the camera bounds,
  including dateline crossings, sorted by name then ID. It uses a height-constrained
  LazyColumn and collapses on selection; every row opens the same detail as a map
  marker. A previous user-location fix does not bias this list after panning.
  Small map-control text uses the theme foreground for contrast.
- Unlike the iOS/RN initial-location behavior, **no location request occurs on
  opening**. Only My location requests foreground coarse and fine together. Coarse
  permission is sufficient; network location is used without GPS when precise
  permission is absent. Fine permission additionally enables GPS. There is no GMS
  SDK dependency, background tracking, or application logging of coordinates/PII.
  Requests stop on success, screen exit/background, cancellation, or a 20-second
  timeout. Denial and unavailable providers leave city search/map browsing usable.
- Merchant website/social/app links accept only HTTP(S), with a host and no user
  info/whitespace/control characters. Phone numbers require at least one ASCII
  digit after normalization, matching iOS; digitless punctuation and Unicode
  digits are rejected. Phone and email fields construct validated `tel:` and
  `mailto:` links, rejecting scheme/header injection. Every external action is a
  user tap. HTTP(S), including generated HTTPS Google Maps directions, uses
  `ACTION_VIEW` with `CATEGORY_BROWSABLE`; phone uses `ACTION_DIAL` (never
  `ACTION_CALL`), and email uses `ACTION_SENDTO`. Missing handlers are caught.
  These intents carry only the public URI, with no extras or wallet data, so
  packages are deliberately not pinned: the user chooses their browser, dialer,
  email or maps handler. The separate Share place chooser shares only the public
  merchant name and BTC Map URL. Email is exposed on Android in addition to iOS's
  actions. Remote merchant images are not automatically downloaded.

## Verification record — 2026-10-19

- Required Nix/Gradle command: **412 tests, zero failures/errors/skips**, including
  **72 map tests** (61 shared cases + 11 Android service/model/link tests). Both test
  and APK assembly tasks passed on the final source in **44 seconds**. Dependency
  verification remained enforced.
  Some intermediate runs hit the existing ten-second `WalletRestorerTest` timeout
  during severe shared-host contention; no wallet code or test deadline was changed.
- Live device: a newly created Android 35 ARM64 Google APIs AVD,
  `NoahBtcMapM8Astra`, serial **emulator-5584**, opened in the T3 Device panel.
  All interaction used that serial. No physical device, protected emulator,
  Docker service, or wallet setup was used. The Device panel was closed, the
  emulator shut down, and `avdmanager delete avd --name NoahBtcMapM8Astra` succeeded;
  the serial and AVD configuration were confirmed absent afterward.
- Live network: bundled 29,705 places became **29,741** after incremental sync.
  Public cache inspection confirmed successive UTC cursors
  `2026-10-20T00:12:46.168Z` and `2026-10-20T00:21:52.037Z` (UTC date differs from
  the local verification date). Light/dark street styles, cluster counts and
  tap-to-expand worked. Atlanta city selection retained global merchant results;
  the food category showed 7,206 places. The Food Shoppe and Eclectic Barbershop
  loaded live addresses, opening hours, links and payment information.
- Portrait phone sheet and landscape wide pane were exercised, including retaining
  the selected merchant across rotation. City suggestions remain visible when
  merchant results arrive first. Nearby results collapse on selection and their
  height is capped in landscape. Viewport survived force-stop and APK reinstall.
  A finish review caught a phone-sheet marker occlusion; measuring the sheet's
  actual overlap and applying camera padding/target together resolved it. The
  reviewer cleared the final capture. Evidence: [phone](evidence/phone.png) and
  [wide](evidence/wide.png).
- Location: initial coarse/fine grants were both false; opening did not prompt.
  My location displayed Android's precise/approximate permission chooser. Denial
  preserved browsing. Approximate-only permission was accepted and requested only
  the network provider; without a network fix it timed out gracefully, removing
  its listener after approximately 20 seconds. With precise permission, the
  synthetic Atlanta fix recentered at zoom 13 and displayed nearby distances;
  both provider listeners were removed after success. Real coordinates were not
  used or logged.
- Cold offline: disabled networking and removed the downloaded merchant cache and
  MapLibre tile/style database in the disposable app. Bundled merchant suggestions
  and GeoNames Atlanta search still worked, with a live-update-unavailable message.
  A style-load retry stall found during this check led to the bounded local-style
  fallback; the final APK rendered merchant dots and the nearby list offline.
  Evidence: [cold offline](evidence/offline.png).
  Street tiles and uncached merchant details require connectivity.

### APK size

The baseline and final builds use the same universal regtest debug variant,
including **arm64-v8a and x86_64**. This is not a Play Store download estimate.

| Artifact | Bytes |
| --- | ---: |
| Baseline APK | 133,878,113 |
| BTC Map APK | 163,791,898 |
| Added | 29,913,785 (28.53 MiB; 22.34%) |

Verified APK SHA-256:
`070d7992b43cb99064812be6ef4e16a768b88083455c462f60b3de73c95039bb`.

MapLibre's uncompressed native libraries account for 26,633,192 added bytes across
the two ABIs. The shared merchant/city assets occupy 1,354,201 and 972,796 compressed
bytes respectively. Each appears once. No Google Play Services location SDK,
offline street-tile archive, or duplicate snapshot was added.

### Limits

- The Home action was compiled and reviewed; live checks entered through the
  debug-only map activity, without creating or loading a wallet.
- A successful approximate/network location fix, physical-device GPS, API 24–29,
  tablet/foldable hardware, full TalkBack and large-font traversal remain unverified.
  Landscape verified the wide layout on the owned emulator.
- Link validation is unit-tested and detail buttons were inspected. Actual dial,
  email, browser and share handoffs were not exercised; no messages or calls were
  sent. Cold-offline street tiles and uncached glyphs are deliberately not bundled.
- Release/R8 builds, per-ABI delivery size and Play Store download size were not
  measured. The debug APK size above includes both supported native ABIs.

## Review follow-up — 2026-10-19

- The required Nix/Gradle unit-test and regtest debug assembly command passed:
  **498 tests, zero failures/errors/skips**, including **119 map tests**
  (93 shared fixture cases + 26 repository/model/link/limit tests). Dependency
  verification remained enforced; no dependency or verification metadata changed.
- Eleven limit regressions cover exact streamed length, dishonest/missing length,
  early termination one byte beyond the cap, UTF-8 byte boundaries, bounded record
  decoding for snapshots/patches/comments, oversized fields/metadata, oversized
  disk-cache fallback, sync/detail cooldown at 299,999/300,000 ms, and merged-cache
  byte/record overflow. Rejected syncs retain the previous disk cache, snapshot
  and cursor. An additional repository test verifies the ordinary one-second retry.
- Shared review vectors run directly from `apps/fixtures/btc-map-review.json`;
  all five bounds cases run as individual JUnit tests. Seven new phone vectors
  include `...`, `( )`, `+...`, empty/plus-only and Unicode-digit values, plus a
  valid single ASCII digit. iOS already rejects digitless numbers and needs no
  source change; its existing fixture loader consumes the same added vectors.
- Model regressions mirror iOS's London CA → London GB viewport ranking, select
  every merchant in world bounds through the normal detail-selection path, and
  verify London bounds after an Atlanta location fix. Camera bounds normalize
  MapLibre's unwrapped dateline longitudes before matching. The Compose search
  effect includes the viewport center and field focus, so re-entry refreshes rank.
- This follow-up used JVM tests and compilation only. No emulator/device,
  simulator or Docker was touched. Live TalkBack traversal, focus transitions and
  external browser/dialer/mail/maps handoffs were not repeated; the earlier live
  evidence above predates these review fixes. iOS source was unchanged and the
  NoahTests target was not rerun. The APK size/hash above describes the earlier
  acceptance build, not this follow-up build.

## Parsing hardening — 2026-10-19

- Both native platforms reject JSON beyond **64 nested containers** before any
  tree/typed decoder runs, including unknown fields. Root containers count as one.
  `apps/fixtures/btc-map-limits.json` is the shared source for 15 regression cases:
  snapshots, patches, details and comments at depths 64, 65 and 10,003, object
  nesting, and escaped structural characters inside strings. Deep JSON stays in
  raw strings so loading the fixture itself never builds the hostile tree.
- Injected-clock tests cover backward jumps during sync and per-merchant oversize
  cooldowns, expiry of successful caches after rollback, and forward recovery after
  a successful retry. Negative elapsed time expires the entry; successful requests
  clear the previous oversize timestamp so its cooldown cannot reappear.
- Merged-cache size is summed from encoded metadata, each merchant and separators
  before the full JSON string is allocated. UTF-8, escaping and empty/single/multiple
  record counts match full encoding. The existing 32 MiB overflow regression keeps
  its request-count, original-cache and unchanged-snapshot assertions.
- The required Nix/Gradle command passed: **515 tests, 0 failures/errors/skips**,
  including **136 map tests** (108 shared cases + 28 other map tests), and regtest
  debug APK assembly passed. The merged-byte regression also passed with a temporary
  Gradle init script setting the worker heap to **512 MiB** (1 test, 0 failures);
  the committed **2g** worker setting was left unchanged.
- Matching iOS code and shared regressions passed in the full NoahTests run
  (**284 reported, 272 passed, 12 skipped, 0 failures**). Android used no device,
  emulator or Docker service. iOS used only its newly created disposable simulator.
