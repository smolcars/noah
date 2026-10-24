# Noah iOS (native)

SwiftUI app that replaces the React Native client in `client/` as an in-place update. Plan and checklist: [docs/native-rewrite](../../docs/native-rewrite/PLAN.md).

## Requirements

- Xcode 27+, iOS 18 deployment target
- [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`). `Noah.xcodeproj` is generated from `project.yml` and not committed.

## Build

```sh
just ios-native-generate          # writes Noah.xcodeproj
just ios-native-build Regtest     # Regtest | Signet | Mainnet
just ios-native-test Regtest      # unit tests on a simulator
```

Schemes `Noah`, `Noah-Signet` and `Noah-Regtest` select the `*-Mainnet`, `*-Signet` and `*-Regtest` configurations. Variant values live in `Config/<Variant>.xcconfig`.

## Identity that must not change

These match the React Native app so the update keeps Keychain items (seed), app-group data and widgets users already placed:

| | Mainnet | Signet | Regtest |
|---|---|---|---|
| Bundle ID | `com.noahwallet.mainnet` | `com.noahwallet.signet` | `com.noahwallet.regtest` |
| App group | `group.com.noahwallet.mainnet` | `group.com.noahwallet.signet` | `group.com.noahwallet.regtest` |
| Widget bundle ID | `com.noahwallet.mainnet.NoahWidgetMainnet` | `com.noahwallet.signet.NoahWidgetSignet` | `com.noahwallet.regtest.NoahWidgetRegtest` |
| Widget kind | `NoahWidgetMainnet` | `NoahWidgetSignet` | `NoahWidgetRegtest` |

Team `AT26Z24V2Q`. The app entitlements list all three app groups, as in the RN app. `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` (0.2.0 / 100) must stay above the last RN release.

## Wallet SDK

[bark-bolt-ffi](https://github.com/smolcars/bark-bolt-ffi) via Swift Package Manager, pinned with `exactVersion` in `project.yml`.

## Live server test

With the regtest stack up, `TEST_RUNNER_NOAH_SERVER_IT=1 xcodebuild … -only-testing:NoahTests/ServerIntegrationTests test` logs in with a throwaway mnemonic (k1 signed through bark-bolt-ffi), registers, calls a gated endpoint and checks the one-retry path after a rejected token.

## Live backup test

Run a Noah server with S3 configured (the dev `.env`) on another port, e.g. `PORT=3100 PRIVATE_PORT=3199 cargo run -p server` with the `.env` sourced, then `TEST_RUNNER_NOAH_BACKUP_IT=1 TEST_RUNNER_NOAH_REGTEST_SERVER_URL=http://127.0.0.1:3100 xcodebuild … -only-testing:NoahTests/BackupIntegrationTests test`. It uploads a throwaway wallet's backup to the regtest bucket and restores it.

## Simulators

Don't install native builds onto a simulator that holds an RN Noah install you care about: the bundle IDs are the same, so the native build replaces it (that is the upgrade path, use it deliberately).

## Upgrade test (React Native → native)

Needs the regtest stack (`just setup-everything`), Maestro, and a Release simulator build of the RN app (`client/`, scheme `Noah-Regtest`):

```sh
apps/ios/UpgradeTests/run.sh <dedicated-simulator-udid> /path/to/Noah-Regtest.app
```

`FUNDED=1` also receives 100k sats from the regtest bark CLI and sends 5k through the RN Send flow, then requires the native side to report the RN database's fingerprint, movement count and spendable balance.

It wipes that simulator's Noah regtest app, creates a wallet in the RN app with the RN Maestro onboarding flows, installs the native build over it, and runs `UpgradeCompatibilityTests` inside the upgraded app's sandbox (seed, MMKV, wallet DB, opening the DB with bark-bolt-ffi).

## Activity (2026-10-23)

The Activity tab follows [`docs/native-rewrite/design/activity.md`](../../docs/native-rewrite/design/activity.md).

- **Code:** `Features/History`:
  - `TransactionsView`: the list and the wide panes;
  - `TransactionDetailView`: the detail, its coin view and the block row;
  - `ActivityRules` / `ActivityDetailRules`: pure list and detail rules;
  - `ActivityExport`: the export sheet and the share presenter.
- **Shared with Home:** `HistoryMapping`'s Data changes and `HomeActivityRow`. Home's "new" set (`HomeActivityTracker`) now lives in `WalletTabs`, so both tabs read the same one.
- **Wide windows** (the iPhone Duo open, iPad, landscape) draw each pane's own bar. The system bar would move its buttons to the Duo's side column, across the fold. So search there is a field in the leading pane rather than `.searchable`.
- **Lazy list caveat:** don't put `onScrollVisibilityChange` on rows inside the list's `LazyVStack`. It left rows that moved from Pending to a day drawn with their old record. The new dot's 1.4 s runs as a row `.task` instead.
- **Fixtures:** `NoahTests/ActivityShotTests` (opt-in, `TEST_RUNNER_NOAH_ACT_SHOTS=1`). Write a state to the app's `Documents/act-shot`, as with the Send/Receive shots. States: `everyday`, `busy`, `empty`, `loading`, `error`, `suspended`, `filter`, `filter-empty`, `search`, `csv`, `confirm`, `detail-<id>[-details]`.
- **Tests:**
  - `ActivityMappingTests`: Data;
  - `ActivityListTests`: grouping, search, filter, CSV, hidden amounts, the shared dot;
  - `ActivityDetailTests`: status, fees, txid evidence, Share receipt;
  - `ActivityRecurringJoinTests`: the recurring journal side of the join.
- **Live on `noah-bark08`:**
  - an on-chain deposit and a Max on-chain send, each from "Waiting for block N" through its confirmation;
  - a Lightning receive (fee ₿110), a Lightning send titled by its invoice description, and a Lightning send that failed with LND disconnected from CLN, refunded as "Refunded · back in your wallet";
  - a spent coin loaded by ID;
  - a CSV of 4 payments, the refunded row excluded.

  Regtest's offboards need one confirmation, so "· 1 of 2" was covered by unit tests only.

## Settings (2026-10-24)

Settings follows [`docs/native-rewrite/design/settings.md`](../../docs/native-rewrite/design/settings.md).

- **Navigation:** pages are `HomeView.Route.page(SettingsPage)` entries in Home's stack, after `.settings`. Deep links (the backup and suspended nudges, recurring notifications) use `SettingsPath.deepLink`, so Settings is beneath them. Pages move through the `\.settings` navigator; a slow save uses `leave(page)`, which goes back only if that page is still on top.
- **Wide windows:** each pushed `SettingsHost` draws the list in the leading pane and its page in the trailing pane, with their own bars. Pushes there skip the animation and the page fades in. The page keeps its place in the view tree across a pose change, so its scroll, typed text and revealed phrase survive. Home measures the window on its stack, not its root, because the root stops updating while Settings is pushed over it. Sheets go through `settingsSheet`, which uses a pane sheet in the trailing pane on wide windows.
- **Code:** `Features/Settings`:
  - `SettingsNavigation` and `SettingsComponents`: the host, chrome, rows, sheets and fields;
  - `SettingsView`: the list, its live values (`SettingsSummary`) and Display's sheets;
  - `SettingsRules`: debug unlock, backup status, the VTXO groups, the delete checklist and the suspend switch;
  - `RecoveryPhrasePage`: `PhraseGate` reads the words only after authentication;
  - `DeleteWalletSheet`: `WalletDeletion` keeps a running delete locked across pose changes.

  VTXOs and On-chain live in `Features/Onchain`, and Recurring in `Features/Recurring`. The emergency exit screens are unchanged.
- **Fixtures:** `NoahTests/SettingsShotTests` (opt-in, `TEST_RUNNER_NOAH_SET_SHOTS=1`). Write a state to the app's `Documents/set-shot`: `root`, `profile`, `lightning-address`, `email`, `backup`, `recovery-gated`, `recovery-shown`, `vtxos`, `vtxo`, `onchain`, `recurring`, `schedule`, `editor`, `notifications`, `advanced`, `ark-server`, `esplora`, `logs`, `debug`, `feedback`, `about`, `delete`, `unit`, `currency` or `language`. The phrase is the prototype's sample phrase. Light and dark follow the simulator's appearance; the Duo open shows the panes.
- **Tests:** `SettingsNavigationTests`, `SettingsDebugSuspendTests`, `SettingsSafetyTests`, `RecoveryPhraseGateTests`, `BackupStatusTests` and `VtxoListTests`.
- **Live on `noah-bark08`** (blocks 890–894 mined for these checks):
  - Move to Ark: 150,000 sats from On-chain (Ark fee 330, network fee 155). Send's block row showed "Waiting for block 891", and the VTXO became spendable after its third confirmation.
  - Renew from Settings: that VTXO's Renew review (fee 749) joined a delegated round, then showed as "Renewed" in Activity once the round confirmed.
  - Recurring: a monthly 1,000-sat payment to another wallet's Ark address, first due on save, paid at once ("1 payment · Sent"). Pause, Resume and Cancel also worked. The approval path wasn't exercised: it needs a Lightning fee above the limit.
  - Backup now: the server answered 500 for the upload (no S3 credentials on this stack) and the page showed the failure. Listing backups worked.
  - Export file: the share sheet offered the encrypted `.noahbackup` file (57 KB).
  - Delete wallet: a throwaway wallet ended back in onboarding.

## Direct native backup compatibility (2026-10-20)

Base `5a19f992`. `NoahTests/CrossPlatformBackupTests.swift` exports through the
real Swift `BackupService` and restores the committed Android `BackupService`
export through `WalletRestorer`: file download, envelope/archive checks, SDK
snapshot validation, transactional installation, secure seed save and production
SDK reopening. Only the server metadata transport is replaced. The tests use
separate datadirs and unique test Keychain items; no app wallet is replaced.

Both committed native exports in `apps/fixtures/native-backups.json` restore
with fingerprint **53bdf6b1**, **0 movements** (full history empty), and **0 sats
in all 14 balance rows**. This proves direct native file compatibility for a
public BIP39 vector and an unfunded regtest wallet. It does not repeat the earlier
funded/S3 acceptance test. Generation uses the SDK's offline factory inside a
session; consumption uses the production opener. Ark connectivity is unnecessary
for this empty-wallet comparison (no daemon or sync is started).

V2 includes only `manifest.json` and a self-contained `db.sqlite`; no mnemonic,
Keychain/auth token, MMKV settings, recurring schedules, logs, widgets, WAL/SHM or
attachments. Manifest fields on both platforms are `formatVersion` (2),
`createdAt` (UTC ISO milliseconds), `snapshotSha256`, `network`,
`walletFingerprint`, required nullable `serverPubkey` and `mailboxPubkey`, and
`barkSchemaVersion` (currently 42). The restorer checks snapshot hash, network,
fingerprint, schema and both optional identities against the SDK. V1 remains a
base64 envelope over the variant's legacy directory subtree, including sidecars.

There is **no app/build version field**. A newer app writing the same supported
format/schema remains compatible. The four newer-version regression cases use
server-listed format 3, envelope 3, manifest 3 and a real SQLite migrations table
at 9999 (with matching manifest/hash). All refuse before installation and retain
the previous database bytes and seed, with no pending journal or open wallet.
Existing retained-backup fallback remains: a rejected candidate may be followed
by an older compatible server-listed backup.

Restore now classifies newer envelope, manifest and SDK schema versions separately and asks the user to update Noah; wrong-phrase and damaged-backup copy is unchanged. `newerBackupsExplainUpdateRequired(_:)` runs normally alongside the pre-install safety tests (no opt-in flag).

Verification: native export generation passed once; focused restore + four newer-version safety cases passed. The opt-in copy reproduction failed all three cases as expected. Final full NoahTests ran once after the last iOS code change: **694 total / 669 passed / 25 opt-in skipped / 0 failed**, **144 suites** (1,393 expanded passes in xcresult). XcodeGen regenerated the project; regtest, arm64, ONLY_ACTIVE_ARCH=YES, code signing enabled, DerivedData under `/private/tmp`. The owned iPhone 17 / iOS 27.0 simulator **Noah Backup Cross 5612** (`B8D12D73-AFA2-47D2-958F-8369D59D85AF`) was identity-checked, shut down and deleted. Owned DerivedData, result bundles and temporary exports/logs were removed. No protected simulator/device or Docker lifecycle was controlled.

### Funded native compatibility (2026-10-20)

Base `0b3b545d`: real Swift `BackupService` → Android `WalletRestorer` and
Kotlin `BackupService` → iOS `WalletRestorer` **passed** with separate funded
wallets (`ce392bd0` and `0bbe2205`). Each retained **37,724 Ark sats**, **10,000
confirmed onchain sats**, movement IDs **1/2** (31,000/7,000 sats; fees 162/114),
and its onchain transaction before and after sync. Each restored wallet then
sent **1,000 Ark sats**, fee **0**, producing successful movement **3** and a
verified Bark receive; remaining balances were **36,724 Ark / 10,000 onchain**.
All 14 balance rows and projected history records matched. Real secure seed
storage, archive/SDK validation, filesystem restore, reopen and sends ran;
only backup listing/download transport was a local fixture.

Rerun from the repository root: `nix develop --command python3 apps/BackupTests/run.py`.
The runner owns and removes both devices (Android port 5618), DerivedData and
results, and never operates container lifecycle. See the [runbook](../BackupTests/README.md)
and [movement IDs, transactions, results and limits](../BackupTests/RESULTS.md).
No backup/restore defect was found; production code is unchanged.
