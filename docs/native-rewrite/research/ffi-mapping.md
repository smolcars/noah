# Noah → native Bark/BoltFFI research

Research date: 2026-10-19. Noah repository was read-only; clones and notes are under `/tmp/noah-native-research/`. This is source analysis, not a successful installed-app upgrade test.

Source abbreviations (all citations are `prefix/path:line`):

- **N** = `/Users/niteshchowdharybalusu/Documents/noah`, HEAD `d367766ae9eb5a646bc764525063e73c9a7e63ea`.
- **R** = `/tmp/noah-native-research/ffi/react-native-nitro-ark`, tag `v0.0.144`, commit `db32fd9b7febb1359a6e4498865d098feee723f5`.
- **B** = `/tmp/noah-native-research/ffi/bark-bolt-ffi`, inspected main `bbe22f342debc8ae20cb8c42d250b5ae56f4855b`.
- **U** = `/tmp/noah-native-research/ffi/bark`, pinned `bfbe6b7c3088a09dd4e81c4ac24cdc763a627a8c`. Old tag `bark-0.7.1` resolves to `1d1e4c8950769ca3fd884088955e7a5316d39b1d`.

Primary remote sources: [Nitro Ark](https://github.com/smolcars/react-native-nitro-ark/tree/v0.0.144), [BoltFFI source](https://github.com/smolcars/bark-bolt-ffi/tree/bbe22f342debc8ae20cb8c42d250b5ae56f4855b), [pinned Bark](https://gitlab.com/ark-bitcoin/bark/-/tree/bfbe6b7c3088a09dd4e81c4ac24cdc763a627a8c). The comparisons below use cloned source, not README claims alone.

## 1. Wallet continuity: preserve the existing database and seed

**The intended upgrade is opening the same `db.sqlite` with the same seed/network. It is not seed recovery into an example app's new directory.** Both Bark revisions use schema 42; their production migration chain is unchanged. There is strong source evidence for DB compatibility, but no real Noah database was opened with the new SDK in this investigation. Treat an installed-app upgrade fixture as a release gate.

### Exact current paths

`ARK_DATA_PATH` is derived from `RNFSTurbo.DocumentDirectoryPath`; iOS resolves that using `NSDocumentDirectory`, Android using `Context.getFilesDir()`. Neither `walletConfig.ts` nor Nitro invents another directory: create/load receive Noah's explicit path. Nitro appends `db.sqlite`. (N/client/src/constants.ts:22,40; N/node_modules/react-native-fs-turbo/ios/RNFSTurboModule.mm:48; N/node_modules/react-native-fs-turbo/android/src/main/java/com/cmpayc/rnfsturbo/RNFSTurboModule.java:88; N/client/src/lib/walletApi.ts:96,227; R/react-native-nitro-ark/cpp/NitroArk.hpp:438,464; R/bark-cpp/src/utils.rs:31.)

`<UUID>` is the OS-assigned app data container, not a persistent string to hardcode. Android examples use user 0; other Android profiles have a different user number.

| Platform / variant | App ID | Absolute datadir (database is `/db.sqlite` inside it) |
|---|---|---|
| iOS mainnet | `com.noahwallet.mainnet` | `/var/mobile/Containers/Data/Application/<UUID>/Documents/noah-data-mainnet` |
| iOS signet | `com.noahwallet.signet` | `/var/mobile/Containers/Data/Application/<UUID>/Documents/noah-data-signet` |
| iOS regtest | `com.noahwallet.regtest` | `/var/mobile/Containers/Data/Application/<UUID>/Documents/noah-data-regtest` |
| Android mainnet | `com.noahwallet.mainnet` | `/data/user/0/com.noahwallet.mainnet/files/noah-data-mainnet` |
| Android signet | `com.noahwallet.signet` | `/data/user/0/com.noahwallet.signet/files/noah-data-signet` |
| Android regtest | `com.noahwallet.regtest` | `/data/user/0/com.noahwallet.regtest/files/noah-data-regtest` |

Android's `/data/data/<package>/files/...` is the usual equivalent user-0 path. iOS Simulator prefixes are `<simulator-device>/data/Containers/Data/Application/<UUID>/Documents/...`. Resolve through platform directory APIs. Variant IDs: N/client/android/app/build.gradle:146,153,160; N/client/ios/Config/mainnet.xcconfig:7 and corresponding signet/regtest files:7.

### SQLite, layout and opening differences

| Concern | Evidence and migration consequence |
|---|---|
| Embedded Bark | Nitro `v0.0.144` uses `bark-wallet` and `bark-bitcoin-ext` tag **bark-0.7.1**; not the September Docker image's revision. BoltFFI explicitly pins **bfbe6b7c**, documented as `0.7.1-dev,2026-10-16`. R/bark-cpp/Cargo.toml:7,15; B/Cargo.toml:14,20; B/docs/migration.md:3. |
| DB filename | Nitro uses `db.sqlite`; upstream default remains `db.sqlite`. BoltFFI chooses it first and falls back to legacy `bark.sqlite` only if `db.sqlite` does not exist. The migration guide's blanket “bark.sqlite filename” statement is misleading for Noah. Do not rename Noah's DB to match that sentence. R/bark-cpp/src/utils.rs:31; U/bark/src/persist/sqlite/mod.rs:42; B/src/ffi/db.rs:25. |
| Migrations | `SqliteClient::open` runs all migrations; migration version lives in `migrations.value`, not `PRAGMA user_version`. Each missing migration commits separately. Both revisions end at Migration0042. `git diff bark-0.7.1 bfbe6b7c -- bark/src/persist/sqlite/migrations` changes only a test's typed-height conversion in m0029. A healthy already-updated Nitro DB needs **no new schema migration**. U/bark/src/persist/sqlite/mod.rs:53; U/bark/src/persist/sqlite/migrations/mod.rs:100,148,169,199. |
| Stored contents | Ark state and BDK changesets share this database; retain the full DB, including movements, metadata, VTXOs/genesis, keys, pending actions/rounds/Lightning, exits, and onchain cache. Both wrappers load BDK using the same BIP39 seed and network. R/bark-cpp/src/lib.rs:374; U/bark/src/persist/sqlite/mod.rs:89; U/bark/src/onchain/bdk.rs:161. |
| `config.toml` | Neither mobile wrapper reads/writes a wallet `config.toml` in these create/open paths. Runtime config is supplied by the host; network/fingerprint/server keys are database properties. A CLI's config/seed files are not the mobile storage contract. R/bark-cpp/src/utils.rs:243; R/bark-cpp/src/lib.rs:374; B/src/ffi/wallet.rs:1072; U/bark/src/lib.rs:1321. |
| Network | Nitro loads `properties.network` from DB; BoltFFI accepts an explicit network. Pass `.bitcoin`/`BITCOIN`, `.signet`/`SIGNET`, or `.regtest`/`REGTEST` matching the existing variant. BDK checks network/descriptors; Ark rejects incorrect seed fingerprint. R/bark-cpp/src/lib.rs:375; U/bark/src/onchain/bdk.rs:165; U/bark/src/lib.rs:1336. |
| Locking | Nitro explicitly uses `MemoryLockManager`; BoltFFI selects native locks from datadir. At this revision **both iOS and Android use `FcntlPidLockManager`**, owning `<datadir>/LOCK`. A second owner can fail; don't remove a live lock file to force opening. Old Nitro background processes do not participate in this file lock, so they must be quiesced too. R/bark-cpp/src/lib.rs:384; B/src/ffi/wallet.rs:1084; U/bark/src/lock_manager/mod.rs:64,197. |
| Defaults | Nitro disables Bark daemon and refuses absent wallet properties. BoltFFI defaults `runDaemon=true`, `createIfNotExists=true`. Upgrade opens must set `createIfNotExists=false`; decide daemon policy explicitly. DB helper still creates a missing directory/empty SQLite file before the later refusal, so preflight DB existence before either factory. R/bark-cpp/src/lib.rs:390; B/src/ffi/wallet.rs:19; B/src/ffi/db.rs:21. |
| Config units | Keep Noah's endpoints and overrides, map `ark→serverAddress`, `esplora→esploraAddress`, `bitcoind→bitcoindAddress`, plus credentials/thresholds/userAgent. **Fallback fee changes from sat/kvB to sat/kwu**: Noah's `10000` (10 sat/vB) becomes `2500`, not `10000`. R/bark-cpp/src/utils.rs:160; B/src/config.rs:101; N/client/src/lib/walletConfig.ts:20,89. |

Open failures can come from missing/unreadable/corrupt DB, wrong seed/network, already-held locks, invalid config, inaccessible chain source, or unsupported backend versions. `Wallet::open` checks the chain source and its version; a failure is not proof the DB needs replacement. U/bark/src/lib.rs:1286,1336,1340,1370.

### Seed continuity is a separate migration requirement

Noah stores the mnemonic through `react-native-keychain` under service **`com.noah.mnemonic.<variant>`**, account/username **`noah`**. Preserve bundle/package IDs, signing continuity and Keychain access. The example `SeedStore` is **not compatible storage**:

- iOS example uses service `io.github.niteshbalusu11.bark.example` and account `wallet` by default, with device-only protection. Read the existing generic-password item first; only migrate it after successful DB validation.
- Android example uses preferences `wallet-seeds`, Keystore alias `bark-wallet-seed`, and base64 `nonce || AES-GCM ciphertext/tag`. Existing RN Keychain v10 stores `RN_KEYCHAIN` DataStore entries (`<service>:u`, `:p`, `:c`) and selects the cipher from `:c`, including a SharedPreferences migration. Port its existing decrypt path before adopting new seed storage; the example cannot discover/decrypt it automatically.
- The example iOS datadir is `Library/Application Support/wallet`; Android is `files/wallet`; restores use fresh UUID suffixes. None are Noah's directories.

Sources: N/client/src/lib/crypto.ts:16,76; N/client/src/constants.ts:19; N/node_modules/react-native-keychain/ios/RNKeychainManager/RNKeychainManager.m:455; N/node_modules/react-native-keychain/android/src/main/java/com/oblador/keychain/PrefsStorageBase.kt:32; N/node_modules/react-native-keychain/android/src/main/java/com/oblador/keychain/DataStorePrefsStorage.kt:29,41; B/examples/ios/BarkExample/SeedStore.swift:6; B/examples/android/src/main/java/io/github/niteshbalusu11/bark/example/SeedStore.kt:15; B/examples/ios/BarkExample/WalletModel.swift:29; B/examples/android/src/main/java/io/github/niteshbalusu11/bark/example/WalletModel.kt:40. Actual decryption of a deployed RN Keychain item is **unverified**.

### Recommended upgrade / fallback contract

1. Resolve existing variant datadir and seed. Treat “only one exists” as an incomplete wallet, never permission to generate a replacement seed.
2. Stop push/background wallet work and drain pending operations. Save a consistent SQLite snapshot and record schema/network/fingerprint, VTXO IDs/balances, movements/metadata and pending action IDs.
3. Open `OnchainWallet.default` against that datadir; open `Wallet.openWithOnchain` with the same seed/network and `createIfNotExists=false`, initially `runDaemon=false`. Compare the recorded state before enabling spending/background activity. Preserve the original snapshot on any failure.
4. Only after verification, enable sync/daemon/notifications under the native app's lifecycle coordinator. Stop notification waits, await `stopDaemonWait`, then release handles (Kotlin `close`; Swift ARC) before swapping databases.
5. If reopening fails, diagnose/retry or restore a validated snapshot. Seed recovery is a last-resort **separate datadir** operation, not an automatic replacement of the historical DB. Run onchain `initialScan(0)` and inspect `recoveryStatus` plus `RecoveryReport.isComplete`; an apparently successful open can contain incomplete recovery.

This sequence is a recommendation, not implemented here. Source lifecycle: B/src/ffi/wallet.rs:19,1059; B/src/ffi/onchain.rs:30,69; B/src/core/wallet.rs:164,218; B/examples/ios/BarkExample/WalletModel.swift:39; B/examples/android/src/main/java/io/github/niteshbalusu11/bark/example/WalletModel.kt:34.

Fresh-seed recovery can find current funds using signed xpub recovery and regular/recovery mailboxes; full onchain scan discovers onchain history. It **does not preserve original Ark movement history/app metadata**. Recovery still depends on server records/protocol support and key scan limits; do not promise recovery of every old VTXO solely from the seed. B/docs/migration.md:77; U/bark/src/recovery.rs:611,726; B/src/config.rs:56. Required unrun fixture: an actual 0.0.144-created funded wallet with pending receives, boards, rounds, exits and annotated history, updated in place on each OS, including interrupted-update/retry and snapshot rollback before any post-upgrade spend.

## Byte-compatible encrypted backup contract

Both platforms implement the same cryptographic envelope. No cipher/KDF/field-order mismatch found by source inspection; cross-platform fixture execution was not performed.

| Byte offset | Length | Meaning |
|---|---:|---|
| 0 | 1 | Version byte: `0x01` legacy full-directory ZIP, `0x02` current snapshot ZIP |
| 1 | 16 | Cryptographically random salt |
| 17 | 12 | Cryptographically random AES-GCM nonce / IV |
| 29 | variable | Ciphertext of ZIP archive |
| final 16 bytes | 16 | GCM authentication tag |

KDB/ PBKDF2-HMAC-SHA256, **600,000 iterations**, **32-byte / 256-bit key**, password is the supplied mnemonic string, not the BIP39-derived seed. Swift explicitly uses UTF-8 bytes; Android passes the characters to `PBEKeySpec`/`PBKDF2WithHmacSHA256`. Neither backup layer trims/collapses whitespace or applies BIP39 normalization. Preserve the exact mnemonic representation when decrypting existing data. English-word mnemonics have the same password bytes; non-ASCII provider behavior was not tested. Cipher: AES-256-GCM, no padding, 128-bit tag, no AAD. Thus the version is **not authenticated as AAD**. v2 deliberately calls v1 encryption then overwrites byte zero with 2; v2 decryption requires 2, changes it back to 1, and uses the v1 decoder. Swift writes ciphertext and tag separately; Java's `doFinal` provides ciphertext followed by tag. Salt/nonce come from `SecRandomCopyBytes` / `SecureRandom`. Sources: `N/client/nitromodules/noah-tools/ios/NoahToolsBackup.swift`:313,329,335,351,370,377,388; `N/client/nitromodules/noah-tools/android/src/main/java/com/margelo/nitro/noahtools/NoahToolsBackup.kt`:23,317,342,346,368,404,414.

**v1 payload/transport:** base64 encoded encrypted envelope (standard alphabet, no line wraps). ZIP root is `backup_staging/noah-data-{mainnet|signet|regtest}/...`, containing the copied wallet data directory, including whatever sidecars/files existed. iOS stages from Documents; Android from `Context.filesDir`. Neither legacy creation function uses SQLite Online Backup, so this is not a guaranteed coherent snapshot of a live WAL database. Restore decrypts/unzips, deletes the existing destination, moves that variant's directory into place if present, and returns true even if the archive lacks that directory. There is no manifest, fingerprint validation, or rollback transaction in the legacy native restore. Sources: `N/client/nitromodules/noah-tools/ios/NoahToolsBackup.swift`:174,204,214,237,280,287; `N/client/nitromodules/noah-tools/android/src/main/java/com/margelo/nitro/noahtools/NoahToolsBackup.kt`:174,200,224,236,261,285,290.

**v2 payload/transport:** binary `.noahbackup` file, not base64. Deflated ZIP contains exactly the intended root files `manifest.json` (UTF-8) and `db.sqlite` (snapshot). ZIP implementation, entry order, timestamp metadata and compression bytes need not be identical; decoders must accept either platform's standard ZIP. Current encrypted file information is path, byte length and lowercase SHA-256 hex of the **entire encrypted envelope**, whereas the manifest hash is lowercase SHA-256 of the **plaintext SQLite file**. Sources: `N/client/nitromodules/noah-tools/ios/NoahToolsBackup.swift`:8-65,68-113,346; `N/client/nitromodules/noah-tools/android/src/main/java/com/margelo/nitro/noahtools/NoahToolsBackup.kt`:32-111,355; N/client/src/lib/backupService.ts:151-205.

Manifest fields, with exact JSON casing:

```json
{
  "formatVersion": 2,
  "createdAt": "ISO-8601 UTC timestamp from new Date().toISOString()",
  "snapshotSha256": "lowercase hex SHA-256 of db.sqlite",
  "network": "bitcoin | signet | regtest",
  "walletFingerprint": "Bark fingerprint.to_string()",
  "serverPubkey": "Bark properties.server_pubkey.to_string(), or null",
  "mailboxPubkey": "Bark properties.server_mailbox_pubkey.to_string(), or null",
  "barkSchemaVersion": 42
}
```

Schema 42 is current for the inspected versions, not a forever constant. Snapshot metadata supplies it. `mailboxPubkey` is specifically the **server mailbox public key**, not a newly derived user mailbox key. Source: N/client/src/lib/backupService.ts:39-50,97-106,108-137; R/bark-cpp/src/backup.rs:328-338,353-370. Manifest parser checks field types/version, not timestamp validity or hash-string length.

## Coherent snapshot and restore/install protocol to retain

1. `createWalletSnapshot` requires a loaded wallet. Nitro reads active DB path/properties, serializes snapshot generation with `SNAPSHOT_MUTEX`, refuses clobbering the target or using the live DB as destination, creates a same-parent partial file, and uses SQLite Online Backup (`rusqlite::backup::Backup`). It copies 128 pages per step, waits 10ms on busy/locked with a 30s continuous busy deadline, then checkpoints destination WAL with `TRUNCATE`. It inspects metadata, runs `PRAGMA integrity_check` and `foreign_key_check`, fsyncs, and publishes without overwriting. This is much safer than copying `db.sqlite` while writes continue. Source: R/bark-cpp/src/backup.rs:19-24,45-51,74-113,177-198,236-315.
2. `performBackup` hashes a fresh snapshot and skips upload when equal to `lastUploadedSnapshotSha256`; otherwise reads mnemonic, encrypts manifest+snapshot, initiates server upload with `{format_version:2, encrypted_size, encrypted_sha256}`, PUTs binary with Content-Length/octet-stream/server-supplied S3 checksum header, then calls complete with backup_id. Both cache files are cleaned in finally. Local export uses the same format. Source: N/client/src/lib/backupService.ts:147-261.
3. Restore lists retained server backups. 404 or empty list uses legacy v1 restore. Otherwise it tries supported v2 backups in returned order, continuing when download/decrypt/validation fails. It does **not** automatically fall back to legacy when a nonempty retained list has only unsupported/broken entries. Source: N/client/src/lib/backupService.ts:347-407.
4. Download encrypted file to unique cache path; decrypt to another unique cache directory; parse manifest; enforce expected network (`mainnet` maps to `bitcoin`). Validate SQLite snapshot against manifest network/fingerprint/serverPubkey. Nitro validation refuses live DB, runs integrity/foreign-key checks, rejects source schema newer than supported, copies snapshot to a temporary DB, opens/migrates only that copy, then reads properties, all VTXOs, movements, pending round state IDs, wallet-action checkpoints, exit entries and BDK state to catch incompatible serialized data. Returned hash/schema are for the **original source**. JS compares original hash/schema and server/mailbox keys to manifest before installation. This validates manifest against DB; mnemonic-to-fingerprint validation happens on wallet open. Source: N/client/src/lib/backupService.ts:263-343; R/bark-cpp/src/backup.rs:116-175,288-338,390-425.
5. Native install creates sibling `.wallet-install-UUID/`, copies only `db.sqlite`, renames entire existing wallet directory to `.wallet-rollback-UUID/` if present, then renames install directory to wallet path. Failed install attempts to move rollback directory back. Returns rollback path, empty if no prior wallet. **No WAL/SHM/config/mnemonic files from the old directory are mixed with the replacement snapshot.** Source: `N/client/nitromodules/noah-tools/ios/NoahToolsBackup.swift`:116-150; `N/client/nitromodules/noah-tools/android/src/main/java/com/margelo/nitro/noahtools/NoahToolsBackup.kt`:113-148.
6. JS opens replacement with `loadWalletWithMnemonic(mnemonic)`, then stores mnemonic in keychain, and additionally native mnemonic storage when UnifiedPush is enabled. Only after success does it delete rollback data; then seeds backup-store snapshot hash/id and wallet initialized/loaded state. Failure tries to close replacement wallet, restore previous keychain/native mnemonic, remove replacement and rename rollback back. If rollback itself fails, report that it could not be rolled back safely. Source: N/client/src/lib/backupService.ts:411-559; `N/client/nitromodules/noah-tools/ios/NoahToolsBackup.swift`:152-171; `N/client/nitromodules/noah-tools/android/src/main/java/com/margelo/nitro/noahtools/NoahToolsBackup.kt`:150-171.
7. This rename protocol handles ordinary exceptions, **not a durable crash journal**: there is no persisted install phase/rollback pointer or startup recovery scan in these routines. Also the low-level install routines do not close active DB handles themselves; a native rewrite must ensure exclusive closed/quiesced ownership before renaming. The current JS path does not explicitly close an already-open prior wallet before install. This is a real migration concern, not a byte-format difference. Sources: N/client/src/lib/backupService.ts:325,488-505; `N/client/nitromodules/noah-tools/ios/NoahToolsBackup.swift`:116-171; `N/client/nitromodules/noah-tools/android/src/main/java/com/margelo/nitro/noahtools/NoahToolsBackup.kt`:113-171.

Backup scheduling behavior: initialized+loaded+not suspended wallet only, normally backup-enabled; one in-flight pass; 5s debounce; generation counters retain writes arriving during upload; retry starts 15s exponential with 0.8–1.2 jitter capped at 15min; flush drains through requested generation; no request runs against suspended/unloaded wallet. Port these invariants alongside background ownership. Source: N/client/src/lib/backupCoordinator.ts:9-34,50-85,89-147,150-199.

### Platform differences / decoder defects

- No demonstrated iOS/Android envelope mismatch. Android explicitly rejects blank mnemonic and encrypted data shorter than 45 bytes; Swift relies more on CryptoKit/failing slices. Different error handling, not valid-data incompatibility (`N/client/nitromodules/noah-tools/ios/NoahToolsBackup.swift`:351-374; `N/client/nitromodules/noah-tools/android/src/main/java/com/margelo/nitro/noahtools/NoahToolsBackup.kt`:317-320,368-379).
- Swift v2 encrypted/temp ZIP writes use atomic replacement; Android `writeBytes` writes directly. A crash can leave an Android partial encrypted artifact where Swift attempts atomic publication. Unique names, completed-upload handshake, and AEAD validation reduce impact, but do not call them equivalent durability (`N/client/nitromodules/noah-tools/ios/NoahToolsBackup.swift`:53,92; `N/client/nitromodules/noah-tools/android/src/main/java/com/margelo/nitro/noahtools/NoahToolsBackup.kt`:61,93).
- Swift rollback removes files with throwing filesystem calls; Android ignores `deleteRecursively()` boolean. Both ignore final rollback-directory cleanup errors, and both best-effort restoration after failed install may fail silently. Android should report failed deletion explicitly in a robust rewrite (`N/client/nitromodules/noah-tools/ios/NoahToolsBackup.swift`:138-140,152-170; `N/client/nitromodules/noah-tools/android/src/main/java/com/margelo/nitro/noahtools/NoahToolsBackup.kt`:136-140,150-168).
- Android's ZIP extractor sanitizes `../` and `..\\` strings, then uses a canonical-path prefix test without a path separator; Swift delegates extraction to ZIPFoundation. Not identical malformed-archive behavior. Do not reproduce weak path checks in a new importer; validate resolved paths are descendants and constrain v2 entries. Normal two-file ZIPs are compatible (`N/client/nitromodules/noah-tools/android/src/main/java/com/margelo/nitro/noahtools/NoahToolsBackup.kt`:420-422,448-485; `N/client/nitromodules/noah-tools/ios/NoahToolsBackup.swift`:95).
- `scripts/decrypt_backup.js` is a useful **v1-only** reference decoder. KDF and offsets are correct, but `version !== 1` is rejected at lines 28-30, and binary byte 2 is mistakenly treated as base64 at lines 100-109. Update decoder to recognize binary 1/2 and accept both envelope versions before using it to test v2. Its crypto need not otherwise change (N/scripts/decrypt_backup.js:13-55,95-126).
- Add FFI snapshot APIs or an app-owned Rust backup adapter matching Nitro's consistent snapshot and nondestructive validation. FFI's current API baseline contains no `createWalletSnapshot`/`validateWalletSnapshot` equivalent. Native Swift/Kotlin encryption/install routines can be retained/ported; they do not belong intrinsically to Bark.


Inventory: scanning every `client/src` `.ts`/`.tsx` import from `react-native-nitro-ark`, plus `NitroArk.*` namespace calls, finds **78 distinct functions and 30 distinct explicitly imported types**. Principal call sites: `N/client/src/lib/walletApi.ts:1`, `paymentsApi.ts:1`, `exitApi.ts:1`, `crypto.ts:1`, `backupService.ts:3`, `hooks/useBackupCoordinator.ts:3`; other imports are type references and `components/WalletLoader.tsx:7`.

The published API-baseline's “128 exports” proves parity with its **prior UniFFI binding**, not with Nitro Ark. Its origin is `src/muniffi/wallet.rs`, commit `d3c0f38...` (`B/docs/api-baseline.json:2`). Missing Nitro features below are therefore compatible with that repo's “complete parity” claim.

Errors are `BarkError.message` containing the Rust error chain, rather than Nitro Promise rejections; host cancellation does not roll back submitted work (`B/docs/migration.md:57`).

Naming: `W` = an owned `Wallet`, `O` = its retained BDK `OnchainWallet`, `F` = top-level function. Both languages use the camelCase names below. Swift signatures were verified in committed `sdk/swift/Sources/BoltFFI/BarkBoltFfiBoltFFI.swift` (abbreviated **S**). Kotlin generated files are build outputs, not committed in this checkout; matching camelCase names/unsigned values are supported by `B/examples/android/src/main/java/io/github/niteshbalusu11/bark/example/WalletModel.kt:44`, `tests/kotlin/ContractTest.kt:45` and Rust export names. **Exact generated Kotlin signatures not exercised here**. Swift uses `UInt64`/`UInt32`, Kotlin `ULong`/`UInt`; do not carry JS floating-point satoshi arithmetic into the native implementation.

## 2. API mapping — every used function

### Wallet lifetime/configuration, balances, sync

| Nitro function(s) used by Noah | Swift / Kotlin equivalent | Difference / evidence |
|---|---|---|
| `createMnemonic` | `F.generateMnemonic()` | `B/src/functions.rs:11`; S:5746. |
| `createWallet` | `F.initWallet(network,mnemonicOrSeed,config,datadir,allowUnreachableServer)` then open; or `Wallet.openWithOnchain(..., WalletOpenArgs(createIfNotExists:true), O)` | `B/src/ffi/wallet.rs:82,120,130`. Native model is handles, not Nitro's global singleton. Create `O` with `OnchainWallet.default(network,mnemonic,config,datadir)` (`B/src/ffi/onchain.rs:31`). |
| `loadWallet` | `Wallet.openWithOnchain(..., WalletOpenArgs(datadir:original,createIfNotExists:false), O)` | Preserve original seed/network/datadir; `false` prevents missing old DB silently becoming a fresh wallet. Default is **true**, runDaemon default **true**, skipRecovery default **false** (`B/src/ffi/wallet.rs:19`). |
| `closeWallet` | `W.stopDaemonWait()`, cancel notification wait/task, release handles (Swift ARC); `.close()` notification/wallet/onchain wrappers in Kotlin | No global close equivalent. `B/docs/migration.md:63`, `src/ffi/wallet.rs:1059`; native examples `B/examples/ios/BarkExample/WalletModel.swift:40`, Android `WalletModel.kt:34`. Keep in-flight work ownership in mind; cancellation is not payment rollback. |
| `isWalletLoaded` | **App state**, e.g. owned wallet handle/opening state | No FFI global loaded query. Track lifecycle atomically in app service. |
| `getArkInfo` | `W.arkInfo()` | Optional when server information unavailable, rather than throwing solely for absence; shape changes below. `B/src/ffi/wallet.rs:639`; S:4102. |
| `refreshServer` | `W.refreshServer()` | `B/src/ffi/wallet.rs:846`; S:4807. |
| `offchainBalance` | `W.balance()` | Six matching monetary buckets renamed `...Sats`; `B/src/types.rs:126`, S:2819. |
| `onchainBalance` | `O.balance()` | Confirmed/pending/total instead of four BDK categories. `pendingSats` excludes immature; `totalSats` includes it. `B/src/types.rs:586`. |
| `sync` | `W.sync()` | `B/src/ffi/wallet.rs:151`; not a substitute for separately syncing `O`. |
| `onchainSync` | `O.sync()` | Returns total sats instead of void; `B/src/ffi/onchain.rs:56`. Recovery additionally needs `O.initialScan(birthdayHeight:0)`; regular sync scans only revealed addresses (`B/src/ffi/onchain.rs:66`). |

**Config unit trap:** Nitro `BarkConfigOpts.fallback_fee_rate` is sat/kvB (`R bark-cpp/src/utils.rs:160`); BoltFFI `Config.fallbackFeeRate` is sat/kwu (`B src/config.rs:101`). Noah's `10000` becomes `2500` to preserve 10 sat/vB. Native `FeeRates` values are also sat/kwu, while Nitro `BarkFeeRates` values are sat/vB. `Config` names map `ark→serverAddress`, `esplora→esploraAddress`, `bitcoind→bitcoindAddress`, `bitcoind_cookie→bitcoindCookiefile`; other names become camelCase. Config fields default nil/null and upstream network defaults; carry Noah's chosen values explicitly. Use typed `Network.bitcoin`/`Network.BITCOIN` for Noah's `mainnet` string. `B/src/config.rs:5`, `src/types.rs:14`; `R/react-native-nitro-ark/src/NitroArk.nitro.ts:7`.

### Addresses, Ark sends, receive subscriptions

| Nitro function(s) | Swift / Kotlin equivalent | Difference / evidence |
|---|---|---|
| `newAddress` | `W.newAddress()` or `W.newAddressWithIndex()` | String or `AddressWithIndex`, not `{user_pubkey,ark_id,address}`; missing parsed address components. `B/src/ffi/wallet.rs:179`; `R/bark-cpp/src/cxx.rs:720`. |
| `peekAddress` | `W.peekAddress(index)` | Returns string, not parsed components. `B/src/ffi/wallet.rs:189`; S:2796. |
| `onchainAddress` | `O.newAddress()` | `B/src/ffi/onchain.rs:109`; S:2368. |
| `onchainIsMine` | **Missing** BDK method | Callback protocol `isMine(scriptPubkeyHex)` lets the app implement its *own* onchain backend; it does not expose ownership queries on the built-in BDK wallet (`B/src/ffi/onchain.rs:28`; `tests/kotlin/ContractTest.kt:53`). |
| `validateArkoorAddress` | `W.validateArkoorAddress(address)` | Server-aware equivalent; don't substitute format-only `F.validateArkAddress`. `B/src/ffi/wallet.rs:506`, `src/functions.rs:31`. |
| `sendArkoorPayment` | `W.sendArkoorPayment(arkAddress,amountSats)` | Returns void. Nitro synthesized `{amount_sat,destination_pubkey}` from inputs; despite the name, `destination_pubkey` was the full address string. `R/bark-cpp/src/cxx.rs:1080`; `B/src/ffi/wallet.rs:493`. |
| `subscribeArkoorAddressMovements` | `W.notifications()` → `NotificationHolder.nextNotification()` loop, app filter | Reimplement Nitro's filter: Ark subsystem + matching `received_on` Ark destination; forward lag events. `R/bark-cpp/src/subscriptions.rs:186`; `B/src/core/notification.rs:77`, `src/types.rs:1606`. |
| `subscribeLightningPaymentMovements` | Same notification loop, payment-hash filter | Nitro restricts to Lightning send/receive and matches metadata/parsed invoice payment hash. Filter/read JSON destinations or new calculated `Movement.paymentHash`. `R/bark-cpp/src/subscriptions.rs:222`. One pending receive per holder; cancel using `cancelNextNotificationWait`, then release. `B/src/core/notification.rs:77,132`. |

### Lightning / BOLT11 / BOLT12 / LNAddress / LNURL

| Nitro function(s) | Swift / Kotlin equivalent | Difference / evidence |
|---|---|---|
| `payLightningInvoice` | `W.payLightningInvoice(invoice,amountSats,wait)` | **Argument order changes**: Noah calls Nitro `(destination,true,amountSat)`; B uses `(invoice,amountSats,wait)` and default wait=false. Returns `LightningSendStatus`, see types. `N/client/src/lib/paymentsApi.ts:240`; `B/src/ffi/wallet.rs:292`; S:3119. |
| `payLightningOffer` | `W.payLightningOffer(offer,amountSats,wait)` | Returns status enum, not rich Nitro result; `B/src/ffi/wallet.rs:332`; S:3244. |
| `payLightningAddress` | `W.payLightningAddress(lightningAddress,amountSats,comment,wait)` | `B/src/ffi/wallet.rs:303`; Noah passes wait=true (`N/client/src/lib/paymentsApi.ts:450`). Native raw LNURL-pay also exists as `W.payLnurl`; LNURL auth/withdraw are not this API (`B/src/ffi/wallet.rs:321`). |
| `checkLightningPayment` | `W.checkLightningPayment(paymentHash,wait)` | Advances action; read-only alternative `W.lightningSendState`. Both return enum. `B/src/ffi/wallet.rs:342,353`. |
| `bolt11Invoice` | `W.bolt11Invoice(amountSats,description,token)` | `LightningInvoice{invoice,paymentHash,amountSats}` replaces `{payment_request,payment_secret,payment_hash}`. `payment_secret` not exposed. `B/src/ffi/wallet.rs:394`; `src/types.rs:269`. |
| `tryClaimLightningReceive` | `W.tryClaimLightningReceive(paymentHash,wait)` | Same concept, flattened state names now hyphenated; `B/src/ffi/wallet.rs:462`, `src/types.rs:302`. |
| `tryClaimAllLightningReceives` | `W.tryClaimAllLightningReceives(wait)` | Returns receive list rather than Nitro void. `B/src/ffi/wallet.rs:432`; `R/bark-cpp/src/lib.rs:645`. |

Noah's LNURL-auth uses `peekKeyPair(0)` and `signMessage(k1,0)`, not an FFI LNURL-pay function (`N/client/src/lib/lnurlAuth.ts:42`). Server authentication also derives/signs without loading a wallet (`N/client/src/lib/api.ts:169`). These are blocked by the crypto omissions below. The FFI repo explicitly says external LNURL/LNAddress/BOLT12 provider acceptance was not run (`B/docs/migration.md:105`).

### Onchain spending, boarding and offboard

| Nitro function(s) | Swift / Kotlin equivalent | Difference / evidence |
|---|---|---|
| `onchainSend` | `O.send(address,amountSats,feeRateSatPerVb)` | Rate is **required**; Nitro defaults to regular. Read `O.feeRates().regularSatPerKwu`, convert/round explicitly to integer sat/vB. Returns txid; app can compose amount/destination result. `B/src/ffi/onchain.rs:121`; `R/bark-cpp/src/cxx.rs:2166`. |
| `onchainDrain` | **Missing** built-in BDK drain | Do not emulate “send all” by subtracting a guessed fee from balance. `R/bark-cpp/src/onchain.rs:91`; `B/src/ffi/onchain.rs:28` has no drain export. |
| `sendOnchain` | `W.sendOnchain(address,amountSats)` | Ark-funded onchain send; returns txid, app composes summary. `B/src/ffi/wallet.rs:511`. |
| `onchainTransactions` | `O.transactions()` | Optionals replace `has_*` booleans; adds `isCpfp`. `B/src/ffi/onchain.rs:191`, `src/types.rs:633`. |
| `boardAmount`, `boardAll` | `W.boardAmount(amountSats)`, `W.boardAll()` | Return `PendingBoard`: only **first** VTXO id retained, amount and txid. Nitro returned all board VTXO IDs; not full parity for multi-VTXO boards. `B/src/ffi/wallet.rs:652,657`, `src/types.rs:715`; `R/bark-cpp/src/cxx.rs:1050`. |
| `syncPendingBoards` | `W.syncPendingBoards()` | `B/src/ffi/wallet.rs:679`. |
| `offboardAll` | `W.offboardAll(bitcoinAddress)` | Returns `OffboardResult{txid}` rather than string. `B/src/ffi/wallet.rs:271`, `src/types.rs:281`. |

### VTXOs, refresh, maintenance, history

| Nitro function(s) | Swift / Kotlin equivalent | Difference / evidence |
|---|---|---|
| `vtxos` | `W.vtxos()` | New rich lock state but missing old detail fields; below. `B/src/ffi/wallet.rs:202`, `src/types.rs:229`. |
| `getExpiringVtxos` | `W.getExpiringVtxos(thresholdBlocks)` | `B/src/ffi/wallet.rs:220`. |
| `decodeVtxoHex` | **Missing** pure decoder | `W.importVtxo` mutates persistence and contacts server; not an acceptable preview replacement. `R/bark-cpp/src/lib.rs:747`; `B/src/ffi/wallet.rs:904`. |
| `importVtxo` | `W.importVtxo(vtxoBase64,args:nil/null)` or `W.importVtxos([hex],args)` + `W.getVtxoById(id)` | Historical parameter name accepts hex **and** base64. Single import returns void; batch returns ids. `B/src/ffi/wallet.rs:893,904,920`. |
| `dangerousDropVtxo` | **Missing** | Explicit recovery/debug operation; `R/bark-cpp/src/lib.rs:768`. |
| `unlockVtxos` | `W.unlockVtxos(vtxoIds,expectedHolder:nil/null)` | Passing nil preserves Nitro's ownerless-lock-only release. Don't weaken owner matching. `R/bark-cpp/src/lib.rs:780`; `B/src/ffi/wallet.rs:258`. |
| `maintenance`, `maintenanceDelegated` | `W.maintenance()`, `W.maintenanceDelegated()` | `B/src/ffi/wallet.rs:165,170`. |
| `maintenanceRefresh` | `W.maintenanceRefresh()` | Returns optional txid; Nitro discarded value. `B/src/ffi/wallet.rs:549`; `R/bark-cpp/src/lib.rs:697`. |
| `refreshVtxosDelegated` | `W.refreshVtxosDelegated(vtxoIds)` | Optional `RoundState` instead of optional `{round_id}`. `B/src/ffi/wallet.rs:554`. |
| `syncPendingRounds` | **Partial:** `W.progressPendingRounds()` + `W.pendingRoundStates()` | Not equivalent: old operation syncs and returns individual `RoundStatus` outcomes, funding txids, errors/final/success. New progression returns void; RoundState is only pending lifecycle/id/scheduled height. `B/src/ffi/wallet.rs:822,837`; `src/types.rs:1363`; `R/bark-cpp/src/lib.rs:1128`, `cxx.rs:2004`. |
| `history` | `W.history()` | All movements remain available but payload loses per-destination amounts; JSON serialized payment methods replace destination objects. `B/src/ffi/wallet.rs:524`, `src/types.rs:445`. |
| `updateHistoryMetadata` | **Missing** | Needed for existing movement annotations. `R/bark-cpp/src/lib.rs:727`; `N/client/src/lib/paymentsApi.ts:506`. |

### Fees

| Nitro function(s) | Swift / Kotlin equivalent | Difference / evidence |
|---|---|---|
| `onchainFeeRates` | `O.feeRates()` | **sat/vB → sat/kwu**, divide by 250 (`B/src/types.rs:606`). |
| `estimateArkoorPaymentFee` | `W.estimateArkoorPaymentFee(amountSats)` | `B/src/ffi/wallet.rs:982`. |
| `estimateLightningSendFee` | `W.estimateLightningSendFee(amountSats)` | `B/src/ffi/wallet.rs:966`. |
| `estimateBoardOffchainFee` | `W.estimateBoardFee(amountSats)` | Board's offchain/server fee, not full funding transaction estimate. `B/src/ffi/wallet.rs:941`. |
| `estimateRefreshFee` | `W.estimateRefreshFee(vtxoIds)` | `B/src/ffi/wallet.rs:958`. |
| `estimateSendOnchain` | `W.estimateSendOnchainFee(address,amountSats)` | `B/src/ffi/wallet.rs:998`. |
| `estimateOffboardAll` | `W.estimateOffboardAllFee(address)` | `B/src/ffi/wallet.rs:990`. |
| `estimateEmergencyExitFee` | `W.estimateEmergencyExitFee(vtxoIds,feeRateSatPerVb,destination,feeMargin:nil/null)` | Nitro rate is **sat/kvB**, new integer rate **sat/vB**; new optional feeMargin defaults upstream 1.2; `fundable` removed. Compute UI affordability from confirmed onchain funds and broadcast fee. `B/src/ffi/wallet.rs:1007`, `src/types.rs:549`; `R/bark-cpp/src/cxx.rs:1566`. |

### Unilateral exits

| Nitro function(s) | Swift / Kotlin equivalent | Difference / evidence |
|---|---|---|
| `startExitForVtxos` | `W.startExitForVtxos(vtxoIds)` | Nitro preselected spendable + pending-round VTXOs; new core loads all requested IDs and passes to upstream exit manager. Preserve app eligibility checks and test negative cases. `R/bark-cpp/src/exit.rs:23`; `B/src/core/wallet.rs:1332`. |
| `cancelExit` | `W.cancelExit(vtxoId)` | Returns `ExitCancelResult{canceled,reason}`; no throw for expected refusal. Check `canceled`. `B/src/types.rs:1204,1222`; `src/ffi/wallet.rs:751`. |
| `syncExit` | `W.syncExits()` | `B/src/ffi/wallet.rs:713`. |
| `progressExits` | `W.progressExits(feeRateSatPerVb)` | Old optional sat/kvB → integer sat/vB. N currently syncs exits first; preserve ordering (`N/client/src/lib/exitApi.ts:110`). `B/src/ffi/wallet.rs:718`; `R/bark-cpp/src/cxx.rs:1537`. |
| `getExitVtxos` | `W.getExitVtxos()` | Less aggregate detail: fetch `getExitStatus(id,true,...)` for history. `B/src/ffi/wallet.rs:761`, `src/types.rs:1249`. |
| `listClaimable` | `W.listClaimableExits()` | Same reduced ExitVtxo. `B/src/ffi/wallet.rs:756`. |
| `getExitStatus` | `W.getExitStatus(vtxoId,includeHistory,includeTransactions)` | Typed state/history but **transactionCount only**, not old parent/child transaction hex packages. `B/src/ffi/wallet.rs:781`, `src/types.rs:1297`. |
| `hasPendingExits` | `W.hasPendingExits()` | `B/src/ffi/wallet.rs:766`. |
| `pendingExitTotal` | `W.pendingExitsTotalSats()` | `B/src/ffi/wallet.rs:771`. |
| `allClaimableAtHeight` | `W.allExitsClaimableAtHeight()` | Optional UInt32/UInt. `B/src/ffi/wallet.rs:776`. |
| `drainExits` | `W.drainExits(vtxoIds,drainAll:false,address,feeRateSatPerVb)` | Returns `ExitClaimTransaction{psbtBase64,feeSats}`, not string. Explicit drainAll flag; no ids + false errors. New code skips well-formed unclaimable ids, unlike Nitro loading each requested exit. `B/src/ffi/wallet.rs:799`, `src/core/wallet.rs:1483`; `R/bark-cpp/src/exit.rs:225`. |
| `extractTransaction` | `F.extractTxFromPsbt(psbtBase64)` | `B/src/functions.rs:51`. Follow new examples' claim sequence: drain → `W.signExitClaimInputs(psbtBase64)` → extract → broadcast, rather than assume the old two-call flow remains enough. B iOS example `WalletModel.swift:154`, Android `WalletModel.kt:94`. |
| `broadcastTransaction` | `W.broadcastTx(txHex)` | Returns txid. `B/src/ffi/wallet.rs:869`. |

### Crypto, mailbox, persistence hooks

| Nitro function(s) | Swift / Kotlin equivalent | Difference / evidence |
|---|---|---|
| `signMessage` | **Missing** wallet-key message signer | `R/bark-cpp/src/lib.rs:536`. |
| `verifyMessage` | **Missing** verifier | `R/bark-cpp/src/lib.rs:590`. |
| `peekKeyPair` | **Missing** deterministic indexed key lookup | `R/bark-cpp/src/lib.rs:488`. |
| `deriveStoreNextKeypair` | **Missing** derivation + persisted index allocation | `R/bark-cpp/src/lib.rs:476`. |
| `signMesssageWithMnemonic` | **Missing** offline mnemonic signer (Nitro spelling has three s's) | `R/bark-cpp/src/lib.rs:558`. |
| `deriveKeypairFromMnemonic` | **Missing** offline indexed derivation | `R/bark-cpp/src/lib.rs:577`. |
| `mailboxAuthorization` | `W.mailboxAuthorization(expirySecs)` + `W.mailboxIdentifier()` | **Present**. Old input = absolute Unix seconds; new = duration seconds from now; result only hex string. New expiry calculation uses internal current time, so app-estimated expiry can differ. Add structured result or absolute-expiry overload if server contract needs exact timestamp. `B/src/ffi/wallet.rs:878,886`, `src/core/wallet.rs:1651,1898`; `R/bark-cpp/src/cxx.rs:2035`. |
| `createWalletSnapshot` | **Missing** consistent DB export | `R/bark-cpp/src/backup.rs:45,236`; never copy an actively written SQLite main file alone. |
| `validateWalletSnapshot` | **Missing** integrity/identity/migration validation | `R/bark-cpp/src/backup.rs:54,116`; existing validator migrates a temporary copy and loads wallet rows, not just `PRAGMA integrity_check`. |
| `subscribeWalletStateChanges` | **Missing** whole-DB committed-state stream | Nitro polls independent read-only SQLite connection `PRAGMA data_version` every 750ms and emits initial/databaseChanged/resyncRequired. Wallet movement notifications are not equivalent; non-movement state also needs backup. `R/bark-cpp/src/state_changes.rs:14,27,141`; `N/client/src/hooks/useBackupCoordinator.ts:3`. |

## Every explicitly imported type

| Nitro imported type(s) | BoltFFI type / required app model |
|---|---|
| `BarkConfigOpts` | `Config`; config rename/unit mapping above. `B/src/config.rs:5`. |
| `BarkCreateOpts` | `Network` + mnemonic string + `Config` + `WalletOpenArgs`; birthday handled through `O.initialScan`, not this struct. `B/src/ffi/wallet.rs:19`, `src/ffi/onchain.rs:75`. |
| `BarkArkInfo` | `ArkInfo`: typed network, `roundIntervalSecs`, optional `maxVtxoAmountSats`, new fee schedule/max exit depth; **mailboxPubkey missing**. `B/src/types.rs:874`. |
| `BarkVtxo` | `Vtxo`: `amount→amountSats`; `state` is `VtxoState` carrying lock owner; adds kind/exitDepth/exitTxWeightWu/registered; **serverPubkey, exitDelta, anchorPoint, point missing**. `B/src/types.rs:229`; `R/bark-cpp/src/utils.rs:377`. Noah uses those for identity/comparison/explorer/details (`N/client/src/screens/VTXODetailScreen.tsx:125,133,287`). |
| `OffchainBalanceResult` | `Balance`: six same buckets, each with Sats suffix. `B/src/types.rs:126`. |
| `OnchainBalanceResult` | `OnchainBalance`: confirmed/pending/total; cannot recover trusted vs untrusted split. Immature can be inferred `total-confirmed-pending`; retain Noah's totals/pending policy deliberately (`N/client/src/lib/balanceUtils.ts:18`). `B/src/types.rs:586`. |
| `NewAddressResult` | String or `AddressWithIndex{address,index}`; no userPubkey/arkId. `B/src/types.rs:291`. |
| `KeyPairResult` | **Missing**. Prefer exposing public key and signing operations; Noah does not need raw private key export merely because the old DTO had it. `R/react-native-nitro-ark/src/NitroArk.nitro.ts:246` (under `react-native-nitro-ark/src`). |
| `ArkoorPaymentResult` | App receipt from request inputs + movement reconciliation (FFI returns void). |
| `OnchainPaymentResult` | App `{txid,amountSats,destinationAddress}` receipt, FFI gives txid only. |
| `BoardResult` | `PendingBoard{vtxoId,amountSats,txid}`; only first VTXO ID survives. `B/src/types.rs:715`. |
| `Bolt11Invoice` | `LightningInvoice{invoice,paymentHash,amountSats}`; no payment secret. `B/src/types.rs:269`. |
| `LightningPayment` | `LightningSendStatus` = Unknown/InProgress(send)/Paid(paymentHash,preimage); `LightningSend` gives invoice/amount/fee/HTLC count/hasFailedRevocation. Nitro custom `revocation_stuck` corresponds to InProgress with hasFailedRevocation, not another enum case. No movementId or actual HTLC list; Paid omits invoice/amount. `B/src/types.rs:377,413`; `R/bark-cpp/src/utils.rs:65`. |
| `LightningReceive` | Same-named type but state now `awaiting-payment`, `htlcs-ready`, `preimage-revealed`, `delivering`, `settled`; replaces old in_progress+underscore phase discriminant. Optional preimage/amount/settledAt; new claimDestination; drops HTLC ids/movementId. `B/src/types.rs:302`; `R/bark-cpp/src/lib.rs:138`. |
| `BarkFeeEstimate` | `FeeEstimate` (`grossAmountSats`, `feeSats`, `netAmountSats`, `vtxosSpent`). `B/src/types.rs:518`. |
| `BarkFeeRates` | `FeeRates` (`fastSatPerKwu`, `regularSatPerKwu`, `slowSatPerKwu`), not sat/vB. `B/src/types.rs:613`. |
| `OnchainTransactionInfo` | `WalletTransaction`: optional onchainFeeSats/confirmation instead of has flags; adds isCpfp; signed balanceChangeSats. `B/src/types.rs:633`. |
| `BarkMovement` | `Movement`: subsystem split to two fields; monetary names plural Sats; destinations JSON strings in sentToAddresses/receivedOnAddresses; **destination amounts lost**; adds calculated paymentHash/lightningInvoice/lightningOffer. `B/src/types.rs:445`. |
| `MovementStatus` | No native enum: `Movement.status` string; app enum validating pending/successful/failed/canceled. `B/src/types.rs:447`; `R/react-native-nitro-ark/src/index.tsx:56` (under `react-native-nitro-ark`). |
| `BarkNotificationEvent` | `WalletNotification` associated-value enum MovementCreated/MovementUpdated/ChannelLagging. `B/src/types.rs:1606`. |
| `DelegatedRoundState` | `RoundState`; id plus ongoing/state/scheduledHeight. `B/src/types.rs:1363`. |
| `PendingRoundStatus` | **No complete equivalent**; RoundState omits final outcome/error/funding txids. `B/src/types.rs:1363`. |
| `ExitFeeEstimate` | `EmergencyExitFeeEstimate`; Sats suffixes, `fundable` removed. `B/src/types.rs:549`. |
| `ExitProgressState`, `ExitStateDetails` | `ExitStateKind` and associated-value `ExitState`; new VtxoSwept(spentInputs) must be handled in UI. `B/src/types.rs:1060,1166`. |
| `ExitProgressStatusResult` | `ExitProgressStatus{vtxoId,state,error}`; state already contains details. `B/src/types.rs:1270`. |
| `ExitVtxoResult` | `ExitVtxo{vtxoId,amountSats,state,isClaimable}`; fetch history separately; derive or extend initialized/txid details. `B/src/types.rs:1249`. |
| `ExitStatusResult` | `ExitTransactionStatus{vtxoId,state,history?,transactionCount}`; **no transaction packages**. `B/src/types.rs:1297`. |
| `WalletSnapshotInfo` | **Missing**, needs snapshot DTO with path/size/hash/network/fingerprint/server/mailbox keys/schemaVersion. `R/react-native-nitro-ark/src/NitroArk.nitro.ts:324`. |
| `WalletStateChangeEvent` | **Missing** DB revision event; do not equate with WalletNotification. `R/react-native-nitro-ark/src/index.tsx:181`; `bark-cpp/src/state_changes.rs:27`. |

## Behavior Nitro currently owns that must be deliberately ported

1. **Identity/signature compatibility is a release blocker.** Offline derivation is BIP39 `to_seed("")`, BIP32 network master → child **350** → requested index; for indices below 2³¹ these are non-hardened. **Loaded-wallet** `peekKeyPair`/`signMessage` instead use upstream wallet derivation **m/350'/0'/index** (`U/bark/src/lib.rs:691`); do not unify the two derivation paths or server and LNURL identities will change. Indexed wallet signing uses `peek_keypair`. It hashes the *message string* using Bitcoin `signed_msg_hash` and signs ECDSA, returning DER-hex `Signature.to_string()`, with compressed public-key string. This is **not** “sign the raw decoded 32-byte k1” and not Schnorr. Match current server auth exactly; changing it changes verification/account identity. `R/bark-cpp/src/lib.rs:111,536,558,577,590`, `cxx.rs:742,803`; `N/client/src/lib/api.ts:178`, `lnurlAuth.ts:42`. Add fixed public test vectors for both distinct mnemonic and loaded-wallet methods before native rollout.
2. **Rich history destinations:** Nitro converts each PaymentMethod into `{payment_method,destination,amount_sat}`; B serializes PaymentMethod alone to JSON and drops destination amount. UI repeat-payment and receive confirmation currently consume these fields; receive sums matching destination amounts, so don't substitute the entire movement balance for each destination. `R/bark-cpp/src/utils.rs:433,452`; `B/src/types.rs:477`; `N/client/src/hooks/useReceiveRequest.ts:205`.
3. **Lightning enrichment:** Nitro queries send status then joins invoice, requested amount, HTLC VTXO objects, movement id and preimage; native status enum is intentionally narrower. Preserve submitted destination/amount until history reconciliation; map stuck revocation correctly. `R/bark-cpp/src/utils.rs:65`, `lib.rs:976`; `B/src/types.rs:377,413`.
4. **Subscriptions/lifetime:** Nitro starts a mailbox processor with retry on load and exposes filtered movement subscriptions plus whole-DB change polling; B has daemon + notification holders. Design one app wallet owner; preserve background sync/backup coordination and cancel subscriptions before replacing wallet. `R/bark-cpp/src/lib.rs:222`, `mailbox.rs:1`, `subscriptions.rs:186`, `state_changes.rs:14`; `B/src/ffi/wallet.rs:19,1035`, examples cited above.
5. **Units and loss of aggregate DTO fields:** board returns, round statuses, exit history/package projection, balance categories and network/fee conversions need explicit mapping. Preserve newly typed lock ownership and cancellation refusal rather than flattening them back into false success.

## 3. Concrete TODOs for bark-bolt-ffi

**Release-blocking for existing Noah features:**

- Add compatible indexed public-key derivation, persisted next-key allocation, wallet/offline mnemonic ECDSA message signing and verification (six missing Nitro functions); preserve both distinct paths (offline m/350/index; loaded wallet m/350'/0'/index), account identity and signature serialization. Do not require export of secret keys to Swift/Kotlin.
- Add consistent SQLite snapshot export and snapshot validation with identity/schema expectations, temporary migration validation, checksum and size. Add whole-wallet committed-state change notifications suitable for backup (not movement-only). Reuse/port Nitro's tested SQLite backup+`data_version` semantics.
- Expose movement metadata updates and **structured typed destination + amount** lists without dropping old persisted metadata/history detail.
- Expose BDK address/script ownership check and true drain/send-all using exact coin selection/fee construction.
- Expose safe pure VTXO decoding and old VTXO detail fields; expose dangerousDropVtxo only as an explicitly dangerous operation matching the current troubleshooting feature.
- Expose sync/progress pending-round **results** (status, funding txids, unsigned txids, error, terminal/success flags), or an equivalent persistent query so the pending-round banner can reconcile completion.

**Parity additions or app decisions needed:**

- Preserve every board VTXO id (current `PendingBoard` uses `.first()`), detailed exit transaction package information/history access, and complete ArkInfo identity fields (mailbox pubkey).
- Provide precise mailbox auth `{mailboxId,expiry,encoded}` or absolute-expiry overload; core authorization already exists, do not claim it is missing.
- Expose parsed Ark-address components if the native UX still needs userPubkey/arkId. Decide whether to expose split BDK balance categories and invoice payment secret; current total balance can be preserved with existing total/confirmed fields, and no explicit Noah use of `payment_secret` was found beyond retaining the imported type.
- If preserving Nitro's optional invoice-origin feature later, add invoice payment with an externally resolved PaymentMethod origin. **Not in the 78 currently used functions**: current `client/src` calls plain payLightningInvoice, not payLightningInvoiceWithOrigin. Keep this separate from required parity.

Unverified: compiled generated Kotlin signatures, live provider behavior, and on-device upgrade execution. Repo reports provider acceptance limits itself; no claim here that passing its current smoke suite verifies Noah's missing paths.

## 4. Regtest October 19 upgrade

### Exact minimum and optional changes

| Item | Noah today | Required/optional October 19 action | Evidence |
|---|---|---|---|
| Bitcoin Core | `bitcoin/bitcoin:30` | **Upgrade to 31 or later**; FFI pins `bitcoin/bitcoin@sha256:da25cedc66b1daefff9f412ee196c901a899c3fa68a33b20849c3e08b5c40d63`. captaind master explicitly rejects Core <31 | N/scripts/docker-compose.yml:3; B/regtest/compose.yaml:4; U/server/src/bitcoind.rs:31,231 |
| captaind image | `niteshbalusu/captaind:nightly-2026-09-04` | Change to `nightly-2026-10-19@sha256:42b7bb01c7e7e9872f730a31c3132f5b0678dacca11b483d7309c64c86c5f649` | N/scripts/docker-compose.yml:77; B/regtest/compose.yaml:35 |
| bark image | `niteshbalusu/bark:nightly-2026-09-04` | Change to `nightly-2026-10-19@sha256:6ddad8bd07a21096d8487b8caa1825185f43b9d8ea1a2f2644bd82206a7122ba` | N/scripts/docker-compose.yml:96; B/regtest/compose.yaml:43 |
| root `watchman_min_balance` | `"0 sat"` | **Delete: master startup explicitly rejects the key**, regardless of value | N/scripts/captaind.toml:45; U/server/src/config.rs:518-522 |
| `[watchman]` | `enabled = false` | **Delete entire section: master startup explicitly rejects it**, even disabled | N/scripts/captaind.toml:172-173; U/server/src/config.rs:510-517 |
| `vtxopool.issue_interval` | `"1m"` | Delete obsolete key; no corresponding master field, serde does not deny unknown fields here. It is not a demonstrated startup blocker | N/scripts/captaind.toml:248; U/server/src/vtxopool.rs:60-77 |
| captaind config path/mount | `/data/captaind/captaind.toml` | May retain current mount. FFI chooses `/config/captaind.toml` plus `CAPTAIND_CONFIG_PATH`; not a mandatory change. Local inspected Oct19 image defaults env to Noah's existing path | N/scripts/docker-compose.yml:79-80; B/regtest/compose.yaml:36-37; local `docker image inspect ... --format '{{json .Config}}'` on 2026-10-19 |
| bark entrypoint/commands | default image Cmd run.sh; commands include explicit `bark` | Existing explicit `dcr run ... bark ...` overrides Cmd, so no CLI rename required. If copying FFI `entrypoint: [bark]`, remove the duplicated `bark` executable argument everywhere | N/scripts/ark-dev.sh:95-101,318,327,426; B/regtest/compose.yaml:44; B/scripts/ark-dev.sh:15,72-74 |
| reference wallet volume/path | `/root`, default `/root/.bark` | No requirement to switch to FFI `/wallets/reference`; keep datadir stable or explicitly migrate it. Do not copy FFI volume change without updating CLI datadir and preserving old data | N/scripts/docker-compose.yml:98; N/scripts/ark-dev.sh:91-101; B/regtest/compose.yaml:46; B/scripts/ark-dev.sh:15 |

There are **no config key renames in the actual two-file diff**. Remaining semantic differences are optional test-policy tuning, not requirements merely because the FFI fixture chose them:

| Setting | Noah | FFI fixture |
|---|---:|---:|
| root `vtxo_lifetime` | 480 | 288 |
| `vtxo_exit_delta` | 144 | 12 |
| `required_board_confirmations` | 3 | 1 |
| `min_trusted_confs` | 2 | 1 |
| `round_interval` | 30s | 5s |
| `fees.offboard.fixed_additional_vb` | 212 | 221 |
| `cln_array[0].priority` | 1 | 10 |

Sources: N/scripts/captaind.toml:6,15,28,31,68,193,290; B/regtest/captaind.toml:4,6,9,10,16,46,103. Other fees, VTXO pool targets/lifetimes/depth, RPC addresses, PostgreSQL/bitcoind settings, CLN certificates and hold-invoice endpoints are semantically equal. FFI additionally pins Bitcoin/Postgres/CLN/LND by digest, adds CLN healthcheck/dependency, uses different localhost ports 19443/3635, `cli` profile, isolated volumes, and lacks Noah server/barkd/electrs/Dragonfly. These are fixture-isolation/reproducibility choices; do not wholesale replace Noah Compose.

`ark-dev.sh` create flags (`--regtest --ark --bitcoind --bitcoind-user --bitcoind-pass`), funding `.rounds.address`, onchain address, and board command still have current FFI script equivalents. No source evidence requires renaming these. FFI avoids Noah's existing destructive `rm -rf /root/.bark`/`--force` setup and instead checks for mnemonic before creating. Preserve existing test wallets when upgrading; do not run Noah `down` expecting preservation, because it always appends `--volumes` (N/scripts/ark-dev.sh:91-101,385; B/scripts/ark-dev.sh:65-76). This research did not change/restart any stack or run migration against existing server volumes.

The FFI repository documents both Oct19 images reporting bfbe6b7c and a fresh-stack paid/boarded integration run; that is **their recorded validation**, not independently repeated here. Source: B/docs/validation/2026-10-19-master.md:9-23. Config header B/regtest/captaind.toml:1 still says derived from 0.7.1; the pinned image and current source are authoritative.

### Does noah-barkd need a matching bump?

**No mandatory matching bump is established by the inspected source; runtime interoperability remains unverified.** Noah's `noah-barkd:0.7.1` is a wrapper around the upstream **release** binary, downloaded with pinned architecture SHA-256s, not Noah's September nightly. Source: N/fly/barkd.Dockerfile:5-22; N/.github/workflows/barkd-build-push.yml:10-11; N/scripts/docker-compose.yml:169. Bark release tag `bark-0.7.1` resolves to 1d1e4c89, and already advertises protocol minimum 4 / maximum 5 (B at tag:server-rpc/src/lib.rs:117,122; pver.rs:24,39). bfbe6b7c still supports 4..5 (U/server-rpc/src/lib.rs:119,124). Although old protocol<5 Lightning/round calls are rejected, release 0.7.1 already supports 5; do not infer incompatibility from the `0.7.1` label or the stale protocol changelog text.

Noah uses only GET `api/v1/wallet/ark-info` expecting `{network}`, and POST `api/v1/lightning/receives/invoice/for-address` with `{address,amount_sat,description}` expecting `{invoice}`, both with Bearer auth; it validates invoice network and exact msat amount. Those specific REST contracts remain present unchanged in current master. Sources: N/server/src/barkd_client.rs:13-14,115-130,134-217; U/bark-rest/src/api/v1/lightning.rs:84-115; U/bark-rest/src/api/v1/wallet.rs:37,308; tag-to-master diff of those handlers. Master adds recovery/batch RPCs and optional send retry fields; the forwarding contract does not call them.

Before retaining 0.7.1, run end-to-end readiness -> forwarding invoice -> LND payment -> native recipient mailbox sync -> delivered VTXO, including barkd restart while forward is pending. A successful `/ark-info` alone cannot prove delivery. If choosing exact master alignment or if these checks fail, build/publish a Noah barkd image pinned to bfbe6b7c and point `BARKD_IMAGE` to it. Updating only the tag/version build argument is insufficient: the Dockerfile downloads a named release URL and verifies fixed per-architecture checksums, so new source/binary URLs and checksums plus build workflow version/tag need corresponding updates. No new `noah-barkd` tag existence or runtime result was verified here.

## Verification limits

Checked source pins, exact production migration diff, all 108 imported/namespace-used symbols, native backup code, generated Swift declarations, Kotlin examples/contracts, regtest config diff, and local October 19 image configuration. No Noah repository files changed (`git status --porcelain` remained empty). No builds, installed-app upgrade, real backup cross-decryption, live transactions, or existing-volume server upgrade were run; binary provenance of the distributed Nitro 0.0.144 libraries was not independently verified beyond its source tag. These remain explicit release checks.
