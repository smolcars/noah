# RN persistence inventory (c6283d16)

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
incorrect known field types. Existing native consumers are checked before opening
the wallet. Android keeps the raw full envelope and merges typed writes through
`SettingsStorage.set`, preserving unknown root/state fields. A bad existing value
cannot be replaced with defaults. Auto-board reads apply RN's v0 attempted reset;
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
