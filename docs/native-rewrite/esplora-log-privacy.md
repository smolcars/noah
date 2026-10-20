# Custom Esplora log privacy

Round-4 base: `feat/native-rewrite` at `a5866b02`; review: `54b44dc5`.
Round-5 work starts at the supplied `be132094` HEAD, after the lifecycle merge.
All test credentials are fake.

A custom endpoint's entire identity is private, including scheme, hostname,
port, and path. `EndpointLogPrivacy.sanitize` is the shared native boundary.
AppLog's file and system sinks reach it through `LogFile.redactForTelemetry`;
Sentry message/exception/breadcrumb scrubbers and final payload guards use that
same path. Export sends retained records through the boundary too. Endpoint
settings error text and iOS `SafeErrorDescription.of` use `LogFile.redact`.
`SafeErrorDescription.forLogging` and Bark callbacks only enqueue raw text in a bounded
queue; the writer applies the boundary before either sink. Ark Info continues
to show/copy the full endpoint on screen.

Registered origins and any URL below them become `[custom-esplora]`. Matching
uses canonical origins, not a guess about which hostname/path segments resemble
credentials. Percent, JSON and HTML entity decoding is bounded to four layers;
uncertain decoding omits the text. Unsupported named HTML entities fail closed
rather than passing an undecoded hostname through; numeric entities also support
the optional semicolon accepted by HTML. Encoded matches omit the whole message because
decoding changes source offsets. URLs belonging to other origins are excluded
from relative-path matching. Authorities are extracted before URL parsing, so
trailing log delimiters never enter host canonicalization. Registrations and every raw/decoded token use the
same canonical origin: lowercase scheme/host, IDNA ASCII, no DNS root dot or
explicit default port, and compressed lowercase IPv6. There is no literal-prefix
fallback or alternate-spelling registry.
Host/port continuations cannot match another origin by prefix; adjacent log text
cannot hide the beginning of the URL. A relative reference matching a registered base
path has no independent origin, so its whole record is conservatively omitted.
There is no global list of credential-shaped or route-name segments. Default
servers are never registered by default configuration and remain visible.

Provider response text has no admission path: recognized `HttpResponse` errors
retain only the variant and three-digit status, for example
`HttpResponse { status: 404 }`. No body/message or following provider error chain
is retained, including HTML entities, escaped quotes, malformed escapes, and
truncated bodies. An unrecognized variant shape retains only `HttpResponse`.
Historical export groups indented continuation lines with their timestamped
record before scrubbing, so a later `Caused by` cannot lose its original context.

## Durable registrations

`EsploraSettings.Record.logEndpoints` stores the union of previous history,
previous active/pending endpoints, and new active/pending endpoints in the
existing native settings value. The history and selection commit atomically in
one durable write. Preparing a switch protects both candidates before Rust is
opened; the production opener also requires durable protection before SDK use.
Startup restores history before installing Bark/Sentry. A history decoding
failure closes the logging boundary instead of admitting unknown endpoint text.
An unreadable native store (including a damaged checksum) falls back with
`native-esplora-history-unavailable=1` in the existing settings values. Every
fallback write preserves this marker, and reset/deletion retains it alongside
any surviving endpoint registrations. `restoreLogPrivacy` throws when it is
present, reaching the existing startup `failClosed` catch even if the fallback
has become otherwise healthy. Unknown history is never reclassified as a known
empty set by a successful write or a new endpoint selection.

The marker never clears automatically, even after rotation. Logs rotate by size
without an age deadline, and the settings layer cannot prove that every older
file/export is gone. It remains for the installation lifetime; uninstall removes
both the marker and local app logs. While marked, the existing fail-closed
boundary replaces records with `[custom-esplora]`.

Replacement, rollback, pending-change recovery, resetting to default, and wallet
settings deletion preserve history. Deletion clears the active/pending selection
and input, retaining only history and a neutral default selection. The reset
transaction and its cleanup completion both preserve that metadata. It stays in
the native settings store, outside wallet backup exports.

