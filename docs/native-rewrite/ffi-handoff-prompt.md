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

13. **Done in v0.3.1.** **16 KB page-size alignment on Android (release blocker):** in the v0.3.0 AAR, `jni/arm64-v8a/libbark_bolt_ffi.so` and `jni/x86_64/libbark_bolt_ffi.so` have LOAD segments aligned to `0x1000` (4 KB; check with `llvm-readelf -lW libbark_bolt_ffi.so`). Android 15+ devices with 16 KB pages can't load the library, and Google Play requires 16 KB support for apps targeting Android 15+. A Pixel 6 on Android 17 already shows the "not 16 KB compatible" warning for Noah's build. Every other native library in the app (MMKV, AndroidX, DataStore) is already at `0x4000`. Link the Android targets with `-Wl,-z,max-page-size=16384` (e.g. per-target `rustflags = ["-C", "link-arg=-Wl,-z,max-page-size=16384"]` in `.cargo/config.toml` for `aarch64-linux-android`, `x86_64-linux-android` and `armv7-linux-androideabi`, or NDK r28+ defaults if they reach the Rust link step), and add a CI check that fails unless every LOAD segment in every shipped `.so` has `Align >= 0x4000`. Publish as a patch release.

14. **Delivered in v0.4.0** (`prepareSend`/`broadcastPrepared`/`discardPrepared`, shared input reservations, typed construction errors); **adopted on iOS**: exact signed fee/amount/vsize/input count, confirmed-only MAX, two-minute review expiry/discard and exact txid journaled before broadcast. Android adopted the v0.4.0 prepared-send APIs too. Prepared boarding is delivered in v0.5.0 (item 18); prepared offboards remain open. **Onchain send preparation and coin control (payment safety):** **User priority:** prepared sends must enforce the reviewed fee ceiling. Entered-amount onchain sends remain enabled, but the current `208 × feeRate` estimate can be exceeded when Bark selects many small input coins at submission. iOS now reviews and submits the same signed transaction, so its onchain-wallet fee/amount are exact. Boarding also uses exact preparation on iOS with v0.5.0; Ark→onchain/offboard sends still use estimates. `OnchainWallet.send` / `drain` select coins, sign, persist and broadcast in one call, so an app can't show the user the exact transaction it is about to pay, and `drain` spends unconfirmed change the user never reviewed (a MAX that quoted 30,000 confirmed sats can drain 100,000). Add a two-step API: `prepareSend(address, amountSats | drainAll, feeRateSatPerVb, confirmedOnly: Bool) -> PreparedTransaction { id, txid, amountSats, feeSats, vsize, inputs: [OutPoint] }` that builds and signs without persisting or broadcasting, and `broadcastPrepared(id) -> BroadcastResult`, plus a way to discard a prepared transaction. Construction errors (insufficient funds, dust) must come from `prepareSend`, never after anything was recorded.

   Prepared offboards must bind recipient amount and network fee as well as the exact VTXO inputs. `offboardVtxos` currently fixes the reviewed total coins but recomputes the fee/net output at submission; Noah keeps MAX enabled with explicit recipient/fee estimates and actual output/fee receipts (pending until readable).

15. **Delivered in v0.5.0: read-only `Wallet.transactionStatus(txid)` and BDK `OnchainWallet.transactionStatus(txid)`; adopted on iOS.** Ark→onchain/offboard Send, board and exit-claim receipts show not yet seen by the network / in mempool / confirmed at block height. Onchain-wallet Send uses status as extra display evidence while retaining BDK reorg/competing-spend and lock rules. Transport errors preserve the last known status and show an error; `notFound` is never failed-without-delivery evidence and never releases a payment lock. v0.4.0 supplied explicit `BroadcastResult` outcomes for prepared onchain sends; v0.5.0 supplies them for prepared boards too. `failed` remains submitted and locked, with broadcast-retry copy; `notAttempted` permits another review. Exact pre-submit board txids survive crashes and reconcile through chain status, pending boards and movement anchors, never amount matching. **Still open:** an explicit broadcast outcome for Ark `sendOnchain` / `offboard*` (prepared offboards are not available); their returned txid is tracked through the new chain-source API. v0.6.0 adds the movement id to `OffboardResult` but still no broadcast outcome.

16. **Addressed in v0.4.0 for Lightning** via `pendingLightningSends()` (paymentHash, amountSats, feeSats, invoice); **adopted on iOS in round 5 and rechecked against 0.4.0**: canonical invoice/hash/amount and reviewed fee/total bounds are persisted before check-only advancement. No Lightning API change was needed. **Delivered in v0.6.0 for Ark sends and offboards; adopted on iOS and Android:** `sendArkoorPayment` returns the payment's movement id and `sendOnchain` / `offboardVtxos` / `offboardAll` return `OffboardResult { txid, movementId }`. Send receipts persist the id, offboard receipts need the id and txid to agree, History links the exact row and repeat metadata is attached with `updateHistoryMetadata` on that id. Journals from older versions keep txid binding and candidate matching. **Still open:** the id only arrives when the call returns, so a crash between Bark recording the operation and the app saving its receipt still falls back to candidate evidence; that window needs item 19's pre-submission key or `pendingOperations()`. **Operation identity for Ark and Lightning sends:** after a crash mid-payment, apps can only match history by destination and amount, which can confuse an older pending operation with the new one. Return an operation/movement id from `sendArkoorPayment` and the `pay*` calls as soon as bark records the operation (or accept a client-supplied idempotency key stored on the movement), and expose `pendingOperations()` listing in-flight sends and offboards with those ids.

