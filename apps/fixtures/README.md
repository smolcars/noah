# Shared native fixtures

One JSON file per domain, consumed by Android JUnit and iOS XCTest:

```json
{
  "cases": [
    { "name": "descriptive unique case name", "input": {}, "expected": {} }
  ]
}
```

`input` and `expected` are domain-specific JSON values. Keep fixtures deterministic,
platform independent and free of real wallet secrets. `scaffold.json` is a loader
smoke test; future domains should exercise actual shared behavior.

Android adds this directory to its test resources. `SharedFixtures.load("domain")`
parses the envelope and returns named cases without depending on the working directory.

Home/Send/Board cutover parity: `home-balance-details.json` specifies every SDK
balance bucket, including immature coins and all pending Ark operations; both platforms
assert the same rows and hidden amounts. FFI 0.5.0 reports onchain pending as one
combined value, so the fixture deliberately does not invent trusted/untrusted values.
`payment-status-copy.json` holds the chosen common network/retry/boarding wording and
two board receipt totals (reviewed fee fallback and actual fee). Both platforms assert
that table; lookup failures are transient display state and never durable evidence.
These two fixtures are hand-specified from the RN Home rules and the cutover decisions.

Send inputs bark accepts and RN refused: `send-destination-native.json` (282 cases) is
native-only and hand-specified from bark 0.8 (`959a489f`) and the decision recorded in
`docs/native-rewrite/ffi-phase-b.md` (rows 5, 16, 18 and 19). It covers Lightning
addresses by bark's rule (long TLDs, `+` tags, internationalised domains, a leading `₿`
that is stripped), LUD-17 `lnurlp://` (HTTPS only), dot segments in `lnurlp://` and
`lnurl1` paths (plain or percent-encoded, resolved as OkHttp resolves them; `%2f` stays),
BIP-321 `req-ark` / `req-amount`, and LNURL-auth refused at parse time. Bounds, network
checks, HTTPS-only LNURL and typed errors stay as they are; every LNURL form (address,
`lnurlp://`, `lnurl1` and the pay-request callback) refuses userinfo and `.onion` hosts,
which would need cleartext. Its shape is `send-utils`, plus `url` for `lnurl` results and
refused LNURL forms: the HTTPS endpoint resolution requests first, or null when
resolution refuses before any request. `error` is the English message. The RN-generated
`send-utils.json` and `send-amount-safety.json` have no vectors for these inputs and pass
unchanged, so no RN vector is overridden.

M6 boarding/exits: run `bun --tsconfig-override client/tsconfig.json
apps/fixtures/tools/generate-exits.ts` from the repository root. The generator imports
RN's `exitFeeEstimate`, `exitTimeline`, `useExitOverview`, and `buildAutoBoardPlan`;
only platform/network dependencies are mocked. `exit-fee-estimate` (82),
`exit-cancellation` (56), `exit-timeline` (12), `exit-overview-coordination` (16),
and `auto-boarding` (21) contain 187 portable cases. Swift runs these in
`ExitFixtureTests`; Android can reuse the files without running React Native.

M7 unattended fee cap: `recurring-fee-cap` (24 cases) is native-only (RN pays recurring
Lightning without a cap) and hand-specified from the decision recorded in
`docs/native-rewrite/TODO.md` (M7). The cap comes from `ArkInfo.feeSchedule.lightningSend`,
with the published fallback schedule when ArkInfo is unavailable. The description field
states the formula. The regtest cases use captaind's local schedule
(`scripts/captaind.toml`) and include the two BOLT11 quotes verified on the Pixel.

M7 RN numeric storage: `recurring-rn-range.json` retains the exact 11 RN-written
schedule rows and numeric tokens from Android commit `a4ea8f0e`, with a native-only
`expectedClassifications` map added at envelope level. Both platforms assert its
classification for every row; the reason documents the boundary being exercised.
The four representable count rows are active. Counts beyond signed 64-bit range,
fractional timestamps and starts beyond JavaScript Date range are stopped-unsupported:
visible as Needs attention, never executed or rounded, and cancellable.
Android's historical local resource is unchanged; tests use the shared expectations.