History is deliberately retained beyond the minimum rotation lifetime. Logs
rotate by size, with no age deadline, so an elapsed-time expiry could expose an
old file. There is no pruning based on wall-clock time or current selection;
registrations remain for the installation's lifetime, including settings reset.
Thus every file that could contain a retired endpoint remains protected until
and after it rotates out. This trades a growing, small local settings set for
avoiding unsafe lifetime inference. Uninstall removes the store and app logs.
Already-retired endpoints from before this change cannot be reconstructed if
neither their registration nor selection survives in settings.

## System-log guarantee and CFNetwork limitation

The guarantee covers Noah's app-owned OSLog/Logcat messages, including SDK
callbacks routed through AppLog, files/export, Sentry, and sanitized UI error
text. It does not intercept Apple-owned `com.apple.CFNetwork` diagnostics.
The round-5 review observed a timed-out local request's full URL in
`NSErrorFailingURLStringKey` with XCTest-enabled system logging. An optimized
standalone build emitted `<private>` and a hash instead, so the probe did not
demonstrate a release leak. That observation is not a guarantee for every OS,
build, diagnostic profile, or logging configuration. Debug/test launches with
private framework diagnostics enabled can expose request URLs outside Noah's
boundary; do not share those raw system logs as sanitized app exports. No
release logging configuration was changed to suppress this test-only finding.

## Normalization decisions

The shared `apps/fixtures/esplora.json` now contains 36 normalization cases,
eight genesis cases, and four height cases (48 total).

- Match RN: preserve empty `?` and `#`, including the authority's separating `/`;
  remove embedded ASCII tab, LF and CR; compress `[0:0::1]` to `[::1]`.
- Intentional divergence: both natives reject all userinfo, including empty `@`
  and `:@`. An endpoint must never contain a user/password authority.
- Intentional divergence: both reject three/four authority slashes instead of
  silently repairing an ambiguous authority. Normal two-slash authority URLs
  and the existing path-backslash normalization stay supported.
- Intentional divergence: both reject malformed percent escapes (`%zz`, `%b`).
  Foundation repairs these while OkHttp/RN preserve them; rejection avoids
  sending a different credential path than the user entered.

No EsploraOpenWait behavior, recurring source files, RN source, or server source
is changed. Validation uses local fakes; it never calls a public Esplora server.

## Round-4 regression evidence

The review probes are permanent `EndpointPrivacyTests` / `EndpointPrivacyTest`
and the retired-endpoint test in `EsploraTests` / `EsploraTest`.

On the base, all three new privacy tests failed on both platforms: visible
hostname, provider body admitted, and unrelated public URL over-redacted.
The retired-endpoint persistence test also failed on each platform, and the
expanded normalization fixture failed on each. The additional unsupported named
HTML entity regression also fails before the decoder change on both platforms.
The origin-only punctuation regression failed before the authority-boundary fix
on Android; the permanent matrix covers both platforms, default ports,
punctuation, and adjacent text while preserving longer hosts and different ports. After the fix, the persistence
test reconstructs native storage, resets settings, restores history that has
never been registered in the current process, and exports a retained path-only
log without its fake credential.

The 147-case log-redaction fixture is unchanged. Scaling checks now run both
`LogFile.redact` and the shared endpoint/provider boundary at 1 KiB and 16 KiB,
with eight input families including provider bodies and HTML entities. They keep
the existing 64x-plus-20-ms scaling tolerance and two-second ceiling.

Focused final verification: Android 67 tests in five suites; iOS 59 tests in five
suites. Both pass, including the unchanged 147-case fixture, new entity probe,
48 shared Esplora cases, and scaling checks. The repository check also passes:
lint, React Compiler (202 files), 148 JavaScript unit tests, TypeScript, and
`cargo fmt --check`. Native translation generation/check is clean; only coverage
inventory changed because removal of the route list removed a false-positive
UI use of `blocks`. No UI copy or translation catalog changed.

Final signed arm64 iOS verification: **763 tests / 152 suites**,
**738 passed, 25 opt-in skipped, zero failed**. The full suite ran after the last
source change, following a 59-test focused pass. `xcodegen generate`,
`ARCHS=arm64`, `ONLY_ACTIVE_ARCH=YES`, and normal code signing were used.

