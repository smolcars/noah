# RN persistence inventory (c6283d16)

## Settings upgrade policy — 2026-10-20

This policy supersedes every earlier MMKV recovery/retirement/Continue rule below.
Wallet identity comes from the validated stored seed and wallet database. Seed + DB
opens Home even if RN settings are missing, empty, cleared, damaged or unreadable.
Only a readable `wallet-storage` explicitly containing `isInitialized=false` resumes
onboarding with the existing seed. `launch-seed-safety.json` still fails closed for
corrupt/conflicting seeds; settings never change those rules.

Settings import runs on the first settings access, including a background push
launch, under the platform's process-wide storage lock. It first creates the
immutable `MMKVUpgradeBackup` snapshot if absent, then copies that snapshot to
scratch. `MMKVIntegrity` must verify the scratch data/CRC pair before MMKV maps it.
Only scratch is opened; live RN files and the immutable pair are never mapped or
modified. Verification/read failure imports nothing. A bad known envelope drops
only that key. Every other RN key retains its exact original string; transaction
v0 migration and profile fallbacks remain read-time behavior. Esplora is validated
and retained, with its existing native consumer status unchanged.

`recurring-payment-storage` is never imported. A nonempty or unreadable recurring
record conservatively records the recurring notice and creates only an empty
native schedule envelope with `serverSyncPending=true`. The existing executor,
ledger, automation authority and journal continue to govern native-created rules.
After wallet admission the normal recurring server-sync path sends
`POST /v0/recurring_payments/sync` with `{"schedules":[]}`. The endpoint calls
`RecurringPaymentRepository.replace_for_pubkey`: it deletes missing IDs, so an
empty list removes every old server wake entry. Existing server test
`test_sync_recurring_payments_replaces_schedule_set` explicitly covers clearing.
Native regression tests assert no payment, an empty payload after admission,
and pending-state retention/retry on sync failure. No server code changed.

Home shows each applicable message once, without blocking or acknowledgement:

- Some settings couldn't be carried over from the previous version, so they're back to their defaults: biometric lock is off, balances are visible, and currency and display settings are reset. Please review Settings.
- Recurring payments need to be set up again after this update.

Pending notice flags live in the native file and are consumed only by visible
foreground Home. Background import retains them. If persistence is unavailable,
consumption is remembered in the process-wide fallback shared by all settings
objects for that file. The notice still appears without a successful write. A
later write persists consumption; if the process exits before any write can
succeed, a later launch may show the notice again. Missing RN files cannot prove
lost preferences and use defaults without the loss notice; an existing empty or
cleared MMKV snapshot does record that notice.

The native store's existence is the import-complete marker. A damaged native
file uses defaults plus the settings notice and attempts an atomic reset. Failed
reads, directory creation, reset writes and notice writes cannot prevent
foreground wallet admission. The fallback stays in memory under the existing
process-wide lock, shared by file path so another settings object cannot overwrite
a recovered onboarding marker with stale defaults. Explicit writes and new opens
retry persistence. Durable write APIs still throw, and health/sync checks reject
an unpersisted fallback: recurring execution and the independent automation
authority remain stopped on write failure. A damaged native file never reimports
RN; the wallet itself is unaffected. Pending temporary files are
never loaded as a fallback. Wallet deletion resets the native file with a durable
cleanup-pending flag, deletes the RN directory and snapshot, then clears the flag.
Interrupted cleanup retries on the next settings access. The empty native file
remains, so re-onboarding can never import the old RN wallet again.

Fresh native creation durably records `wallet-storage.isInitialized=false` under
the existing creation gate and session reservation before generating/saving the
seed or creating the database. Marker-write failure creates neither and permits a
retry through the existing onboarding error. Public-key lookup happens afterwards.
Both backup and phrase restore write the same marker under the restore gate and
reservation before installing staged data or committing the seed. Existing
restore completion/recovery still owns marking a completed restore initialized.
Interrupted creation with seed + DB therefore resumes onboarding; an existing
wallet with missing/unreadable settings still opens Home. Default preferences
mean biometric lock off, suspension cleared, balances visible, USD/bip177 display,
and auto-boarding prompts on; boarding still requires confirmation.

Background opens require the seed/DB launch decision Home, no pending restore or
deletion, no explicit unfinished onboarding, and no suspension. They read seeds
only; Android's foreground migration remains after verified SDK open. Fresh
installs create a native file and an absent-source snapshot, never live MMKV files.
Quarantine, support/settings-blocked states, Continue/model rebuilding, automatic
restoration, retirement, error handlers and lazy-reload guards have been removed.