RN's real `validateRecurringPaymentInput`, `createRecurringPayment`, and
`useRecurringPaymentStore.upsertSchedule` through Zustand `persist` /
`createJSONStorage` produced the original rows. Only MMKV, logging, translation
and unused React hooks were mocked. The original writer script remains at
`/tmp/noah-android-recurring-r3-rn.ts` and uses Bun with
`--tsconfig-override client/tsconfig.json` and installed dependencies via `NODE_PATH`.
The pre-expectations shared/Android copies had SHA-256
`c96c5f940008ac97f3f0757b79cef276381a56ac8e9fcca4a8d1cd27b23954b3`.
The fixture contains no wallet secrets.

RN's store does not configure a persistence version. Zustand defaults to numeric
0 and `createJSONStorage` uses `JSON.stringify`, producing only the token `0`.
Native accepts that exact token or an absent legacy version. `1e-400`, `0.0`,
`-0`, `0e5`, `"0"`, `1`, and `1e400` all make the entire store unavailable;
original bytes survive and execution/mutation stop. Whitespace around `0` is JSON
formatting and remains accepted. Even `JSON.stringify(-0)` produces `0`.

Normal RN timestamps come from `Date.now()`, the editor's local `Date` constructor
(with seconds/milliseconds zero), and calendar `Date.getTime()`; retries add whole
millisecond delays. Thus normal writes have whole-millisecond start/end/next-run,
created/updated, retry, in-flight and run-history times. The factory/validator can
accept synthetic fractional inputs (as these range rows demonstrate), but native
classifies any fractional time field as an unsupported row and preserves its values.

Upgrade safety: both platforms consume `launch-state.json` (6 cases) and
`launch-seed-safety.json` (30 cases). Inputs are individual source reads (`found`
with the raw string, `absent`, `unavailable`, duplicate/conflicting candidates or
invalid encoding), plus database presence, multiple database candidates and the
SDK fingerprint relation. No case substitutes a final unavailable seed status
for validation. Only public BIP39 vectors are present. These fixtures exercise
production validation/agreement/launch inspection; iOS additionally checks real
SDK fingerprint rejection without DB changes. An `existingWallet` inspection is
a candidate to open: the SDK must still verify the fingerprint before migration.

| Source/database condition | Shared outcome | Diagnostic reason |
| --- | --- | --- |
| All sources absent, no data | `onboarding` | `none` |
| All sources absent, data present | `walletDataWithoutSeed` | `none` |
| Valid byte-identical sources, no data | `seedWithoutWalletData` | `none` |
| Valid byte-identical sources, matching DB | `existingWallet` | `none` |
| Empty/invalid BIP39 or invalid credential encoding | `invalidSeed` | `invalidMnemonic` |
| Unavailable source, even with another valid source | `secureStorageUnavailable` | `sourceUnavailable` |
| Disagreeing original bytes or duplicate/foreign Keychain candidates | `seedConflict` | `sourceConflict` |
| Valid seed, SDK fingerprint mismatch | `seedConflict` | `walletFingerprintMismatch` |
| Two database candidates | `seedConflict` | `multipleDatabases` |

Multiple database candidates take precedence. Source failures block before
agreement/fingerprint checks; if several sources fail, the first source failure
is reported. Absence never overrides another source's failure. All blocking
outcomes preserve source and database bytes, with no automatic create/restore.

`stored-mnemonic.json` (37 cases) shares normalization vectors: NFKD, lowercase,
Unicode White_Space splitting, and joining words with one ASCII space. The space
set is U+0009–000D, U+0020, U+0085, U+00A0, U+1680, U+2000–200A, U+2028–2029,
U+202F, U+205F and U+3000. U+001C–001F, U+180E, U+200B and U+FEFF are not word
separators. Normalization applies to wallet SDK input, auth `m/350/0` derivation
and signing. Validation retains the original credential and agreement compares
its bytes, including canonically equivalent whitespace with different UTF-8.

RN itself performs **no normalization** in `crypto.ts` Keychain reads/writes or
`walletApi.ts` wallet/auth calls. Its restore screen calls JavaScript `trim()`
(ECMAScript WhiteSpace + LineTerminator, including U+FEFF, excluding U+0085),
then passes the remaining string unchanged to Nitro/Bark. Native's existing
BIP39 acceptance of NFKD/case/Unicode spacing is intentionally broader and must
be consistent across every SDK input. Backups always use the original string:
iOS PBKDF2 receives its UTF-8 bytes; Android receives its original character array
(UTF-8 PBKDF2 semantics). Neither backup encryption nor decryption normalizes it.
The four decorated crypto vectors exercise original-password backup round trips;
iOS uses real SDK auth and offline open, Android checks SDK argument boundaries.

