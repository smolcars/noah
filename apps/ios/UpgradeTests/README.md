# iOS in-place upgrade safety

## Settings upgrade policy — 2026-10-20

This supersedes the historical MMKV recovery notes below. Seed + DB opens Home;
only explicit readable `isInitialized=false` resumes onboarding. Settings are
best-effort, imported once from an integrity-verified immutable snapshot through
scratch MMKV. All native writes use the durable JSON store; live RN files stay
unchanged. Damaged native files use defaults and the settings notice, without
reimporting RN. Background opens never write seeds and require Home admission.

RN recurring schedules never import. Home shows the one-time recurring notice;
the existing server-sync path sends `{"schedules":[]}` after admission and retains
pending state on failure. Upgrade assertions now inspect the native store and
expect empty recurring state plus the notice. Native-created schedules retain
the existing ledger, authority and journal. Deletion removes RN files/snapshot
and leaves an empty native import marker through re-onboarding.

See [the complete storage policy](../../android/UpgradeTests/STORES.md#settings-upgrade-policy--2026-10-20)
for file format, sync ordering, notices, server verification and corruption rules.

Committed restore recovery also repairs partial automation bootstrap only as a
durable STOPPED authority, with state persisted before its initialized marker.
Ordinary recurring admission remains fail closed. Explicit Resume retries pending
restore settings first and surfaces the storage failure through Settings. See
[restore recovery regression evidence](../RestoreTests/README.md#restore-recovery-review-round-3).


Base: `feat/native-rewrite` at `c6283d16`. Run from the repository root in the
Nix development shell, with XcodeGen on PATH:

```sh
FUNDED=1 UPGRADE_DERIVED_DATA=/private/tmp/noah-upgrade-owned-dd \
  apps/ios/UpgradeTests/run.sh <owned-simulator-udid> <RN-regtest.app>
```

The runner wipes **only the supplied disposable simulator's regtest app and
keychain before RN setup**. It refuses the five protected simulator names.
There is no uninstall between RN and native. Its default DerivedData,
snapshots, Maestro artifacts and baseline MMKV copy are removed on exit.
When overriding DerivedData, remove that owned directory yourself after testing.
The RN build is never removed. Do not use the shared stack's lifecycle recipes.
For a test-only retry after RN setup has completed, `RN_PREPARED=1 FUNDED=1`
skips destructive setup/funding and verifies that the installed RN build number
and JavaScript-bundle SHA-256 match the supplied build before taking a new
baseline. Do not use this mode after native has been installed.

`FUNDED=1` uses RN's onboarding/send subflows, receives 100,000 Ark sats and
sends 5,000 back, creates a recurring rule through RN's editor with its first
payment tomorrow, changes display unit/currency/name/auto-board/privacy/mailbox/debug/language through
RN UI, then receives **10,000 onchain sats** from Bitcoin Core and mines **one**
confirming block. RN must show the onchain amount and Confirmed section before
native installation. The native store must contain no RN recurring schedules, record the one-time
notice, and the recurring screen must show its empty state. Other settings are
compared against a private import of RN's actual MMKV bytes. The native wallet must retain the fingerprint, every movement's
ID/status/kind/amount/fee, Ark balance and confirmed/total onchain balance.
Settings comparisons additionally cover unchanged biometric, suspension and
backup flags. Debug and mailbox flags and the Swedish locale must be non-default.

Bark needs at least 100,000 spendable sats. If necessary, obtain a fresh address
with `just bark onchain address`, fund it using `just bcli sendtoaddress` (BTC
units), `just generate 1`, `just bark board "300000 sats"`, and `just generate 1`
to confirm the board. LND need not run. Use explicit block counts. The Noah
server is the existing Docker service at `127.0.0.1:3000`.

The runner's test expectations contain only public wallet identity and counters;
it never exports the phrase, keys, signatures, auth JWT or Expo token. It copies
`noah-upgrade-result.json` to `UPGRADE_EVIDENCE` (default
`/private/tmp/noah-upgrade-evidence.json`) and captures the native recurring
screen at `UPGRADE_SCREENSHOT` (default `/private/tmp/noah-upgrade.png`). Avoid
capturing the onboarding recovery-phrase screen. Delete transient test artifacts
after recording the non-secret evidence here.

## Seed and conflict gates

`StoredSeed` accepts 12/24-word BIP39 English phrases with valid word list and
checksum, normalizing NFKD/case/Unicode White_Space for every wallet/auth SDK input. RN saves
the raw phrase (`crypto.ts:setMnemonic`), trims it at its restore UI, and defers
wallet-load validation to Nitro/Bark. The native app retains the stored bytes
for backup decryption and never repairs or writes them during validation.
An empty, invalid-UTF8 or corrupt seed blocks instead of becoming onboarding.
RN treats an empty password as missing and propagates Nitro/Bark load failures;
its reinstall cleanup can delete a seed when data is absent. Native deliberately
keeps these states recoverable instead of copying that unsafe cleanup behavior.

RN's `storeNativeMnemonic` is explicitly a no-op on iOS. RN and native read
`com.noah.mnemonic.<variant>` / `noah`; no second iOS native seed service exists
in this base. Every accessible candidate for that service is enumerated across
accounts and synchronization attributes. Multiple candidates (even agreeing
ones), a foreign account, or simultaneous `db.sqlite` and legacy `bark.sqlite`
block automatic selection. The pinned SDK's `OnchainWallet.openExisting`
checks the seed fingerprint read-only before its migrating/create connections;
the native app maps its mismatch to a blocking conflict state. The recovery
screen has fixed, selectable support guidance and no seed display, delete,
create or restore action. Tests assert unchanged database bytes and filenames
on mismatch and no writes on invalid-seed create/restore.

## RN persisted-store inventory

All eight stores use the unencrypted `noah-wallet-storage` MMKV instance under
`Documents/mmkv`, with a JSON `{"state":{...},"version":N}` envelope. Omitted
versions mean v0. Fields below are the persisted fields; actions are omitted by
JSON serialization. Only `transaction-storage` declares a migration.

- `wallet-storage` v0 (`walletStore.ts`): partializes `isInitialized`,
  `isWalletLoaded`, `staticVtxoPubkey`, `isBiometricsEnabled`,
  `isDebugModeEnabled`, `isWalletSuspended`, `hasSeenBatteryOptimizationPrompt`.
  Native consumers: launch/onboarding, locking, settings and background gates.
- `profile-storage` v0 (`profileStore.ts`): `displayName`, `preferredCurrency`,
  `bitcoinAmountUnit`. RN merge defaults invalid currency/unit to USD/bip177.
  Native consumers: profile, currency/unit settings, balance/payment formatting.
- `privacy-storage` v0 (`privacyStore.ts`): `isHomeBalanceHidden`.
  Native consumer: Home balance visibility.
- `transaction-storage` v1 (`transactionStore.ts`): partializes
  `isAutoBoardingEnabled`, `hasAttemptedAutoBoarding`; v0→v1 preserves other
  fields and resets `hasAttemptedAutoBoarding=false`. Native consumers:
  auto-board setting and prompt planning. Migration is read-only until a save.
- `server-storage` v0 (`serverStore.ts`): `isRegisteredWithServer`,
  `lightningAddress`, `nostrPubkey`, `isBackupEnabled`, `emailAddress`,
  `isEmailVerified`, `isEmailPromptDismissed`, `mailboxAuthorizationExpiry`,
  `isMailboxAuthorizationEnabled`, `lastReportedDeviceFingerprint`,
  `lastDeviceReportAt`. Native consumers: registration/identity, email,
  mailbox and backup settings/services.
- `backup-storage` v0 (`backupStore.ts`): `backupPending`, `lastBackupAt`,
  `lastBackupAttemptAt`, `lastBackupStatus`, `lastBackupError`, `lastBackupId`,
  `lastUploadedSnapshotSha256`. Native consumers: backup coordinator/settings.
- `recurring-payment-storage` v0 (`recurringPaymentStore.ts`): `schedules`
  (ID→complete `RecurringPayment`), `serverSyncPending`. Native consumers:
  recurring list/editor/executor. Its existing raw-token codec retains unknown
  envelope/state/row fields, blocks future versions/corrupt envelopes and keeps
  unsupported policies visible with execution disabled. Full RN policy and
  numeric-range fixtures remain in use.
- `esplora-storage` v0 (`esploraStore.ts`): partializes `endpointOverride`.
  RN merge accepts strings and maps other values to null. **No native endpoint
  editor/consumer exists yet**. The iOS decoder is available and fixture-tested,
  but does not activate the override. Add validation/reload/rollback with that
  screen before consuming it.

The old TODO's “remaining 7” count was stale: seven stores already had partial
native readers. The shared codec now validates their envelopes/types, refuses
future versions and preserves unknown state/root fields (including exact wide
numeric tokens) on saves. Malformed consumed settings block startup and headless
wallet work before defaults can overwrite them. The existing recurring codec
continues its stricter financial-policy/range handling. See
`../NoahTests/RNStoreTests.swift` and `../../fixtures/tools/generate-rn-store-compatibility.ts`.

## Expo physical-device gate

**A simulator run does not verify Expo registration continuity.** RN's
`pushNotifications.ts` returns `device_not_supported` before asking Expo for an
installation ID/token; native registration likewise returns
`deviceNotSupported`. Fabricating either value would not be migration evidence.
The opt-in test `registeredExpoIdentityAndTokenAreUnchanged()` is visibly skipped
unless both `NOAH_UPGRADE_EXPECT_EXPO_ID` and `NOAH_UPGRADE_EXPECT_EXPO_TOKEN`
are supplied privately to a physical-device test run (Xcode test environment
uses the `TEST_RUNNER_` prefix). It inspects the existing UUID without creating
one, then verifies native's actual registered token against the RN token using
boolean assertions that do not print either value. Capture those values on an
RN-registered physical iPhone before upgrading. Keep the installed app and
keychain, install native with the same signing identity, run that test, and
verify a delivered push separately. This remains a release gate.

**Physical-device run, passed (2026-10-20).** Setup: an iPhone 17 on iOS 27.2 (UDID
`00008150-001444D90A28C01C`), with only `com.noahwallet.regtest` touched.
- RN 0.1.14 regtest was built for the device (Release, `EXPO_PUBLIC_REGTEST_URL` /
  `EXPO_PUBLIC_REGTEST_SERVER_URL` pointing at the Mac's LAN IP) and installed.
  Onboarding was completed by hand, with notifications and local network allowed.
- RN registered an Expo push token with the regtest server for wallet `0357e728`;
  its MD5 prefix is `27614e59222c`.
- Native 0.2.0 (100) was then installed over RN with `devicectl` (no uninstall), built
  with `-configuration Release-Regtest NOAH_REGTEST_HOST=<lan-ip>
  NOAH_REGTEST_SERVER_URL=http://<lan-ip>:3000`.
- Native opened the same wallet with RN's keychain seed: server login at 09:51:38 UTC
  for `0357e728`, and no new user.
- `registeredExpoIdentityAndTokenAreUnchanged()` then ran on the device with
  `TEST_RUNNER_NOAH_UPGRADE_CHECK=1` and the RN token supplied privately from the
  server DB. RN's installation ID can't be read outside its keychain item, so the test
  now accepts the token alone: Expo derives the token from that ID, and an unchanged
  token proves reuse. Native's actual registration returned RN's exact token, and an
  installation ID was present. **1 test passed.** The token was never printed, and the
  result bundle was deleted.
- **Finding (root cause confirmed on the device with the UI driver):** before onboarding, the
  user had deleted the stale RN wallet in RN Settings. RN's `deleteWallet`
  (client/src/lib/walletApi.ts) unlinks everything in Documents, including the
  `mmkv` folder, while RN's MMKV instance stays open. Every later RN write (the
  new onboarding, `isInitialized`, settings) goes to the deleted file and never
  reaches disk. On first launch native found no store, created a fresh one and
  resumed onboarding ("Your Recovery Phrase").
  - Reproduced on 2026-10-20: RN onboarding → Delete Wallet → onboarding again → install
    native → native resumed onboarding, with `Documents/mmkv` created at native's launch.
  - Four control rounds that installed native while RN wrote MMKV continuously, with
    no prior delete, kept every RN store and landed on Home, so a kill mid-write is
    not the cause.
  - RN itself also loses those settings on its next restart.

`ExpoInstallationIDTests` verifies real Expo-shaped keychain-item and legacy
UserDefaults reuse with isolated fake bundle IDs; those tests prove the storage
contract, not live registration or delivery.

## Widgets

`check-widgets.py` compares all three RN source kinds, extension bundle IDs and
app-group entitlements with native configurations. With RN/native `.app` paths,
it additionally checks both built regtest widget extension IDs, native's kind,
app group and WidgetKit extension point, the containing app ID, and increasing
build number. These identifiers must remain:

- Mainnet: `NoahWidgetMainnet`, `com.noahwallet.mainnet.NoahWidgetMainnet`.
- Signet: `NoahWidgetSignet`, `com.noahwallet.signet.NoahWidgetSignet`.
- Regtest: `NoahWidgetRegtest`, `com.noahwallet.regtest.NoahWidgetRegtest`.

`rn-place-widget.yml` places an RN small widget when no placed instance exists
before native installation. Retries retain existing placements.
`check-widget-placement.py` records SpringBoard's actual kind, containing app ID,
extension ID, display ID and unique instance ID, then requires the identical
record after native UI verification. `widget-survives.yml` returns to Home for
a screenshot without adding another widget. Identifier checks alone would not
prove SpringBoard retained a placement.

## Recorded run

Run on 2026-10-20 in this worktree, using the owned **Noah Migration Sol
278d9bec**, UDID `68B4ED06-F3DD-40DD-BDD2-8A2A1EF0567C` (iPhone 17 Pro,
iOS 27.0), Xcode 27.1, arm64 and signing enabled. No protected simulator or
stack lifecycle command was used. The cached release RN app at
`/private/tmp/noah-rn-build/Build/Products/Release-iphonesimulator/Noah-Regtest.app`
was reused: its build request names `feat-native-rewrite/client/ios`, client
sources there were clean at the same base, the latest client change was
`62b8b5e5` at 2026-10-19 17:42 EDT, and its JS bundle/executable were built at
17:46/17:47. RN build 38 was replaced by native build 100 with the same app ID.

Final public checkpoint (also exported as
`/private/tmp/noah-ios-migration-278d-evidence.json`):

- Fingerprint **`28e28d1b`**, matching RN's original database.
- **2 movements**, with identical IDs, status, kind, intended/effective amounts
  and fees. **95,000 spendable/offchain sats**, **10,000 confirmed/total onchain
  sats**, **105,000 total**, zero pending. Core funded RN with 0.0001 BTC and one
  block; RN's Confirmed row showed 10,000 sats before native installation.
- **1 active recurring rule**, “Upgrade safety rule”, 1,000 sats monthly to the
  Bark wallet address, first payment on 2026-10-21. Every decoded RN policy
  field matched after native startup; zero paid occurrences, empty runs,
  no in-flight marker. The native recurring screen displayed the rule and
  “Monthly · 0 paid”. Post-UI SQL comparison confirmed no extra payment/movement.
- RN→native: unit **sats**, currency **EUR**, display name **Upgrade safety**,
  hidden Home balance **true**, auto-board **false**, mailbox notifications
  **false**, debug mode **true**, language **sv**. Biometric, suspension and
  backup flags were also compared with RN (no physical biometric enablement
  or backup service delivery was asserted).
- The two actual placed widget instance IDs
  **`36DF0BB8-3530-42FD-A764-B4BBA9CA62B6`** and
  **`7D2327AB-1B92-476A-A0B8-2BAE0A9ECAB1`**, their display IDs, kind,
  containing app/extension IDs and grid size were identical before/after.
  `check-widgets.py` also passed for all three source configurations and the
  built RN/native regtest products.
- **Expo UUID/token continuity: not verified**, with one visible test skip.
  No synthetic registration or installation UUID was presented as live evidence.

Evidence images are `/private/tmp/noah-ios-migration-278d-widget-recurring.png`
and `/private/tmp/noah-ios-migration-278d-widget.png`. Neither contains a phrase,
private key, signature or token. The native data-container UUID changed during
installation; the runner now refreshes its host-side path before exporting and
cleaning test files. That final exporter was separately rerun against the
updated sandbox without repeating funding.

RN setup and checks were resumed at checkpoints while fixing test selectors:
center switch/Confirmed rows above the floating tab bar, match switch 1/0 values,
and use the visible WidgetKit gallery Add Widget button where iOS 27 omits it
from Maestro's hierarchy. `RN_PREPARED=1` validated the RN build/JS hash before
native installation. An iOS 27 pasteboard grant was applied. For shared-machine
Maestro runs, `JAVA_TOOL_OPTIONS=-Duser.home=/private/tmp/<owned-maestro-home>`
isolates Java logs/cache; this run used a task-owned directory. Retry checks
inspected balances before sending again; successful funding was not replayed.
The stack's Bark wallet was replenished via Core and `bark board`; LND was not
started. Four explicit single-block mining calls were needed across provisioning
and the disposable funded iterations; no default 101-block mining was used.

Validation:

- Initial focused checks: **20 passed / 4 suites**.
- Final focused seed, store, creator, restore, settings and Expo storage checks:
  **47 passed / 7 suites**.
- Live upgrade: **4 passed, 1 skipped / 1 suite**; native recurring Maestro and
  both widget checks passed afterward.
- Full `NoahTests` ran **once**, without exclusions: **510 total / 487 passed /
  22 opt-in skipped / 1 failed**, 107 suites. The failure was
  `WalletLifecycleTests.queuedRecoveryCannotDeleteTheReplacementWallet()`:
  its mock mnemonic generator returned the invalid placeholder “new wallet”.
  The test now generates a valid BIP39 phrase and avoids phrase operand capture
  in diagnostics. Its exact trailing-`()` test ID was rerun: **1 passed**.
  No production failure remains from that run; the full suite was not repeated,
  honoring the requested one-run limit.
- `xcodegen generate`; `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, code signing enabled.
  Bash syntax, Python source syntax and all-variant widget checks passed.
  RN store fixture regeneration was byte-identical (SHA-256
  `02aaadd9be70b979d0029b34a6eba13c387cfa99c150839faf9c246f1666bb1a`).

Cleanup completed: confirmed the simulator's exact name/UDID and **Shutdown**
state, deleted that simulator, and verified its UDID no longer exists. Removed
`/private/tmp/noah-ios-migration-278d-dd`, its native `.xcresult` bundles, the two
owned orphan Maestro test-output directories, UDID-specific temporary runner
files, task-owned Maestro home/debug artifacts and transient scripts/logs. The
cached `/private/tmp/noah-rn-build` was preserved. Only the non-secret JSON
checkpoint and the two evidence images named above remain.

## Migration safety review round 1

Both apps consume the same 196 raw RN store cases, 36 source-level launch cases
and 37 mnemonic-normalization vectors; the exact outcome/diagnostic table and RN
normalization/backup rules are in `../../fixtures/README.md`. Generic store
versions use exact raw tokens, including rejecting numeric underflow and alternate
spellings of zero. Unknown values stay preserved; profile consumers use RN's
USD/bip177 fallback. Wallet open, login derivation and signing share `StoredSeed`;
backup passwords and Keychain values retain the original string.

Transient secure-storage unavailability retries in process on protected-data
availability, foreground, and the blocking screen's Retry action. A retry reruns
the seed read/recovery decision, serializes concurrent attempts, and never maps
unavailable storage to an absent seed. Invalid/conflicting states stay blocked.

Round 1 verification (2026-10-20): targeted parity/retry/seed suites **19 passed**;
full `NoahTests` ran once: **520 total / 498 passed / 22 opt-in skipped /
0 failed, 110 suites**. XcodeGen, arm64/active-architecture-only simulator build,
code signing enabled. Real offline SDK open/auth and fingerprint/no-write checks
passed. The owned `Noah Migration Safety f0e0adc0` simulator was confirmed shut
down, deleted and verified absent; its DerivedData and all `.xcresult`
bundles were removed. No protected/shared device or Docker was touched. The
funded RN-to-native device procedure was not repeated in this seed/storage pass.

## Historical MMKV interrupted-update safety (superseded 2026-10-20)

`SettingsStorage` installs CRC/length **recover** callbacks as part of MMKV
initialization, before any instance is opened. Warnings/errors go to `AppLog`
with fixed diagnostics: MMKV's raw messages, keys and values are never forwarded.
Before the first native open, the data/CRC pair is copied byte-for-byte into
`Documents/mmkv.native-backup`, outside MMKV. Files, manifest and directory
entries are synced before opening. The published directory is the native-only
first-open marker and is never replaced. Both staging and published backup
directories have `isExcludedFromBackup=true`; tests read the resource values.
An incomplete pair, copy failure or sync failure blocks storage access and
retries on the next app launch. No MMKV writable instance is opened on that path.

If the recorded header/current/last-confirmed size indicates content but no RN
keys survive open, the instance is closed, the original pair is restored once,
and MMKV is reopened with recovery enabled. A durable pending marker resumes
an interrupted two-file restore before either file is mapped. Empty recovery
again blocks with `STORAGE_UNSUPPORTED`, preserving the backup.

After the first successful open and consumed-store validation (including the
one permitted restore), `Documents/mmkv.native-retired` is atomically written
and fsynced, followed by its parent directory, before admitting native mutations.
This external record lists admitted RN key names, adding newly written keys
before mutation. Subsequent CRC/length recovery or loss of an admitted RN key
blocks; the original snapshot can never override later native state. The
immutable snapshot remains for explicit/support recovery only. A bad retirement
record or failed sync blocks writes and is retried on next launch. Wallet
deletion durably retires with no required keys, clears/syncs settings, and removes
both published and staging snapshots after local wallet/seed deletion. The
retirement record survives deletion and re-onboarding; a new snapshot is never
created from the deleted wallet.

A DB and seed with **`wallet-storage` already missing on first native open
resume onboarding** in the foreground (user decision, 2026-10-20). RN's Delete Wallet unlinks the MMKV
folder while it's open, so a wallet re-created in the same RN session never
persists its stores. Blocking would lock those users out of a valid wallet.
Headless opens still refuse until onboarding finishes (`isInitialized` must be
true). A present, compatible store with `isInitialized=false` resumes onboarding
as before. A present but undecodable value (including invalid UTF-8) blocks through
`contains(key:)` plus decoding checks. Neither condition is silently repaired
by writing defaults. Recurring execution requires healthy
storage; its independent journal retains consumed-payment evidence when MMKV
recovers an older policy.

The runner now defaults to `INSTALL_WHILE_RUNNING=1`: it takes a stopped RN
baseline, builds native, relaunches RN, and repeatedly selects EUR through RN
controls. It waits for an observed MMKV byte change, then explicitly installs
native while that writer is still running, before invoking the test runner.
`INSTALL_WHILE_RUNNING=0` retains the stopped-app comparison. Run **both modes**
for upgrade acceptance. The running mode intentionally permits RN writes between
the earlier snapshot and replacement; all existing funded preference/rule checks
remain enabled. English and the funded Swedish locale are supported by the
writer flow. Do not print writer logs or upload Maestro artifacts.

The shared binary fixture and deterministic corruption procedure are documented
in `../../fixtures/rn-mmkv/README.md`. CRC, file-length, truncated-write,
unrecoverable-data, interrupted-restore, immutable-backup and deletion tests run
without network or a wallet daemon. The discard demonstration is opt-in and
runs alone because it replaces the process-global handler.

Verification for this change (base `37ab2125`, branch `t3/mmkv-upgrade-recovery`):

- Full `NoahTests` ran **once: 562 total / 539 passed / 23 opt-in skipped /
  zero failures**, 117 suites. XcodeGen, arm64 only, active architecture only,
  code signing enabled; DerivedData under `/private/tmp`. The 23 skips include
  the isolated discard demonstration, which passed separately (**1 passed**).
- All corruption/backup tests passed, including all three CRC/length/truncation
  cases. The recurring test recovered a CRC-damaged file containing an older
  policy, retained its newer independent journal, waited for the storage/launch
  gate and made **no duplicate payment**.
- Real RN 0.1.14 regtest on owned iOS 27.0 simulator `Noah MMKV Recovery t3-79e4`
  completed onboarding via the existing Maestro flows (email/name skipped,
  unfunded). The final running-install procedure required a completed RN EUR
  selection and changed MMKV bytes; RN had entered the next loop iteration when
  `simctl install` replaced it. No termination was issued between those writes
  and replacement. Native upgrade checks: **4 passed / 1 physical-Expo skip**.
  Seed, existing DB/fingerprint, initialized wallet and native SDK open survived.
  Widget bundle/kind identity checks passed. A further focused assertion against
  an isolated copy of the immutable first-open backup verified that **every RN
  store key present before native opened remains readable** (**1 passed**).
- **Natural silent discard was not reproduced** in that simulator replacement;
  the native log had zero CRC/length recovery callbacks. Deterministically
  damaging the real RN file reproduced data loss with the prior discard
  strategy, and recovery/backup/restore or blocking behavior with the fix.
- The first runner attempt accepted a launch-time write before the UI loop;
  that gap was fixed in both runners and the stricter iOS procedure repeated
  successfully. Stopped mode remains available but was not rerun in this pass.
  Funded, verified-email, real push and physical-device cases were not repeated.
  No shared/protected device or Docker container was touched.

Cleanup: confirmed the owned simulator's exact name/UDID and **Shutdown** state,
deleted it, then verified its UDID absent. Removed task-owned DerivedData,
`.xcresult` bundles, Maestro home/debug artifacts, logs, checkpoint and screenshot.
The cached RN build was preserved. The only retained binary evidence is the
non-secret RN fixture committed under `apps/fixtures/rn-mmkv`.

## Historical MMKV recovery review fixes (2026-10-20; base `172446b6`)

Permanent `MMKVRecoveryTests` probes reproduced suspension rollback, later
wallet-key loss, present-but-undecodable RN bytes and missing system-backup
exclusions on the unfixed code. All four failed before their fixes. A further
CRC probe caught MMKV's lazy loading: post-retirement recovery is checked after
validation forces the mmap read, even if every RN key survives.

The shared `../../fixtures/mmkv-launch-state.json` has nine seed+DB storage
vectors consumed by both native suites: first-open absence/unfinished state
resumes onboarding, a present unreadable value blocks, and later key loss
blocks. Additional tests cover retirement-write failure without MMKV mutation,
exact immutable snapshot bytes, interrupted restore, staging/published exclusion
resource values, snapshot removal, and deletion → re-onboarding without restore.

Verification: XcodeGen, arm64/active architecture only, code signing enabled,
two build jobs, owned DerivedData under `/private/tmp`. The full `NoahTests`
suite ran **once: 609 tests / 122 suites / 584 passed / 23 opt-in skipped /
2 failed test functions**. Both failures were test assumptions invalidated by
the new boundary: the 196-case codec harness reused stores after injecting
corrupt values, and the truncation probe expected startup to accept a partial
JSON envelope. Isolating each RN vector and asserting blocking for truncation
fixed those tests; the exact two functions then **passed** (all 196 RN vectors
and all three corruption arguments). No production change followed the full
run, and the full suite was not repeated. All new regression functions passed
in the full run and/or their focused runs, including nine shared launch vectors.

Android's old-behavior negative control failed 11 of 30 targeted tests; restored
fixes passed. Android's final command passed 2,933 JVM tests and compiled all
JNI probes. No Android device, protected simulator, physical device or Docker
was used. Funded/running-RN replacement and real push acceptance were not
repeated in this review. Durable recurring pause/cancel tombstones remain out
of scope, and no `WalletSession`, `RecurringExecutor` or `RecurringStore`
implementation was changed.

Owned simulator `Noah MMKV Safety t3-4176`
(`165E6CEA-DFFA-4980-A6E8-17B0ABC0CBA2`) was closed through T3, confirmed by
exact name/UDID in Shutdown state, deleted and verified absent. Removed its
`/private/tmp/noah-mmkv-safety-4176-dd` DerivedData (including all result bundles)
and all owned transient scripts/logs. Shared/protected devices were untouched.

## Historical MMKV migration safety, review round 2 (2026-10-20; base `4f7228c5`)

Recovery rejection now survives process restarts. Before returning **recover**
from either MMKV error callback, native atomically writes and syncs a quarantine
file outside MMKV, then syncs its parent directory. The identical UTF-8 format on
both platforms is `1\nnoah-wallet-storage\n` (version, store name, trailing newline);
it contains no setting values or secrets. iOS uses
`Documents/mmkv.native-quarantine`; Android uses
`noBackupFilesDir/mmkv.native-quarantine`. Marker write/sync failure fails closed
for that launch; an unreadable or unknown marker also blocks.

The admission state machine is:

1. Before restoration retirement, inspect a disposable copy of the immutable
   pre-open snapshot. Inventory **all eight RN keys**, including recurring.
   Any missing snapshot key, undecodable consumed envelope, or newly quarantined
   recovery closes MMKV and restores the original pair once. Revalidate against
   the snapshot inventory before retiring restoration and clearing quarantine.
   The existing restore-pending journal still resumes before mapping files.
   If restoration/validation fails, remain blocked. An already restored pair may
   finish admission after a crash; another recovery/loss cannot trigger a second
   restoration. A snapshot that itself never had wallet-storage may still resume
   foreground onboarding using the existing seed and DB.
2. After retirement, never restore the RN snapshot. Recovery with readable,
   complete admitted stores becomes **SETTINGS_RECOVERED**, a selectable support
   message explaining that settings came from an older state and listing
   biometrics, wallet suspension, balance hiding and backup settings to review.
   Missing admitted keys or undecodable envelopes remain **STORAGE_UNSUPPORTED**
   and offer no bypass. Retrying/restarting cannot clear quarantine.
3. Only the foreground launch gate's explicit **Continue** action reopens and
   validates recovered settings, syncs retirement, then removes the marker and
   syncs its directory. Failed acknowledgement retains quarantine. No seed is
   read/written to acknowledge settings. Push, background tasks, workers, widgets
   and recurring work cannot open a quarantined wallet. Widgets only render
   existing caches; they do not open MMKV or a wallet. The independent recurring
   policy ledger and revision-bound settlement remain unchanged and still gate
   recovered schedules after acknowledgement.

Recurring envelope admission uses the existing recurring codec. Undecodable
bytes, malformed envelopes and missing admitted recurring keys block both
opening and later binding/mutation. Strict-parse unsupported rows remain visible
and stopped; they do not block the wallet and are never replaced by an empty
schedule envelope. Esplora remains inventoried but inactive as before.

Shared `../../fixtures/mmkv-launch-state.json` now has **15 vectors**, adding
partial first-open missing/undecodable wallet state, a snapshot with no wallet
state, persistent quarantine, retries and explicit acknowledgement. Immutable
snapshot bytes are never mapped directly: MMKV may rewrite only the disposable
inspection copy or live files.

Permanent real-MMKV probes reproduce an appended native suspension record
recovering to an older wallet state, rejection across two opens, recurring
undecodable/admitted-key loss, and partial first-open loss. The base-code run
failed all three finding functions (six issues); a corrected admitted-recurring
fixture was also negative-controlled against the old inventory: both cases
failed. The fixed targeted run passed **25 functions / 3 suites**, including
all shared vectors, four quarantine durability tests and the headless gate
(which never calls the seed reader while quarantined).

Final verification: **643 total / 124 suites / 620 passed / 23 opt-in skipped / zero failures**. XcodeGen ran before the full NoahTests suite,
which ran once after the final code change, with arm64/active architecture only,
code signing enabled, two jobs and owned DerivedData under `/private/tmp`.
No funded/running-RN replacement or physical push delivery was repeated.

Cleanup: owned simulator **NoahMMKVRound2-639fae47** (`FFB51B88-7853-45E3-B0F9-75DD652CC1D9`) was confirmed by exact name/UDID, shut down through T3, confirmed **Shutdown**, deleted and verified absent. Its `/private/tmp/noah-mmkv-r2-639fae47-dd` DerivedData and every owned result bundle were removed. No protected simulator, Android device, physical device,
Docker, client or server implementation was changed.

## Historical MMKV migration safety, review round 3 (2026-10-20; base `32e1b6ef`)

Recovery is gated **before mapping** the live data/CRC pair, restored pair and
snapshot inspection copy. The checker reads the MMKV 2.4.3 format directly;
`../../fixtures/rn-mmkv/README.md` records the pinned source/AAR, exact 112-byte
metadata layout and real RN MMKVCore 2.4.0 fixture values. Version 3/4 uses the
metadata size rather than the stale legacy header. The last-confirmed pair is
never accepted as proof that the current pair is healthy. Missing data is a
never-created store and opens normally. Bad CRC/length, missing/short metadata,
or an unsupported version must write and fsync the external marker and its
parent directory before any MMKV mapping. Marker failure leaves both files
unchanged and blocks this launch.

MMKV's callbacks remain a secondary signal, including after cache eviction.
They return recover only after durable quarantine. MMKV 2.4.3 offers only
recover and discard; **discard also rewrites the files**. There is no callback
option that both refuses recovery and preserves bytes. A secondary callback
whose marker cannot be made durable therefore terminates the process before
returning to MMKV (Swift `fatalError`; Android process kill plus `Runtime.halt`).
The next launch reaches the ordinary pre-open gate. This deliberately favors
preserving settings over continuing a launch with unacknowledged recovery.

The foreground Continue action durably acknowledges first, then reapplies
recovered protection before publishing wallet data. Every headless path still
requires healthy storage and cannot acknowledge the marker. Retirement,
first-launch single restoration, admitted-key validation, recurring codecs and
the independent recurring policy ledger retain their existing behavior.

iOS retains the callback's store target for its full lifetime. Continue creates
a fresh `AppModel`, including server/settings state, and replaces `RootView` by
identity so its biometric policy is initialized from recovered settings. Locked
wallet content is excluded from the view hierarchy until authentication. No
AppDelegate change was necessary.

Negative control: restoring callback-only admission, clearing the callback
target at the end of initialization and keeping the pre-Continue model failed
**all 3 probes / 8 assertions**. The fixed targeted run passed **37 tests /
4 suites / zero failures or skips**, including real-MMKV CRC/length silent
rollback, lazy reload, immediate biometric lock + hidden balance, 15 shared
launch vectors, durability failures and headless refusal.

Final NoahTests ran once after the last code change and XcodeGen generation:
**656 total / 125 suites / 633 passed / 23 opt-in skipped / zero failures**.
The run used `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, automatic code signing,
`-jobs 2`, no parallel test runners, owned DerivedData under `/private/tmp`,
and only NoahTests. Android's final JVM/APK/instrumentation-compilation command
also passed (2,965 JVM tests / 95 suites).

Cleanup: task-owned simulator **NoahMMKVRound3-10769**
(`AFDA86B8-7986-4D4A-89B8-AADE9D2703C0`) was confirmed by exact identity,
shut down through T3, confirmed **Shutdown**, deleted and verified absent.
`/private/tmp/noah-mmkv-r3-10769-dd`, every owned result bundle, transient log,
negative-control backup and layout probe were deleted. All protected simulators,
physical devices and Docker remained untouched. No AppDelegate, WalletSession,
recurring implementation, push-job, background-execution, client or server
implementation was changed.

Limits: no funded/running-RN replacement, live push delivery or physical
biometric prompt was repeated. Tests exercise immediate lock/hiding policy and
actual MMKV recovery; the secondary callback's process-termination fallback is
source-verified, with injected durability failures covered before mapping.

## Native settings replacement verification — 2026-10-20

Base `b6b3538d`; branch `t3/simplify-settings-migration`. This implements the
2026-10-20 policy above, replacing the prior recovery/Continue behavior.

- Android final required Nix command ran after the final code change with
  `--max-workers=2`: **2,978 JVM tests / 103 suites / 0 failures, errors or skips**;
  regtest APK assembly and Android instrumentation Kotlin compilation passed.
- Owned Android 15/API 35 arm64 AVD **NoahSettings_fdb0**, console port **5596**:
  **13 instrumentation tests discovered: 12 passed, 1 opt-in skipped**, zero
  failures. These are 10 `MMKVRecoveryTest` snapshot/native-file tests, two
  `RecurringStorageDeviceTest` tests, and the skipped prepared RN
  `UpgradeCompatibilityTest` (no funded RN sandbox/expectations supplied).
- iOS full `NoahTests` ran once after the final code change and XcodeGen:
  **652 tests / 130 suites / 630 passed / 22 opt-in skipped / 0 failures**;
  XCResult records 923 expanded parameterized passes. Arm64/active architecture
  only, signing enabled, two build jobs, no parallel test runners, owned
  DerivedData/result bundle under `/private/tmp`.
- Targeted iOS runs verified all 196 raw RN import cases, shared launch policy,
  empty recurring sync after admission (with retry), native damage/defaults,
  interruption before/after rename for absent and existing generations,
  concurrent import, existing immutable snapshot precedence, fresh install,
  deletion/re-onboarding, notices and 2,000 native writes with unchanged RN bytes.
  Android's matching JVM/JNI coverage passed. Native-created recurring tests
  remain in the full suites; production executor, ledger and authority unchanged.
- The first Android device run exposed fixture assumptions (the real binary
  has no recurring schedules) and a test-only directory creation race. Explicit
  generated recurring records and precreated test directories fixed these;
  the complete final device run passed. The first iOS atomic-order assertion
  compared URL directory hints; comparing paths fixed it, with real fsync order
  unchanged. A Swift import typo was caught during compilation and corrected.

Cleanup: confirmed exact simulator **NoahSettings-fdb0** /
`842FE262-F486-4394-924E-A2896937075B`, shut it down, verified Shutdown, deleted
it and verified absence. Confirmed `emulator-5596` reported **NoahSettings_fdb0**,
stopped it, verified absence from adb, listed and deleted that exact AVD and
verified absence. Removed owned DerivedData, every result bundle and temporary
scripts/logs; stopped the lingering failed targeted Xcode invocation. No owned
build process remains. Shared/protected simulators, other AVDs, physical devices,
Docker, `client/`, `server/`, AppLog and AppTelemetry were untouched.

Limits: no funded RN replacement, stopped/running RN install acceptance, live
server sync, push delivery or physical biometric prompt was repeated. Server
clearing semantics were source-verified (including its existing empty-set test);
native empty-payload/admission/retry behavior was executed in tests. The updated
prepared upgrade assertions compile but remain opt-in for that acceptance run.
