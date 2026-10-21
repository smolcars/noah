# Custom Esplora log privacy

Round-4 base: `feat/native-rewrite` at `a5866b02`; review: `54b44dc5`.
Round-5 work starts at the supplied `be132094` HEAD, after the lifecycle merge.
Round 6 starts at `2fa950f1`, with the reviewed `4565c735` in its ancestry.
Round 7 starts at `249cb85f`, including the reviewed round-6 `184c39c6` ancestry.
Round 8 starts at the reviewed round-7 `40371bfd`.
All test credentials are fake.

A custom endpoint's entire identity is private, including scheme, hostname,
port, and path. `EndpointLogPrivacy.sanitize` is the shared native boundary.
AppLog's file and system sinks reach it through `LogFile.redactForTelemetry`;
Sentry message/exception/breadcrumb scrubbers and final payload guards use that
same path. Export sends retained records through the boundary too. Endpoint
settings error text and iOS `SafeErrorDescription.of` use `LogFile.redact`.
Raw SDK text from `SafeErrorDescription.forLogging` and Bark callbacks only enters a bounded
queue; the writer applies the boundary before either sink. Ark Info continues
to show/copy the full endpoint on screen.

## Current policy (round 8)

Round 7 adds registered secret tokens to the round-6 authority allowlist below.
The acceptance boundary is one record at every Noah-owned sink: a token derived
from any current or retired registered endpoint must not survive, including
inside an admitted public URL, arbitrary prefixes, or a later line/record that
contains a complete token. The durable history and fail-closed marker are
unchanged. No-registration/no-marker mode retains its previous behavior.

Registrations derive the following tokens locally, without DNS/network access:

- Full canonical hostnames, including canonical IP literals and both ASCII IDNA
  and Unicode spellings. Each non-public host label of at least six characters
  is also protected. The final DNS label is excluded from label extraction;
  fixed common suffix labels (`com`, `org`, `net`, `edu`, `gov`, `co`, `ac`),
  reserved `example`/`localhost`, and host labels from shipped public origins are
  excluded too. Full hosts are always protected, even when their labels are
  public. Thus `fake-r6-private.example` protects the full name and
  `fake-r6-private`, without making every occurrence of `example` private.
- Userinfo (including the individual username/password components) of at least
  six characters. Selection validation still rejects userinfo and query strings;
  registration nevertheless derives their tokens for legacy/corrupt history.
- Each opaque path segment and query value of at least six characters, in raw
  and decoded forms. For example `/api/fakeR6PathSecret913?key=fakeQuerySecret761`
  protects both fake secrets. A fixed route-word list excludes `api`, `block`,
  `blocks`, `tip`, `height`, `hash`, `tx`, `txs`, `transaction`, `transactions`,
  `address`, `scripthash`, `mempool`, `v0`, `v1`, `v2`, network names, `esplora`,
  `fee-estimates`, `outspend`, `outspends`, `status`, `hex`, `raw`, `merkle-proof`,
  `merkleblock-proof`, `utxo` and `broadcast`. Route-word exclusions apply to
  path/query tokens, not private host labels or userinfo.
- A port only with its host; `18443` alone remains useful diagnostic text.

An immutable Aho–Corasick trie combines all registrations. Breadth-first failure
links propagate a terminal-hit bit, so suffix and overlapping matches need no
per-pattern searches or match arrays. Registration invalidates the cached
matcher only when tokens change; the first subsequent record rebuilds it.
Android also caches immutable origin/host snapshots instead of copying history
for every record. Round 8 retains raw token spellings and scans raw and
locale-independent lower/upper-cased UTF-16 views alongside the NFKC/cased view.
This preserves a complete raw token when an adjacent combining mark would compose
with its final scalar. The NFKC view uses a final NFKC pass after casing
(including `Straße`/`STRAẞE`/`STRASSE`). The final pass makes normalization
idempotent when casing expands a character into combining marks. Work per view is O(record length), independent of the number
of registrations; storage is O(total token length). The bounded decoder retains
four escape layers, normalizing compatibility forms before each layer so that
fullwidth percent/backslash/entity punctuation cannot hide a supported escape.
Clean-install decoding keeps its previous defaults.