18. **Delivered in v0.5.0; adopted on iOS: prepared boarding and pinned inputs/fees.** `prepareBoard(.amount(gross) | .drainAll, feeRateSatPerVb, confirmedOnly)` supplies the exact gross funding output, net Ark amount, Ark fee, signed onchain fee/vsize and input outpoints. iOS reviews these values plus total leaving the onchain wallet; amount boards pay the chain fee in addition to gross, and MAX drains confirmed funds only. `submitPreparedBoard(id)` submits the reviewed bytes with txid, VTXO IDs and explicit broadcast outcome. Preparations expire after two minutes and discard on navigation/background/wallet changes; generation tokens discard late results. Auto-Board retains its persisted confirmation-only setting and 5,000-sat target, shows the exact remaining reserve and re-prepares once with reduced gross if necessary. The M6 journal stores txid before submit. Failed post-acceptance cache persistence retains the handle and retries the same ID before discard; discard cannot cancel an accepted board. Typed errors cover minimum amount, too many small coins, unavailable/reserved inputs, changed reviews and wallets needing an initial sync. Prepared offboards remain open.

19. **Durable M6 operation reconciliation:** accept a client idempotency key (or return a persisted operation ID before broadcasting) for boarding, delegated refresh, exit progression and claim; expose an operation lookup distinguishing not-submitted, recorded/pending, broadcast, failed-without-broadcast and terminal. Without it, a crash/error before receiving an ID cannot be correlated safely by amount alone. iOS retains scoped attempts, blocking only overlapping repeats of operations that could spend again. Read-only Check status resolves exact txids or conclusive per-item states; legacy boards without an exact txid and unknown refresh/exit/claim outcomes remain uncertain. New prepared boards journal a deterministic funding txid before submit, closing that boarding identity gap without implementing generic durable client keys. A destructive user override can clear one attempt after explicit repeat-risk consent. No automatic resubmission or amount matching. A durable operation API should resolve the remaining pre-ID crash window without asking users to guess. v0.6.0's movement ids (item 16) are returned only after submission, so this item stays open.

20. **Delivered in v0.5.0; adopted on iOS: `listAllExits(includeHistory, includeTransactions)`.** Bark's persisted listing supplies exact amounts, typed live/terminal states, optional history and transactions, including canceled/claimed rows after reopen. The iOS overview uses this listing; detail uses persisted `getExitStatus` amounts/history. App-side `ExitHistoryStore` discovery, legacy-movement seeding and pre-mutation `remember` writes are retired: the SDK fully covers those rows, so no migration bridge is needed and no wallet data is deleted. Tests cover terminal mapping/history and live cancel → active-list removal → reopen → persisted overview/history.

21. **Payment-request APIs Noah can't adopt yet (v0.6.0, see [ffi-phase-b.md](ffi-phase-b.md)).** Each change removes one blocker; Noah re-runs that evaluation when they land.
    - `parsePaymentRequest`: accept BIP-321 path addresses on signet and regtest (today `bitcoin:tb1…` and Noah's own regtest Receive URI are refused); return typed parse errors instead of one generic `invalidPaymentRequest`; check Ark addresses without a live Ark server; treat fiat-priced offers as "no fixed amount" instead of panicking.
    - LNURL and Lightning addresses: a resolve step that returns the invoice before paying, so the app can review it, journal its hash and pay exactly that invoice (today `sendPayment` fetches a fresh invoice inside the pay call). The LNURL client needs HTTPS only, no redirects and a timeout.
    - `bip321Uri`: take existing on-chain and Ark addresses (today it derives new ones every call) and an invoice description.
    - `estimatePaymentFees`: return a result per option, so one failing rail doesn't fail the list.

## P2: optional, needs a product decision

17. **Optional manual-progression exit policy:** Noah's RN bark-cpp also runs Bark's daemon, and RN maintenance includes onchain synchronization and exit progression. Native iOS therefore permits explicitly confirmed start and manual progress, explaining at start that Noah subsequently progresses exits automatically in the background, broadcasting/fee-bumping transactions and paying fees from the onchain balance. This is RN parity, not an M6 blocker. For a future product policy, expose a persistent `exitProgressPolicy: manual | automatic` that can disable daemon progression/CPFP and broadcast-capable exit sync without stopping the rest of the daemon. Provide read-only chain-status refresh and explicit progression with an operation ID/broadcast outcome. A manual policy should survive close/reopen, foreground, push, and restore; tests should prove only explicitly authorized progression broadcasts in manual mode. The current estimate/status paths remain read-only; durable user-initiated attempts prevent app-level resubmission after uncertainty (item 19), while Bark's authorized automatic progression continues.

- Onchain balance split into trusted and untrusted pending.
- Exposing the bolt11 payment secret.

## Docs to add

- A fee-unit table:
  - `Config.fallbackFeeRate` is sat/kwu, so Nitro's sat/kvB `10000` becomes `2500`
  - `OnchainWallet.feeRates()` is sat/kwu
  - emergency-exit and `progressExits` fee rates are sat/vB
- The recommended way to open an EXISTING wallet: preflight that `<datadir>/db.sqlite` exists, use `createIfNotExists = false`, pass the original network, and never create a fresh DB when one is expected.