The native format is a version-1 UTF-8 JSON envelope with `payload` (a JSON string)
and `sha256` (SHA-256 of that string). Payload contains `values` (string-to-string
map of RN envelopes/other small native values), `settingsNoticePending`,
`recurringNoticePending`, and `cleanupPending`. Strict decoding checks version,
field sets, string/boolean types, UTF-8 and digest. Writes use same-directory temp
→ file fsync → atomic rename → parent-directory fsync. Each filesystem stage and
sync operation is injectable for interruption tests. SettingsStorage's public
read/write APIs remain; durable failures still stop financial automation.

Paths: iOS `Documents/mmkv.native-settings.json`; Android
`noBackupFilesDir/settings.native.json`. Snapshot paths remain
`Documents/mmkv.native-backup` / `noBackupFilesDir/mmkv.native-backup`.


Settings review fixes (2026-10-20, base `10c0b996`): actual file/parent permission
faults reproduced `EACCES`/Cocoa 513 before the fix on both platforms. Interrupted
creation probes also failed before marker ordering changed. Afterward, tests cover
constructor and later-read fallback, once-only notice consumption while writes
fail, recovered persistence shared across readers, creation interruption after the
marker/seed/DB and before public-key lookup, failed marker retry without a wallet,
restore marker ordering/retry, and recurring executor/authority fail-closed behavior.
Creation interruptions use injected stop boundaries and reopened durable settings;
these are not OS-kill UI tests.

Final Android required command ran once after the last code change:
`nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug :app:compileRegtestDebugAndroidTestKotlin'`:
**3,011 JVM tests / 106 suites / zero failures, errors or skips**, APK assembled,
instrumentation Kotlin compiled. Targeted final checks: **25 JVM tests passed**.
On the owned API-35 emulator at console port 5600, the broader storage run was
**17 tests / 16 passed / 1 failed**: unchanged
`AndroidStorageTest.realRNFormatsDataStoreAndPreferencesRemainUnchanged` uses
`instrumentation-only-secret` as a mnemonic and receives `invalidMnemonic` from
existing BIP39 validation. Its fixture was left untouched. Both new regressions,
all ten real-JNI import tests, real Keystore persistence, Expo ID and both auth
storage tests passed. Final installed APK + instrumentation APK were checked
again after the required command: **12/12 passed** (`SettingsFailureTest` and
`MMKVRecoveryTest`, including the real executor write-failure probe).

Final iOS full `NoahTests` ran once after the last code change, with generated
project, arm64/active architecture, signing enabled and private `/private/tmp`
DerivedData: **667 total / 645 passed / 22 opt-in skipped / zero failures**, 135
suites (**963 expanded passing cases**). Targeted settings and onboarding/restore
checks passed: six test functions / ten expanded cases. Owned iPhone-17 simulator
`NoahSettingsFix809c` was identified, shut down and deleted; private DerivedData
and all six result bundles were removed. The same-named owned AVD at port 5600
was identified, stopped, verified absent and deleted. No physical devices, other
agents' devices or Docker were used. `client/` and `server/` were unchanged.


All eight stores use `client/src/lib/mmkv.ts` instance `noah-wallet-storage` in
`files/mmkv`, with Zustand `persist` + `createJSONStorage` envelopes
`{"state":{...},"version":N}`. Functions are omitted by JSON serialization.
Missing version means v0. No store uses encrypted MMKV. No persisted store in
`client/src/store` is omitted below.