RN store envelopes: from the repository root, run:

```sh
NODE_PATH=<installed-repo>/node_modules bun --tsconfig-override client/tsconfig.json \
  apps/fixtures/tools/generate-rn-store-compatibility.ts
```

`rn-store-compatibility.json` (196 cases) is the **single** generated contract for
both platforms. The generator executes all eight actual RN Zustand stores with
`persist`/`createJSONStorage`, the real transaction v0→v1 migration, and profile
merge/fallback. Only MMKV, logging and unused formatting are mocked; the clock is
fixed. It preserves the complete `input: {key, raw}` string. Native tests never
parse/stringify the envelope before passing it to the codec, so `-0`, `0e5`,
`1e-400` and `0.0` retain their spelling. Native version/type/envelope probes are
labelled separately from actual RN writes.

Every store accepts only absent version or exact numeric `0`;
`transaction-storage` also accepts exact `1`. Other tokens block that store and
its writes, keeping original bytes. Reads apply transaction migration in memory;
a save writes v1. Shared cases check unknown root/state numeric tokens (wide
integers, underflow and negative zero) survive writes, nullability, and RN's
USD/bip177 fallback for future or null preferences without repairing the stored
values. Recurring fixtures go through the existing recurring codecs, whose
financial-policy tests remain in `recurring-storage.json` and
`recurring-rn-range.json`. Esplora remains inactive until a native consumer lands.

`mmkv-launch-state.json` contains eight seed+DB settings cases under the product
policy dated 2026-10-20: missing, empty, cleared and damaged MMKV, invalid wallet
bytes, missing initialization flag, initialized wallet and explicit false. Both
native importer suites assert the same Home/onboarding decisions and notice
flags. Missing/unreadable settings use Home; only explicit false resumes
onboarding. The seed-safety fixture remains unchanged.

All 196 `rn-store-compatibility.json` cases now include `expected.imported`:
valid non-recurring keys import byte-for-byte; invalid keys drop independently;
recurring never imports, even when its codec accepts the envelope. Codec/write
preservation checks still cover native-created schedules. Snapshot-only imports
are checked with real JNI/MMKV on Android and real MMKV on iOS; native writes
never touch the RN pair.

Damaged native settings use defaults and the settings notice without reimporting
RN. Notices persist through background import and are consumed once on visible
Home. See both UpgradeTests READMEs for native file paths, atomic-write ordering,
empty recurring server sync, deletion and cleanup behavior. Earlier fixture
notes about blocking malformed RN stores apply only to direct codec operations,
not to the best-effort importer.

`backup-crypto.json` and `backup-crypto-unicode.json` contain Node-generated
AES-GCM envelope vectors (including empty/binary plaintext and original UTF-8
password bytes). `backup-archives.json` contains complete independent Node +
Info-ZIP v1/v2 files; its database bytes are synthetic, not usable SDK databases.

`native-backups.json` contains the **unaltered encrypted exports** of each native
`BackupService`, generated on 2026-10-20 with bark-bolt-ffi 0.5.0. Both use the
public BIP39 `abandon` × 11 + `about` vector and fresh offline regtest databases:
fingerprint `53bdf6b1`, empty movements, zero in every balance row. No real funds,
private credentials or server backup objects are involved. Fresh salts/nonces and
SQLite timestamps make regeneration intentionally non-byte-deterministic; the
committed inputs make consumption repeatable. Do not replace these files with
Node-generated envelopes: that would lose the direct native writer evidence.

Generation is opt-in: iOS `CrossPlatformBackupTests.generateIOSServiceExport()`
with `TEST_RUNNER_NOAH_GENERATE_CROSS_BACKUP=1` writes
`tmp/native-backup-ios.json`; Android
`CrossPlatformBackupTest#generateAndroidServiceExport` with instrumentation
`-e noahGenerateCrossBackup 1` writes `cache/native-backup-android.json`. Run only
on disposable devices you created, copy these two JSON cases into the shared
`cases` array, and rerun both consumers. Each generator uses a separate wallet
directory and deletes its plaintext wallet/scratch afterward. See both native
READMEs for direct-restore and newer-backup evidence.

The decrypt tool fixture/security checks run from the repo root with either
`bun test scripts/decrypt_backup.test.js` or
`node --test scripts/decrypt_backup.test.js` (Node 22.2+). They check both native
exports as well as the independent crypto/archive vectors.
