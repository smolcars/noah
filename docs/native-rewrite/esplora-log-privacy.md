# Custom Esplora log privacy

Base: `feat/native-rewrite` at `a5866b02`. All test credentials are fake.

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
from relative-path matching. Known authorities also match before trailing log
delimiters, with explicit default-port spellings and DNS root dots normalized.
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

## Regression evidence

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

1. Whole endpoint identity: iOS `EndpointLogPrivacy.swift:35`; Android
   `EndpointLogPrivacy.kt:28`. HTML decoding: iOS `AppLog.swift:241`; Android
   `AppLog.kt:189`.
2. Shared provider boundary: iOS `EndpointLogPrivacy.swift:67` and
   `AppLog.swift:232`; Android `EndpointLogPrivacy.kt:59` and `AppLog.kt:180`.
   Historical records: iOS `EndpointLogPrivacy.swift:36`; Android `:27`.
   Error display: iOS `EsploraSettingsScreen.swift:72`; Android `:45`.
3. Durable history: iOS `EsploraSettings.swift:34`; Android `EsploraSettings.kt:18`.
   Startup: iOS `AppDelegate.swift:26`; Android `NoahApplication.kt:10`.
   Reset retention: iOS `SettingsStorage.swift:165`; Android `:100`.
4. Other-origin exclusion: iOS `EndpointLogPrivacy.swift:91`; Android
   `EndpointLogPrivacy.kt:75`.
5. Normalization: iOS `EsploraEndpoint.swift:10`; Android `EsploraEndpoint.kt:13`.
   Shared cases: `apps/fixtures/esplora.json`.

## Implementation commits

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