Both owned simulators had their exact identities and shutdown states confirmed
before deletion: `Noah Endpoint Privacy t3-34d7`
(`5F40AFBA-22B6-4F54-B7B2-14F7EC62EA2C`) and
`Noah Endpoint Privacy Final t3-34d7`
(`1B980EAB-769C-402B-88CD-009CA0DC2CBF`). All owned DerivedData and twelve result
bundles were removed. No other simulator, emulator, physical device, or Docker
instance was used.

One concurrent Android full attempt hit the unchanged wall-clock scaling limits:
`http://` measured 0.86 ms / 391 ms and punctuation 0.74 ms / 134 ms. The focused
67-test run passed with the same code and thresholds. Final full runs were then
sequenced, removing the owned iOS simulator before Android verification, to
reduce shared-host contention. These wall-clock checks remain sensitive to
severe host contention.

Final Android verification: **3,552 JVM tests / 130 suites**,
zero failures, errors, or skips; regtest APK assembled. After the last source
change, the required command passed with no owned iOS build/simulator running:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'
```

The final iOS full run also used `nix develop`, code signing enabled, a dedicated
simulator, and DerivedData under `/private/tmp`. No active owned build remains.

## Rule locations

1. Whole endpoint identity/canonical authority: iOS `EndpointLogPrivacy.swift:27`;
   Android `EndpointLogPrivacy.kt:16`. HTML decoding: iOS `AppLog.swift:241`;
   Android `AppLog.kt:189`.
2. Shared provider boundary: iOS `EndpointLogPrivacy.swift:61` and
   `AppLog.swift:232`; Android `EndpointLogPrivacy.kt:51` and `AppLog.kt:180`.
   Historical records: iOS `EndpointLogPrivacy.swift:46`; Android `:35`.
   Error display: iOS `EsploraSettingsScreen.swift:72`; Android `:45`.
3. Durable history: iOS `EsploraSettings.swift:37`; Android `EsploraSettings.kt:18`.
   Unknown-history marker: iOS `SettingsStorage.swift:104` /
   `EsploraSettings.swift:43`; Android `SettingsStorage.kt:58` /
   `EsploraSettings.kt:23`. Startup: iOS `AppDelegate.swift:25`; Android
   `NoahApplication.kt:10`. Reset retention: both `retainingLogPrivacy` helpers.
4. Other-origin exclusion: iOS `EndpointLogPrivacy.swift:84`; Android `:66`.
5. Normalization: iOS `EsploraEndpoint.swift:10`; Android `EsploraEndpoint.kt:13`.
   Shared cases: `apps/fixtures/esplora.json`; authority cases:
   `apps/fixtures/esplora-log-authorities.json`.

## Round-4 implementation commits

- `bbb34d92` test(native): reproduce custom Esplora privacy regressions
- `91f3c43d` fix(android): remove entire registered Esplora identities
- `1146ffd5` fix(ios): remove entire registered Esplora identities
- `3d8cbf5d` fix(ios): suppress provider bodies at the shared privacy boundary
- `a5d7cf96` fix(android): suppress provider bodies at the shared privacy boundary
- `13588210` fix(ios): scope custom paths away from unrelated URL origins
- `e404cc56` fix(android): scope custom paths away from unrelated URL origins
- `47719812` fix(ios): preserve historical error context at the privacy boundary
- `bffd89d1` fix(android): preserve historical error context at the privacy boundary
- `6005c780` fix(ios): guard encoded identities and unknown provider errors
- `81ae051b` fix(android): guard encoded identities and unknown provider errors
- `de5dc969` fix(ios): persist retired Esplora log registrations across launches
- `192c0825` fix(android): persist retired Esplora log registrations across launches
- `3b563165` fix(ios): align safe Esplora normalization edge cases with RN
- `016fe633` fix(android): align Esplora normalization decisions with iOS
- `9053b21c` fix(ios): retain status from encoded provider error headers
- `708dd55c` fix(android): retain status from encoded provider error headers
- `0f6f48d5` fix(android): omit unsupported HTML entities at the privacy boundary
- `b1685a26` fix(ios): omit unsupported HTML entities at the privacy boundary
- `9907eb48` fix(ios): recognize custom authorities before log delimiters
- `efc312b2` fix(android): recognize custom authorities before log delimiters

## Round-5 changes

Authority extraction uses the same bracket-aware delimiter grammar on both
platforms. iOS uses Foundation's IDNA ASCII host plus the existing
`EsploraEndpoint.canonicalHost` IPv6 routine; Android uses OkHttp's local
`HttpUrl` canonicalizer. Both strip root dots/default ports and look up the
canonical origin in the registration set. This removes the source of the
mismatch rather than expanding a list of spellings. Other origins, including
both default Esplora servers and Noah's production/signet URLs, remain visible.
Ark Info still displays/copies the full endpoint and never logs it.

`apps/fixtures/esplora-log-authorities.json` supplies 135 shared cases: Unicode
and ASCII IDNA, expanded/compressed IPv6, case, root dots, default/nondefault
ports, all reviewed delimiters, decoded forms, and unrelated/public origins.
Both platform tests exercise file/export, app/system logging, Sentry message,
exception and breadcrumb boundaries; iOS also exercises SafeErrorDescription.
The authority fixture and damaged-checksum restart/reset regressions failed on
the original round-4 implementation and pass with these fixes.

The iOS `unknownHistoryStartupProtectsHistoricalExport()` integration test runs
alone with `TEST_RUNNER_NOAH_TEST_LOST_HISTORY=1`, because `failClosed` is
intentionally process-lifetime state. The ordinary full suite still runs the
damaged-store, durable-marker, fallback-write, restart and reset assertions
without changing process-global privacy state. Android runs its export/startup
probe in an isolated Robolectric sandbox using real SettingsStorage and native
files; only filesystem sync is injected because Robolectric does not implement
that host operation.

Scaling retains the 1/16-KiB, 64x-plus-20-ms and two-second assertions, now with
1,000 registered endpoints and additional registered/unrelated URL input
families. The 147-case redaction fixture and 48-case normalization/network/tip
fixture remain unchanged. EsploraOpenWait, recurring, lifecycle/lock, RN, and
server source were not changed.

The first full iOS run exposed one legacy expectation that damaged import must
produce an empty settings map (759 passed, 26 skipped, one failed). It now
requires exactly the unknown-history marker and no imported values; Android's
two corresponding instrumentation assertions use the same expectation. Both
affected iOS tests pass in a focused run. The final full suite is run after
that test-only correction.

Final signed arm64 iOS verification: **786 tests / 156 suites**, **760 passed,
26 opt-in skipped, zero failed**. The full suite ran once after the final test
change, with `xcodegen generate` first, normal signing, `ARCHS=arm64`,
`ONLY_ACTIVE_ARCH=YES`, Nix, and DerivedData at
`/private/tmp/noah-esplora-r5-dd`. Focused verification passed 68 distinct tests
across the privacy/logging/settings suites, including the isolated startup/export
probe and two corrected import expectations. All 135 authority cases, the
unchanged 147 redaction cases, and 48 normalization/network/tip cases pass.

Before deletion, the owned simulator's exact name, UDID and shutdown state were
confirmed: `Noah Esplora R5 t3-5e1892bb`
(`5F6731A7-CBE0-4C1F-83EE-9D29F1D43031`, `Shutdown`). It, its DerivedData and all
seven owned result bundles were removed before the final Android build started.
No shared simulator, emulator, physical device or Docker instance was used.

Final Android verification: **3,574 JVM tests / 136 suites**, zero
failures/errors/skips; regtest APK assembled and instrumentation Kotlin compiled.
The 69-test focused run (six suites) also passed. The required full command ran
once after the last code/test change, with all owned iOS resources already gone:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug :app:compileRegtestDebugAndroidTestKotlin'
```

Every round-5 commit passed the repository pre-commit checks (lint, React
Compiler: 202 files, 148 JS unit tests, TypeScript, Rust formatting). Native
translation `--check` is clean; no UI strings/catalogs changed. Device
instrumentation was compiled rather than run. All runtime probes used local
fakes; no public network requests were made. No owned build remains active.