The existing URL/host layer runs first and retains its exact authority outputs.
If a secret token remains anywhere, the entire record becomes
`[custom-esplora]`. Decoded/compatibility-view hits also omit the whole record;
there are no uncertain source-offset substitutions. Provider type/status
summaries pass the token matcher too. Swift's URL and bare-host replacements
now collect stable NSString/UTF-16 ranges and assemble output once per layer,
without repeated indexing/mutation of a growing String. There is no remaining
O(registrations × record length) relative-path scan.

Round 8 derives Android Unicode host spellings with device `android.icu.text.IDNA`
nontransitional UTS #46, consistent with OkHttp's IDNA2008 deviation-character
handling. JVM tests bridge that API to test-only ICU4J 78.3; a Robolectric fixture
test also exercises the Android API implementation. Conversion errors fail
closed. IPv6 literals bypass IDNA. Shared vectors cover ß, final sigma, and
contextual Devanagari ZWJ/ZWNJ names; JDK IDNA2003 is no longer used.

A final `redactRecord` token guard runs after file timestamp/tag/indentation
formatting, including the trailing LF and generated overflow records, and immediately before app-owned
OSLog/Logcat delivery. It inspects raw, decoded and compatibility forms with the
same matcher and honors the fail-closed marker. It preserves normal-mode output,
timestamps and already-extracted provider summaries when no token hits. Sentry's
message/breadcrumb boundary already receives complete formatted text; the shared
composition regressions cover it too. Fallback error type names pass through
`LogFile.redact`, including iOS's type-only `forLogging` fallback. Ordinary
noncolliding names are unchanged.

### Accepted limitations

- **One token split mid-token across records or lines.** Matching protects a
  complete token on any later line or in a separate record; it does not join
  token fragments around a newline or pool fragments from separate records.
  There is no reliable way to distinguish wrapping from independent text.
  Guessing joins would erase unrelated diagnostics and cross-record pooling
  would require additional retained state beyond the bounded record boundary.
  This is acceptable because the guarantee concerns complete registered tokens
  within one record, including supported encodings, rather than arbitrary
  reconstruction of fragments.
- **Tokens shorter than six characters.** Opaque segment/query/label extraction
  excludes these because short strings and ordinary route words occur throughout
  routine diagnostics. Treating them as secrets everywhere would obscure most
  operational failures. Whole registered hosts and the conservative authority
  layer still protect short endpoint authorities. This threshold is an explicit
  tradeoff for useful logs, rather than an assertion that short text is secret.
- **Secrets absent from the registered URL.** Registration cannot derive
  provider-generated credentials, unrelated application data, or lost endpoint
  identities. Existing mnemonic/credential/provider-body guards remain in place;
  unreadable history still fails closed. This is acceptable because an endpoint
  policy can prove protection only for known URL-derived identities, and callers
  must continue to avoid logging other secrets.

## Authority policy (retained from round 6)

The round-5 review at `4565c735` showed why a denylist of equivalent spellings
cannot be the primary URL boundary. Round 6 inverts admission: once any current
or retired custom endpoint has been registered, every `scheme://` URL token is
replaced with `[custom-esplora]` unless its canonical origin is explicitly public.
The scheme, userinfo, host, port, path, query and fragment are removed together,
through the token's end. Whitespace, quotes and angle brackets end the token;
other trailing punctuation is conservatively removed with it. Even malformed
ports and authorities are removed without needing to recognize a registered host.

The allowlist is built once from native `ServerEndpoint.baseURL` and
`WalletConfiguration.base` for all variants, including configured Noah, Ark,
default Esplora and regtest Bitcoin Core origins. iOS uses its bundle/build and
regtest launch configuration; Android uses `BuildConfig.REGTEST_SERVER_URL` and
`REGTEST_HOST`. A fixed public list in each `EndpointLogPrivacy` covers shipped
mempool explorer/prices, Expo push, BTC Map/map tiles/attribution, directions,
support, repository and store links. No selection, imported endpoint or other
user input contributes to this allowlist. A registered custom origin overrides
the allowlist, including a user-entered endpoint equal to a default.

Only a strict ASCII authority without userinfo can inherit public admission.
A token must contain exactly one authority; a public origin cannot hide a nested
URL in its path. The public URL tail also passes through registered-host
inspection, so appended fields, JSON arrays and encoded bare hosts cannot inherit
the public origin's admission.
Backslashes, noncanonical IP spellings, unusual authority characters, malformed
ports/percent escapes and parse failures redact. Public URL parsing is local and
never resolves DNS. Percent, JSON and HTML entity decoding remains bounded to
four layers; uncertain decoding has no admission path. A redaction in a decoded
view omits the whole message because decoding changes source offsets. Unsupported
named HTML entities fail closed; numeric entities retain optional-semicolon support.