| RN file / key | Persisted fields | Version / RN migration | Android consumer |
| --- | --- | --- | --- |
| `walletStore.ts` / `wallet-storage` | `isInitialized`, `isWalletLoaded`, `staticVtxoPubkey`, `isBiometricsEnabled`, `isDebugModeEnabled`, `isWalletSuspended`, `hasSeenBatteryOptimizationPrompt` | v0; partialize excludes errors, restore progress, background job flag/time | Launch/onboarding, wallet lock, suspension, debug settings |
| `backupStore.ts` / `backup-storage` | `backupPending`, `lastBackupAt`, `lastBackupAttemptAt`, `lastBackupStatus`, `lastBackupError`, `lastBackupId`, `lastUploadedSnapshotSha256` | v0; all data fields | Backup settings/coordinator |
| `esploraStore.ts` / `esplora-storage` | `endpointOverride` (nullable string) | v0; partialize; merge maps non-string to null | **No native consumer yet**; codec/fixture only, no launch read or mutation |
| `privacyStore.ts` / `privacy-storage` | `isHomeBalanceHidden` | v0; all data fields | Home balance visibility |
| `profileStore.ts` / `profile-storage` | `displayName`, `preferredCurrency`, `bitcoinAmountUnit` | v0; merge defaults unsupported currencies to USD and units to bip177 | Home, amount formatting, profile/currency/unit settings |
| `recurringPaymentStore.ts` / `recurring-payment-storage` | `schedules` record, `serverSyncPending` | v0; all data fields; complete rule shape in `client/src/types/recurringPayment.ts` | Recurring list/editor/runtime; unsupported rows remain visible and unsendable |
| `serverStore.ts` / `server-storage` | `isRegisteredWithServer`, `lightningAddress`, `nostrPubkey`, `isBackupEnabled`, `emailAddress`, `isEmailVerified`, `isEmailPromptDismissed`, `mailboxAuthorizationExpiry`, `isMailboxAuthorizationEnabled`, `lastReportedDeviceFingerprint`, `lastDeviceReportAt` | v0; all data fields; reset keeps mailbox-enabled preference | Account, identity, email, mailbox and backup settings; server registration |
| `transactionStore.ts` / `transaction-storage` | `isAutoBoardingEnabled`, `hasAttemptedAutoBoarding` | **v1**; v0 migration forces attempted=false; partialize drops transient `autoBoardSuccessBanner` | Auto-board preference/prompt; native preserves unknown/transient legacy fields instead of deleting them |

RN recurring rows persist `id`, `label`, `destination`, `destinationType`, `amountSat`,
`comment`, `interval` (`unit`, `every`), `startAt`, `endAt`, `maxOccurrences`, `status`,
`nextOccurrenceIndex`, `nextRunAt`, `occurrencesPaid`, `consecutiveFailures`,
`lastError`, `retryNotBefore`, `inFlight` (`occurrenceIndex`, `startedAt`), `runs`
(`occurrenceIndex`, `scheduledFor`, `attemptedAt`, `status`, `amountSat`, optional
`error`), `createdAt`, and `updatedAt`. Native-only revision/binding fields remain
unknown to RN; unsupported numeric rows stay preserved and unsendable.

`RNStoreCodec` rejects malformed envelopes, future/incorrectly typed versions and
incorrect known field types. During import, invalid keys are dropped independently.
Android keeps valid raw full envelopes in its native store and merges typed writes through
`SettingsStorage.set`, preserving unknown root/state fields. The importer uses defaults for a bad RN value; direct codec operations still reject invalid writes. Auto-board reads apply RN's v0 attempted reset;
a corrupt envelope disables the prompt. Esplora is deliberately not wired to the
runtime until its screen is implemented. The codec does not rewrite RN data on read.

`rn-store-compatibility.json` is the same raw-envelope fixture iOS consumes.
One deterministic generator executes the eight actual RN stores, the transaction
v0 migration and profile merge. Run from the repository root:

```sh
NODE_PATH=<installed-repo>/node_modules bun --tsconfig-override client/tsconfig.json \
  apps/fixtures/tools/generate-rn-store-compatibility.ts
```

Its 196 cases cover unknown numeric-token preservation, exact versions, malformed
envelopes, nullability and profile fallback. Tests pass `input.raw` directly to
native codecs without a parse/stringify round trip. Unknown currency/unit strings
and nullable profile values survive writes; consumers use USD/bip177 as RN does.
Versions must be absent or exactly `0` (`transaction-storage` also allows `1`).

Existing `recurring-storage.json` and `recurring-rn-range.json` retain their role:
real RN rule fields and numeric edge cases are covered by the recurring suites.

RN seed behavior: `client/src/lib/crypto.ts` stores/returns the phrase verbatim;
empty passwords become absent. `walletApi.ts:createWallet` refuses nonempty seed
or wallet-data-only states, but native validation occurs later when loading.
`restoreWallet` writes the supplied phrase before loading it. Android deliberately
strengthens these paths: an empty/corrupt saved phrase is `invalidSeed`, every source
must agree byte-for-byte after BIP39 validation, and seed writes refuse invalid/conflicting
existing records. SDK `OnchainWallet.openExisting` checks network/fingerprint read
only before opening/migrating the DB; seed migration runs only explicitly after a successful foreground open. Background maintenance, Lightning invoice/claim, backup and recurring pushes only read seeds; a later job or foreground open reuses an identical native copy without rewriting it. Session reuse is bound to the exact SDK-verified credential, so an already-open wallet cannot authorize migration of a different seed.
Validation checks normalized BIP39 while retaining the exact original credential
string: RN uses it as the backup encryption password. Even valid formatting/case
differences between sources block automatic selection. The blocking recovery screen offers retry and support guidance, without a delete,
create or restore action, and never displays any saved phrase.

