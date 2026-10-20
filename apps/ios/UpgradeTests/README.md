# iOS in-place upgrade safety

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
native installation. All recurring fields are compared against a private copy
of RN's actual MMKV bytes, including timing, destination, amount, status,
occurrences and history. The native recurring screen must show the rule and
zero payments. The native wallet must retain the fingerprint, every movement's
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

## MMKV interrupted-update safety (2026-10-20)

`SettingsStorage` installs CRC/length **recover** callbacks as part of MMKV
initialization, before any instance is opened. Warnings/errors go to `AppLog`
with fixed diagnostics: MMKV's raw messages, keys and values are never forwarded.
Before the first native open, the data/CRC pair is copied byte-for-byte into
`Documents/mmkv.native-backup`, outside MMKV. Files, manifest and directory
entries are synced before opening. The published directory is the native-only
marker and is never replaced; the backup is retained even after wallet deletion.
An incomplete pair, copy failure or sync failure blocks storage access and
retries on the next app launch. No MMKV writable instance is opened on that path.

If the recorded header/current/last-confirmed size indicates content but no RN
keys survive open, the instance is closed, the original pair is restored once,
and MMKV is reopened with recovery enabled. A durable pending marker resumes
an interrupted two-file restore before either file is mapped. Empty recovery
again blocks with `STORAGE_UNSUPPORTED`, preserving the backup. Explicit wallet
deletion durably marks the backup ineligible for restoration before clearing
settings, so a later launch cannot resurrect the deleted wallet's settings.

A DB and seed with a **missing `wallet-storage` resume onboarding** in the
foreground (user decision, 2026-10-20). RN's Delete Wallet unlinks the MMKV
folder while it's open, so a wallet re-created in the same RN session never
persists its stores. Blocking would lock those users out of a valid wallet.
Headless opens still refuse until onboarding finishes (`isInitialized` must be
true). A present, compatible store with `isInitialized=false` resumes onboarding
as before. Neither condition is
silently repaired by writing defaults. Recurring execution requires healthy
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