With no custom history and no fail-closed state, URL handling keeps the previous
behaviour, including full unrelated-origin diagnostics; ordinary credential,
mnemonic, provider-body and uncertain-decoding guards still apply. The durable
fail-closed marker is unchanged: it replaces whole records with `[custom-esplora]`
for the installation lifetime, even after healthy writes/reset.

Registered-host canonicalization remains a second layer for scheme-less SDK
mentions such as `host:port`, including single-label hosts, IDNA, bracketed or
unbracketed IPv6, and WHATWG IPv4 forms. Brackets only group IPv6 tokens; other
bracket/JSON contents still receive ordinary host inspection. Uncertain
host parsing redacts. Host lookups use a set rather than scanning the registered
endpoints for each token. Round 7 supersedes the relative-base-path scan with the token matcher above;
public URL tails and complete wrapped path tokens receive the same protection.
Status/error context outside redacted URL or scheme-less host tokens stays visible;
provider errors continue to retain only their recognized type and status.

This intentionally supersedes round 4's unrelated-origin preservation rule while
custom history exists: LNURL callbacks and other origins outside that configuration and fixed list
are also removed, even when unrelated to Esplora. This loses some diagnostics to
avoid another alternate-spelling escape. Resetting to a default does not restore
those diagnostics because retired history remains protected. Ark Info still
shows/copies the full endpoint on screen and never logs it.

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

The shared `apps/fixtures/esplora.json` now contains 41 normalization cases,
eight genesis cases, and four height cases (53 total, retaining the original 48).

- Match RN/Android: reject scoped IPv6 before Foundation can embed an interface
  index in the address, and validate the raw supplied port before parsing can
  discard an overflowing value. Ports must be decimal integers in 1...65535.
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

- URL admission, registered-host layer, provider boundary and historical records:
  both `Services/Logging/EndpointLogPrivacy.swift` and
  `services/logging/EndpointLogPrivacy.kt`.
- Decoding and common file/system/telemetry boundary: both `AppLog` files;
  Sentry admission and final payload guards: both `AppTelemetry` files.
- Durable history/unknown marker: both `EsploraSettings` and `SettingsStorage`
  files. Startup restores protection before SDK logging. Both
  `retainingLogPrivacy` helpers preserve it through reset.
- URL validation: both `EsploraEndpoint` files. Shared normalization cases:
  `apps/fixtures/esplora.json`; shared exact authority outputs:
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

## Round-6 regression evidence

The shared authority fixture retains the previous 135 cases and adds 107 inputs
(242 total), now with identical exact expected outputs on both platforms. It
covers all reviewer inputs: `user!name@host`, fullwidth-plus hosts, decimal/octal/
hex/shortened IPv4, invalid and multiply-coloned ports, arbitrary delimiters,
encoded forms, bare hosts and public-origin lookalikes. Both platform runners
exercise app/system log delivery, file/export and Sentry messages, exceptions and
breadcrumbs; iOS also checks SafeErrorDescription. Separate policy snapshots test
all fixture inputs with no registrations and verify that user-entered defaults
are never admitted as public.

Before implementation, the expanded permanent Android authority test failed;
normalization already rejected all five new cases on Android. A standalone Swift
probe compiled the unmodified production privacy/normalization functions and
the production decoder against these same fixtures: 95 mismatches, including the
reviewed authority leaks, scoped IPv6 rewrite and overflowing port. The fixed
Swift probe passes all cases. A later self-check found that an allowed public URL
could hide a private URL/host in its path, and that single-label/bracketed bare
hosts could be skipped. Permanent shared probes reproduced these gaps before the
final boundary changes (including a failing Android fixture test and 12 Swift
probe mismatches, then three bracket/array mismatches). The final lexer checks
single-label registrations without treating ordinary long words as hostnames;
punctuation/JSON separators stay visible. A real-logger Swift probe also
reproduced two composition failures: registering a credential-label hostname or
a BIP39 word could replace that text before the general secret detector saw it.
The boundary now inspects both the original view and its general-secret-redacted
view before emitting text. Provider type/status extraction still runs first.
Explicit policy snapshots reuse four vectors from the unchanged 147-case fixture
(mnemonic and credential labels), also asserting a retained HTTP 503/error prefix;
they avoid changing process-global registrations. The real Swift logger probe
passes all authority cases and both composition probes after the fix. All 242 shared cases pass the final
Swift probe and focused Android run. Final full suites run after these last
source/test changes; the earlier successful full runs are not the final verification.