Both apps consume 6 basic and 30 safety source-level launch cases and the same
37 normalization vectors. See `../../fixtures/README.md` for the outcome/diagnostic
table and the exact RN/native normalization and backup-password rules. Android
now distinguishes `invalidSeed`, `seedConflict` and `incompatibleStorage` from
transient secure-storage unavailability; all are blocking and preserve bytes.
The SDK mismatch maps to `seedConflict`, as on iOS. No background opener writes
seeds. Interrupted-restore recovery still settles through its existing exclusive
lifecycle gate; its explicitly journaled commit is separate from seed migration.

Round 1 verification (2026-10-20): targeted seed/open/store/source checks passed;
the final required command ran once with `--max-workers=2`:
`:app:testRegtestDebugUnitTest :app:assembleRegtestDebug` — **2,858 tests /
78 suites, zero failures/errors/skips; regtest APK assembled successfully**.
No emulator, physical device or Docker was used. Shared fixture regeneration was
byte-identical (SHA-256
`1abc20a702b77724e543687ead88c55d2b15c2f7d1f40bc7771937be4bdbe241`).
The funded RN-to-native device procedure was not repeated; Android SDK argument
boundaries are checked in JVM tests, and real offline SDK open/auth is covered
on iOS with the identical public normalization vectors.

MMKV recovery review (2026-10-20, base `172446b6`): automatic restoration retires
at the first successful validated open using a fsynced record outside MMKV.
Previously admitted RN key loss or any later CRC/length recovery blocks.
The backup and retirement file live in `noBackupFilesDir`; wallet deletion
removes published/staging snapshots while preserving retirement through
re-onboarding. Absent keys may use defaults only when never admitted; present
values with invalid UTF-8 or no decodable bytes block, using strict byte decoding
before JSON validation. Existing seed+DB with wallet-storage already missing at
first native open still resumes foreground onboarding. Every headless opener
requires explicit initialization, and committed deletion waits for foreground.
See `DEVICE.md` and shared `../../fixtures/mmkv-launch-state.json` (nine vectors).

Review round 2 (2026-10-20, base `4f7228c5`): admission inventories every RN key
on both platforms, including recurring. Its existing codec validates envelopes;
unsupported recurring rows stay visible/stopped, while unreadable bytes or a
missing admitted recurring key block later binding as well as launch. First-open
live keys must cover the immutable snapshot's key inventory; partial loss or
undecodability restores the closed pair once or blocks. Onboarding for missing
wallet-storage applies when the snapshot itself had no such key.

Recovery callbacks durably quarantine outside MMKV before returning recover.
The marker is UTF-8 `1\nnoah-wallet-storage\n`, file- and directory-synced.
After restoration retirement, readable recovered settings require the foreground
**Continue** acknowledgement (`SETTINGS_RECOVERED`); unreadable/lost admitted
values remain blocked (`STORAGE_UNSUPPORTED`). Restart/retry and headless paths
cannot clear it. Review biometrics, wallet suspension, balance hiding and backup
settings after acknowledging. The independent recurring policy ledger remains
in force. See `DEVICE.md` and all **15** shared storage launch vectors.
Final Android verification: **2,961 JVM tests / 94 suites / zero failures or
skips**, APK assembled and all 17 JNI probes compiled; no Android device run.


Review round 3 (2026-10-20, base `32e1b6ef`): raw-file integrity is now the primary
admission signal, before MMKV can silently fall back to last-confirmed state
without a callback. See `../../fixtures/rn-mmkv/README.md` for the verified
112-byte metadata layout and fixture pinning. A durable marker precedes every
recovery mapping; failure blocks with unchanged bytes. Secondary callbacks stay
associated for lazy reloads and terminate before returning if marker durability
fails, because MMKV's discard option also rewrites data. Continue rebuilds the
biometric policy and reloads every cached settings consumer before launch;
locked wallet content is not composed. Both real-JNI storage suites ran on an
owned emulator: **22 passed / zero failures or skips**. No store key, RN envelope,
seed format, recurring implementation, client or server code changed. Full check
counts and device cleanup are recorded in `DEVICE.md`.
