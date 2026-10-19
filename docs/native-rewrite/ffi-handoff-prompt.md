# Task: add the APIs Noah's native apps need to bark-bolt-ffi

Repo: https://github.com/smolcars/bark-bolt-ffi (BoltFFI 0.31.1, Bark master `bfbe6b7c`). Read `AGENTS.md`, `docs/migration.md` and `docs/api-baseline.json` first.

## Context

Noah (https://github.com/smolcars/noah) is replacing its React Native app with native Swift (iOS) and Kotlin (Android) apps that use this SDK. The RN app used react-native-nitro-ark **v0.0.144**: https://github.com/smolcars/react-native-nitro-ark/tree/v0.0.144. Its Rust lives in `bark-cpp/src/`, and the C++ bridge is `bark-cpp/src/cxx.rs`. The native apps ship as in-place updates of the existing apps, so they have to keep Nitro's behaviour exactly: the same keys, the same signatures and the same snapshot format. Every item below is something Noah uses from Nitro that BoltFFI doesn't expose yet. Each one names the Nitro reference implementation.

## Ground rules

- Add Rust exports and regenerate the bindings. Never patch generated files. Keep `api-baseline.json` and `scripts/check-api.py` up to date.
- Every new API gets Rust tests plus Swift and Kotlin contract tests, and the Kotlin has to actually compile.
- Use golden vectors for the crypto items. Fix a public test mnemonic (the BIP39 `abandon … about` vector), compute the expected public keys and signatures with Nitro v0.0.144's code (or the identical pure-Rust derivation), and assert byte-identical output from Rust, Swift and Kotlin. secp256k1 ECDSA signing is RFC 6979 deterministic, so the signature hex must match exactly.
- Never return private keys across the FFI. Noah never reads `secret_key`, so public key plus sign/verify APIs are enough.
- Never log secrets or mnemonics.
- Amounts are `u64` sats. State every fee-rate unit explicitly in the docs.
- Make small commits, with one PR per priority group. Finish with a tagged release (e.g. `v0.2.0`) that ships the XCFramework, AAR, checksums and SPM manifest, so Noah can pin it.

## P0: blocks Noah's first native milestone and backups

### 1. Keys and message signing (two derivation paths; do NOT unify them)

Nitro reference: `bark-cpp/src/lib.rs:476-600` and `bark-cpp/src/cxx.rs:410-425, 700-815`.

Wallet-bound, used for LNURL-auth. This uses Bark's `Wallet::peek_keypair(index)`, which derives `m/350'/0'/index`: hardened purpose 350, hardened `VTXO_KEYS_INDEX = 0`, then a non-hardened index. It errors if the index hasn't been stored in the DB yet; keep that behaviour.
- `Wallet.peekPublicKey(index: UInt32) -> String`: compressed public key hex, i.e. `PublicKey::to_string()`.
- `Wallet.deriveStoreNextKeypair() -> { publicKey: String, index: UInt32 }`: wraps `derive_store_next_keypair` and persists the new key.
- `Wallet.signMessage(message: String, index: UInt32) -> String`: ECDSA over `bitcoin::sign_message::signed_msg_hash(message)` with the peeked key. Return the DER hex from `ecdsa::Signature::to_string()`.

Offline from the mnemonic, with no wallet loaded. Noah's server JWT login uses this at index 0, and so does the restore flow. The path is **`m/350/index`, non-hardened**: `Xpriv::new_master(network, &mnemonic.to_seed(""))`, then `.derive_priv(&[350.into()])`, then `.derive_priv(&[index.into()])`.
- `derivePublicKeyFromMnemonic(mnemonic: String, network: Network, index: UInt32) -> String`
- `signMessageWithMnemonic(message: String, mnemonic: String, network: Network, index: UInt32) -> String`: same hash and DER hex as above. Nitro misspells this `signMesssageWithMnemonic`; use the correct spelling.

Verification:
- `verifyMessage(message: String, signatureDerHex: String, publicKeyHex: String) -> Bool`

Tests:
- indices 0, 1 and 7
- what `index.into()` does for indices ≥ 2³¹ (it becomes hardened); document it
- all three networks
- the wallet path and the offline path give DIFFERENT keys for the same index (assert this)
- each path matches Nitro's vectors
- sign → verify round trip, plus verify rejecting a tampered message

### 2. Wallet snapshot export and validation (Noah backup format v2)

Nitro reference: `bark-cpp/src/backup.rs` (`create_wallet_snapshot`, `validate_wallet_snapshot`, `WalletSnapshotInfo`, `WalletSnapshotExpectation`).

`Wallet.createSnapshot(destinationPath: String) -> WalletSnapshotInfo { path, sizeBytes, sha256, network, walletFingerprint, serverPubkey?, mailboxPubkey?, schemaVersion }`:
- Use the SQLite Online Backup API: 128 pages per step, a 10 ms pause between steps, and a 30 s continuous-busy timeout.
- Serialize snapshots with a process-wide lock.
- Refuse to overwrite the destination, and refuse a destination that aliases the live DB.
- Checkpoint the destination's WAL, run `PRAGMA integrity_check` and `foreign_key_check`, fsync, then publish atomically without overwriting.
- `sha256` is the lowercase hex of the plaintext `db.sqlite`.
- `mailboxPubkey` is the **server** mailbox pubkey, as in Nitro.

`validateWalletSnapshot(path: String, expected: { network?, walletFingerprint?, serverPubkey? }?) -> WalletSnapshotInfo` (top-level):
- Must work whether or not a wallet is loaded, and must never alias or mutate the live DB or the input file.
- Check integrity, foreign keys and a supported schema version.
- Migrate a **temporary copy**, then load VTXOs, movements, round states, action checkpoints, exits and BDK state to prove it opens.
- Compare its identity against `expected`.
- Must accept the `db.sqlite` inside v2 `.noahbackup` files Noah has already produced (schema 42).

### 3. Notifications for committed wallet-state changes

Nitro reference: `bark-cpp/src/state_changes.rs`.

Nitro polls `PRAGMA data_version` every 750 ms on an independent read-only connection and emits `initial`, `databaseChanged` and `resyncRequired`. Noah uses this to trigger backups after ANY committed DB change, not just movements. Provide the same thing as a BoltFFI subscription object:
- an async `nextChange()`, with one pending wait per holder
- explicit cancel and stop, and `isActive`
- behaviour consistent with the existing `notifications()` holder

## P1: needed for feature parity

4. **Movement metadata and amounts**
   - Add `Wallet.updateHistoryMetadata(movementId: UInt32, patchJson: String)`. Reference: `lib.rs:727`, `parse_history_metadata_patch`.
   - Make `Movement` destinations structured `{ paymentMethod, amountSats }` instead of JSON-string payment methods. Noah's receive-completion logic sums destination amounts.
5. **Onchain**
   - `OnchainWallet.isMine(address) -> Bool`. Reference: `cxx.rs:551`.
   - `OnchainWallet.drain(address, feeRate?) -> txid`, a true BDK drain. Reference: `cxx.rs:562` and `onchain.rs`.
6. **VTXOs**
   - A pure `decodeVtxo(hexOrBase64) -> Vtxo` with no DB or network access. Reference: `lib.rs:747`.
   - Add the `Vtxo` fields Nitro exposed: server pubkey, exit delta, anchor point and point. See `utils::vtxo_to_bark_vtxo`.
   - Add `Wallet.dangerousDropVtxo(id)`. Reference: `lib.rs:768`.
7. **Pending rounds:** `Wallet.syncPendingRounds() -> [PendingRoundStatus]`, including the terminal outcome, funding txid(s) and error. Reference: `lib.rs:1128`.
8. **Boarding and exits**
   - `boardAmount`/`boardAll` should return ALL created VTXO IDs, not only the first.
   - `getExitStatus` should optionally return the exit transaction packages (parent/child tx hex plus status), as Nitro's `bark-cpp/src/exit.rs` does.
9. **Mailbox:** a `mailboxAuthorization` overload that takes an **absolute** Unix expiry (Nitro `cxx.rs:526`, `authorization_expiry: i64`) and returns the expiry alongside the encoded authorization.
10. **Missing fields**
    - `ArkInfo.mailboxPubkey`.
    - The parsed user pubkey and Ark ID for new addresses, or a `parseArkAddress(address)`.
    - The Lightning send/receive details Nitro returned, where cheap: invoice, amount, HTLC IDs and movement ID.

11. **(Done in v0.3.0: `OnchainWallet.openExisting`.) No-write open of an existing wallet:** `OnchainWallet.default` currently opens `db.sqlite`, runs SQLite migrations and creates BDK tables before `Wallet.openWithOnchain` checks `bark_properties` and the seed fingerprint. So a wrong mnemonic, the wrong network or an empty file all get written to before the open fails. Provide an opener, or make `createIfNotExists = false` behave this way, that validates properties, network and fingerprint read-only first and never creates or migrates the database when those checks fail. Noah currently works around this with its own read-only `bark_properties` check.

12. **(Done in v0.3.0: nine buckets + `pendingSats`/`totalSats`.) Full wallet balance (high priority):** bark's `Balance` (`bark/src/balance.rs` at the pinned commit) has nine buckets, and v0.2.0 only exposes six. It drops `needs_refresh`, `pending_arkoor_send` and `pending_offboard`, plus `total()` / `pending()` / `summary()`. Because current bark keeps `needs_refresh` sats out of `spendable`, any total built from the FFI undercounts the wallet whenever VTXOs need a refresh or an Ark send or offboard is in flight. Expose all nine fields in sats, and either `totalSats`/`pendingSats` or a `BalanceSummary { spendableSats, needsRefreshSats, pendingSats, totalSats }`, so apps use bark's own accounting instead of re-deriving it. Add a contract test that the FFI total equals `Balance::total()`.

13. **16 KB page-size alignment on Android (release blocker):** in the v0.3.0 AAR, `jni/arm64-v8a/libbark_bolt_ffi.so` and `jni/x86_64/libbark_bolt_ffi.so` have LOAD segments aligned to `0x1000` (4 KB; check with `llvm-readelf -lW libbark_bolt_ffi.so`). Android 15+ devices with 16 KB pages can't load the library, and Google Play requires 16 KB support for apps targeting Android 15+. A Pixel 6 on Android 17 already shows the "not 16 KB compatible" warning for Noah's build. Every other native library in the app (MMKV, AndroidX, DataStore) is already at `0x4000`. Link the Android targets with `-Wl,-z,max-page-size=16384` (e.g. per-target `rustflags = ["-C", "link-arg=-Wl,-z,max-page-size=16384"]` in `.cargo/config.toml` for `aarch64-linux-android`, `x86_64-linux-android` and `armv7-linux-androideabi`, or NDK r28+ defaults if they reach the Rust link step), and add a CI check that fails unless every LOAD segment in every shipped `.so` has `Align >= 0x4000`. Publish as a patch release.

## P2: optional, needs a product decision

- Onchain balance split into trusted and untrusted pending.
- Exposing the bolt11 payment secret.

## Docs to add

- A fee-unit table:
  - `Config.fallbackFeeRate` is sat/kwu, so Nitro's sat/kvB `10000` becomes `2500`
  - `OnchainWallet.feeRates()` is sat/kwu
  - emergency-exit and `progressExits` fee rates are sat/vB
- The recommended way to open an EXISTING wallet: preflight that `<datadir>/db.sqlite` exists, use `createIfNotExists = false`, pass the original network, and never create a fresh DB when one is expected.