The original 147-case redaction fixture is unchanged. It is checked under an
explicit clean-install policy so process-global registrations cannot make the
baseline expectations depend on test order. SDK/Sentry/file sinks additionally
check the active policy, whose neutral URL marker deliberately differs for some
original URL inputs. Scaling retains the 1/16-KiB, 64x-plus-20-ms and two-second
limits with 1,000 registrations, adding allowed-origin, malformed-authority and
scheme-less-host families. Scans are bounded at scheme/open-bracket candidates;
repeated failed candidates do not rescan the remaining suffix.

Initial focused verification passed **69 Android tests / five suites** and **64 iOS
tests / five suites**. iOS used a signed arm64 regtest build on the owned
`Noah Esplora R6 adb1ee9c` simulator, with package updates disabled. All probes
use fake credentials and local fakes; no public endpoint requests are required.
Native translation `--check` is clean. The pre-commit hook's lint, React Compiler
(202 files), 148 JavaScript tests, TypeScript and Rust formatting checks pass.
The fresh worktree's missing Husky shims/dependency links were restored locally;
no tracked RN or server files changed.

## Round-6 implementation commits

- `6f35f104` test(native): share round-six Esplora authority privacy regressions
- `fbd35dbe` fix(android): redact URL authorities unless configured public
- `fcb9377b` fix(ios): allow only configured public URL authorities in private logs
  (also adds the two shared URL-adjacent prefix cases)
- `3a619ec9` test(native): cover nested authorities and single-label log hosts
- `6607e79f` fix(android): inspect public URL tails and bare custom hosts
- `975ea4ea` fix(ios): inspect public URL tails and bare custom hosts
  (also preserves general secret detection and adds the two bare IPv6 cases)
- `d1f47e39` fix(android): preserve secret detection during endpoint redaction

Each commit retains the requested Claude Opus 5.5 co-author trailer. The final
documentation commit records the policy, verification and cleanup below.

## Round-6 final verification and cleanup

Final signed arm64 iOS verification: **789 tests / 157 suites**, **763 passed,
26 opt-in skipped, zero failed**. The complete NoahTests suite ran once after the
last source/test change, with `xcodegen generate` first, normal signing,
`ARCHS=arm64`, `ONLY_ACTIVE_ARCH=YES`, package updates disabled, Nix, and
DerivedData at `/private/tmp/noah-esplora-r6-dd`. A pre-final full run passed;
a subsequent rerun was interrupted to fix the demonstrated general-secret
composition gap. Neither substitutes for this final full result.

Both owned simulator identities and shutdown states were confirmed before
removal: `Noah Esplora R6 adb1ee9c`
(`17B74D08-03A2-456E-BA2E-5F9944BF7267`, `Shutdown`) and
`Noah Esplora R6 Final adb1ee9c`
(`F3DC7DEF-E3E4-49E4-80B0-B0A91D5C9AF6`, `Shutdown`). Both simulators, all owned
DerivedData and all four named result bundles (focused, pre-final full,
interrupted, final complete) were removed before the final Android command.
No shared/forbidden simulator, emulator, physical device or Docker instance was
used. All runtime probes used local fakes; no public endpoint requests were made.

Final Android verification: **3,577 JVM tests / 138 suites**, zero failures,
errors or skips; regtest APK assembled and instrumentation Kotlin compiled. The
required full command ran once after the last source/test change, with all owned
iOS resources already removed:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug :app:compileRegtestDebugAndroidTestKotlin'
```

Both final suites include all 242 authority cases, the unchanged 147-case
redaction fixture, all 53 Esplora cases (the original 48 plus five regressions),
and the 1,000-registration scaling checks at their original limits. Native
translation `--check` is clean. Instrumentation was compiled rather than run.
Every commit passed the normal repository pre-commit hook; no tracked RN,
server, screen, recurring, lifecycle/lock or EsploraOpenWait source changed.
No owned native build remains active. Temporary dependency links are removed
after the documentation hook completes.


## Round-7 regression evidence

The original 242 authority cases, 147 redaction cases and 53 Esplora cases stay
unchanged. `apps/fixtures/esplora-log-tokens.json` adds 28 exact shared outputs:
colon-prefixed bare hosts, complete secrets on wrapped lines and separate
records, admitted public URL tails, arbitrary prefixes, case, private host labels,
retired paths, query/userinfo, percent/JSON/HTML escapes, NFKC fullwidth forms,
IDNA labels and Unicode case expansion. Both native runners cover app/system,
file/export and Sentry message/exception/breadcrumb boundaries; iOS also covers
SafeErrorDescription. The fixture includes ordinary route diagnostics and a bare
port that remain visible. Automaton tests cover inherited suffix terminals,
Unicode surrogate pairs, negative matches, idempotent Greek combining-mark normalization, capital sharp S
and provider-summary token collisions.

Before implementation, Android's permanent new sink regression failed on
`peer:fake-r6-private.example:8443`. A standalone Swift probe compiled the
unmodified production EndpointLogPrivacy, EsploraEndpoint and decoder (with local
configuration/general-redactor stand-ins): 12 of the first 15 shared token cases
failed, including all reviewed leak shapes. The production-function probe passes all 242 authority and all 28 token cases.
A local JDK self-check also demonstrated that NFKC-plus-uppercase is not
idempotent for Greek combining-mark expansion; the final normalizer includes a
second NFKC pass and lower/upper casing, with permanent tests on both platforms. This probe supplements the full
native suites; it does not substitute for real logger/telemetry verification.

Scaling retains the existing 1/16-KiB, 64x-plus-20-ms and two-second assertions
with 1,000 registrations. New families cover long records without `/`, with `/`,
with a token hit, and repeated fullwidth `ａ ` under an isolated short-host
snapshot. The fullwidth input uses the boundary's larger export-record allowance
so the 16-KiB UTF-16 input is inspected instead of hitting the ordinary file
message byte cap. The translation coverage inventory adds the route word
`blocks` as a scanner false positive; no UI/catalog strings changed.

Round-7 iOS scaling medians (1 KiB → 16 KiB, 1,000 registrations): file boundary
without slash 2.194 → 34.140 ms, with slash 1.978 → 29.601 ms, token hit
0.546 → 8.051 ms; endpoint-only boundary without slash 1.343 → 20.393 ms,
with slash 1.257 → 18.626 ms, token hit 0.541 → 7.953 ms. Fullwidth bare hosts
under the isolated `a` policy: 9.871 → 157.227 ms (15.9x for 16x input).
All scaling assertions pass. AppLog's rotation test completes in 6.405 seconds,
its 4,100-record export test in 1.950 seconds, and the entire AppLogTests suite
in 18.264 seconds. The shared authority sink test completes in 0.677 seconds,
the new token sink test in 0.188 seconds.

## Round-7 final verification and cleanup

Final signed arm64 iOS verification: **813 tests / 164 suites**, **787 passed,
26 opt-in skipped, zero failed**. The complete NoahTests suite ran once after
the last source/test change, with `xcodegen generate` first, normal signing,
`ARCHS=arm64`, `ONLY_ACTIVE_ARCH=YES`, Nix, package updates/resolution disabled,
and DerivedData at `/private/tmp/noah-esplora-r7-dd`. The Swift Testing run took
278.205 seconds. Two earlier pre-test builds were interrupted while the Unicode
normalizer was corrected; neither ran the suite or substitutes for this result.

The owned simulator's exact identity and shutdown state were confirmed before
deletion: `Noah Esplora R7 7e363ae9`
(`42F71734-2AAD-43B6-AE37-3CB02021556D`, iPhone 17, iOS 27.0, `Shutdown`).
It, the owned DerivedData and all three owned result bundles were removed before
the final Android command. No shared/forbidden simulator, emulator, physical
device or Docker instance was used. Probes and tests use fake secrets and local
fakes; no public endpoint requests were made.

Final Android verification: **3,606 JVM tests / 149 suites**, zero failures,
errors or skips; regtest APK assembled and instrumentation Kotlin compiled.
The required command ran once after the final source/test changes and after
owned iOS resources were removed; it completed successfully in 2m 9s:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug :app:compileRegtestDebugAndroidTestKotlin'
```

Android final scaling medians (1 KiB → 16 KiB): file boundary without slash
1.028 → 14.720 ms, with slash 0.735 → 11.320 ms, token hit 0.140 → 1.620 ms;
endpoint-only boundary without slash 0.402 → 7.024 ms, with slash 0.417 →
7.658 ms, token hit 0.094 → 1.478 ms. Fullwidth bare hosts: 2.010 → 31.736 ms
(15.8x for 16x input). All scaling assertions pass. AppLogTest completes in
9.305 seconds. The earlier focused Android run passed 70 tests in five suites;
the final full run includes the subsequent compatibility/casing tests.

Both final suites pass all unchanged 242 authority, 147 redaction and 53 Esplora
cases, plus all 28 shared token cases and the automaton/scaling tests. Native
translation `--check` is clean. Every commit passes the normal pre-commit hook
(lint, React Compiler, 148 JavaScript tests, TypeScript and Rust formatting),
without bypassing hooks. No tracked RN, server, layout, wake, recurring,
lifecycle/lock, settings or EsploraOpenWait source changed. Android device
instrumentation was compiled rather than run. No owned native build remains
active; temporary dependency links are removed after the final hook completes.

## Round-7 implementation commits

- `f27ea17a` test(native): share round-seven Esplora secret-token vectors
- `04ec75f5` fix(android): scrub registered Esplora tokens with a cached automaton
- `026b0d76` fix(ios): match Esplora secrets linearly across encoded log records
- `6fac582d` fix(android): keep Unicode secret normalization idempotent
- `b01d5e36` fix(ios): canonicalize Unicode after secret-token casing

The final documentation commit records this policy, verification and cleanup.
All round-7 commits retain the requested Claude Opus 5.5 co-author trailer.


## Round-8 regression evidence

All original 242 authority, 147 redaction and 53 Esplora cases are unchanged.
`esplora-log-tokens.json` retains its 28 cases and adds 24 (52 total): combining
acute/ring/cedilla adjacency with raw/case/percent/JSON/HTML tokens, a decomposed
registered path, and eight IDNA2008 full-host/private-label cases.
`esplora-log-records.json` adds eight shared vectors for tag/message composition,
Bark callbacks, generated overflow text/tag composition, file indentation and
terminator composition, and platform fallback type names. Both platforms run
these fixtures; Android also checks actual Robolectric Logcat, and iOS captures the exact OSLog delivery seam.
The existing sink matrix checks file/export, app logging, UI SDK errors and Sentry
message/exception/breadcrumb boundaries for all 52 token cases.

Before implementation, Android's permanent combining and IDNA fixture tests failed
on `prefixfakeOpaquésuffix` and `peer:straße.example`; the permanent record tests
failed on raw-file composition, overflow and `SocketTimeoutException`. The
record tests use a plain Robolectric Application and distinct instrumentation
configuration, avoiding real Noah startup's unavailable-store policy and isolating
the process-lifetime fail-closed marker from the lost-history test. A standalone
Swift 6 probe compiles the production AppLog, EndpointLogPrivacy, EsploraEndpoint and SafeErrorDescription
(with local configuration and Bark error-type stand-ins). It reproduced nine
token failures plus all seven applicable record failures (16 total). After the
normalization fix, all 52 token vectors passed while the initial six record
failures remained. A final complete-record probe caught terminator composition
against the previously fixed code; file guards now include the trailing LF. The
complete fix passes all 52 token vectors and seven applicable iOS record vectors
(the eighth record vector names Android's fallback type). This probe
supplements, rather than replaces, the native suite's real Bark/Sentry tests.

Scaling keeps 1,000 registrations, the 1/16-KiB inputs, original
64x-plus-20-ms tolerance and two-second ceiling, and the fullwidth short-host
case. New endpoint-boundary families exercise combining-mark hits/misses and
IDNA2008 hits/misses with ß, sigma and joiner characters.

A focused Android run passed 77 tests in six suites. A pre-final full attempt
exposed two new-test failures because the lost-history test's process-lifetime
marker survived in a cached Robolectric sandbox; the distinct instrumentation
configuration fixes that isolation issue. The mixed lost-history/record run then
passed all five tests. A later pre-final attempt was stopped after a wall-clock
scaling assertion during concurrent simulator startup/build activity; the
scaling thresholds are unchanged. Overflow fixture counts are distinct synthetic
37/41 values, and the composed-tag token includes its count, so global iOS
registrations cannot mask other tests' ordinary 3/19-record overflow markers.


## Round-8 final verification

Final Android verification: **3,612 JVM tests / 150 suites**, zero failures,
errors or skips; regtest APK assembled and instrumentation Kotlin compiled.
The required command passed after the final source/test/fixture changes in
1m 49s, before starting the owned iOS build:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug :app:compileRegtestDebugAndroidTestKotlin'
```

Android final scaling medians (1 KiB → 16 KiB, 1,000 registrations): file
boundary without slash 0.765 → 11.470 ms, with slash 0.715 → 16.933 ms, token
hit 0.125 → 1.912 ms; endpoint-only boundary without slash 0.458 → 7.298 ms,
with slash 0.461 → 7.168 ms, token hit 0.122 → 1.886 ms. New combining-mark
miss/hit families: 3.801 → 93.894 / 1.082 → 24.444 ms; IDNA2008 miss/hit:
2.127 → 35.207 / 1.279 → 20.269 ms. Fullwidth bare hosts: 4.698 → 74.777 ms
(15.9x for 16x input). All original scaling assertions pass.


Final signed arm64 iOS verification: **818 tests / 165 suites**, **792 passed,
26 opt-in skipped, zero failed**. The complete NoahTests suite ran once after the
final source/test/fixture changes, with `xcodegen generate` first, normal signing,
`ARCHS=arm64`, `ONLY_ACTIVE_ARCH=YES`, Nix, package updates/resolution disabled,
and DerivedData at `/private/tmp/noah-esplora-r8-dd`. Swift Testing took
320.975 seconds. All new privacy suites and the complete existing suites pass.

iOS final scaling medians (1 KiB → 16 KiB, 1,000 registrations): file boundary
without slash 2.599 → 39.336 ms, with slash 2.475 → 35.928 ms, token hit
0.527 → 8.015 ms; endpoint-only boundary without slash 1.855 → 26.385 ms,
with slash 1.451 → 21.453 ms, token hit 0.510 → 8.124 ms. New combining-mark
miss/hit families: 4.632 → 74.491 / 2.842 → 46.113 ms; IDNA2008 miss/hit:
3.547 → 59.045 / 3.430 → 59.116 ms. Fullwidth bare hosts: 10.411 → 163.486 ms
(15.7x for 16x input). All original scaling assertions pass.

Both final suites pass the unchanged 242 authority, 147 redaction and 53 Esplora
cases, all 52 shared token cases, and the applicable shared record vectors
(seven per platform, eight in the fixture), plus automaton/scaling regressions. Normal mode,
the round-6 allowlist, the durable marker and the three accepted limitations are
unchanged. Native translation `--check` is clean. Android device instrumentation
was compiled rather than run. All runtime probes use local fakes; no public
endpoint requests were made.

Before deletion, the owned simulator's exact identity and shutdown state were
confirmed: `Noah Esplora R8 96b50281`
(`E2C3F536-B698-4344-96A9-6D83AF314FF4`, iPhone 17, iOS 27.0, `Shutdown`).
The simulator, owned DerivedData, the single owned result bundle and standalone
probe directory were removed. No shared/forbidden simulator, emulator, physical
device or Docker instance was used. No tracked RN, server, layout, wake, recurring,
backup, settings or EsploraOpenWait source changed. No owned native build remains
active. Temporary dependency links and hook shims are removed after the final
hook completes.

## Round-8 implementation commits

- `ed65a2b4` test(native): reproduce round-eight endpoint token leaks at final sinks
- `b92f4a34` fix(android): retain raw endpoint token matches beside normalized views
- `efdb8327` fix(ios): preserve raw and folded endpoint token boundaries
- `e30faa2e` fix(android): derive endpoint Unicode tokens with nontransitional UTS46
- `fe4b3646` fix(android): guard complete file and Logcat records after formatting
- `75c07012` fix(ios): guard formatted file and system records before emission
- `efa3f5d2` fix(android): redact fallback error type names
- `3edef27e` fix(ios): pass fallback error type names through endpoint privacy

The final documentation commit records verification and cleanup. Every round-8
commit passes the normal pre-commit checks and retains the requested
`Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` trailer.
