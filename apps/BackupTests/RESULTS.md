# Real RN ↔ native encrypted backups — 2026-10-21

Base: `feat/native-rewrite` at `06f8a2a213a4c81f90ee655afcf6b2685a5cd389`.
**All four funded app-to-app directions passed**, including a spend from each
restored wallet. RN 0.1.14 (38), `react-native-nitro-ark` 0.0.144; native 0.2.0
(100), bark-bolt-ffi 0.5.0. All apps used the existing S3-backed server on **:3100**.
No server, client or native production implementation was changed.

Exact public machine-readable evidence, all fourteen balance rows, every checked
movement row, onchain transaction IDs, bootstrap and uploaded-object metadata,
ZIP entries, manifests and build hashes are in
[RN_NATIVE_EVIDENCE.json](RN_NATIVE_EVIDENCE.json). The sibling runners and build
commands are documented in [README.md](README.md#real-rn--native-s3-acceptance).

## Observed results

“Ark / onchain” means source **and restored**, in sats. History counts are source,
restored and after restored spend, combining SDK movements with onchain history.

| Platform | Direction | Result | Source = restored fingerprint | Ark / confirmed onchain | History, source → restore → spend | Bark receiver movement |
|---|---|---|---|---|---|---|
| iOS 27.0 | RN → native | **PASS** | `54886eae` | 20,000 / 10,000 | 2 → 2 → 3 | `6` |
| iOS 27.0 | Native → RN | **PASS** | `032ec82a` | 20,000 / 10,000 | 2 → 2 → 3 | `5` |
| Android 15 | RN → native | **PASS** | `53bdf6b1` | 20,000 / 10,000 | 2 → 2 → 3 | `8` |
| Android 15 | Native → RN | **PASS** | `9e0359d3` | 20,000 / 10,000 | 2 → 2 → 3 | `10` |

Every source received **20,000 Ark sats** with `just bark ... send --wait` and
**10,000 onchain sats** with `just bcli sendtoaddress ... 0.00010000`, followed by
exactly **one** `just bcli generatetoaddress` block. The apps synced and displayed
funded history before Backup Now. The source's successful Ark receive had SDK
ID `1`, subsystem `bark.arkoor`, kind `receive`, intended/effective balance
`+20,000`, fee `0`; its confirmed onchain history row was retained unchanged.
All fourteen balance rows matched: Spendable=Offchain=20,000,
Confirmed=Onchain=10,000, and the remaining ten rows zero.

Each destination restored through its actual onboarding Restore UI, opened Home,
and sent **5,000 Ark sats** with the existing app Maestro send flow. Each send
added successful SDK movement `2`, kind `send`, intended/effective balance
`−5,000`, fee `0`, preserved the original movement and onchain row, and left
**15,000 Ark + 10,000 confirmed onchain sats**. Bark's independently checked
receiver history confirmed all four payments. No uncertain payment was repeated.

## Bootstrap setup, not an interop result

Both apps require an existing server backup before Restore can initialize a
wallet. With the user's explicit authorization, setup created a **fresh, empty
SDK wallet** for each known public BIP39 test vector, made a current v2 snapshot,
and uploaded that empty bootstrap through :3100 to real S3. All fourteen SDK
balance rows were zero; both movement and onchain histories were empty. No
random phrase was generated or captured. Public phrases were not printed,
placed in OS command arguments, or retained in evidence or screenshots.

Only each **source app** restored its empty bootstrap: RN for A/C, native for
B/D. A/B were the independent iOS directions; C/D were independent Android
directions. The targets restored the later **funded app-made** files, never an
independently generated funded fixture. Earlier unfunded Android setup attempts
were discarded; D's final empty bootstrap was recreated before its successful
source run. No funding had occurred in the discarded D attempt.

After source UI Backup Now and List Backups, the runner stopped the source,
recorded the server's newest completed object, and downloaded it from a verified
AWS S3 origin. Its ID and timestamp differed from the bootstrap; its encrypted
size and SHA-256 matched the server list and downloaded bytes. Immediately before
target restore, the newest server record was required to equal that same object.
The funded snapshot's complete state then matched the destination's installed
wallet. On iOS, the target's persisted/retained backup-state ID was additionally
checked against the selected source object ID.

Automatic backups were enabled in the apps; Backup Now uses the existing
unchanged-snapshot optimization. The native iOS manual flush reused its preceding
**funded app-made S3 object**, rather than uploading an identical snapshot again.
The UI confirmed success and listed that object. This remains a real native
`BackupService` upload, not the empty setup bootstrap or a fixture transport.

## App-made S3 objects

- **RN iOS**: `e770f240-c73a-4a14-a4b9-7c5c372b9d1d`; `2026-10-21T12:28:24.325735+00:00`; **22,265 bytes**. SHA-256: `857e88587be55dc336cb181da5757eaad0ec954d94b02a410a2dd40827a9c48f`.

- **Native iOS**: `c3a1dc3f-6cab-4369-b4cd-761a4c83b84a`; `2026-10-21T12:13:59.981421+00:00`; **23,448 bytes**. SHA-256: `d02091bbe08f3601828665079c8a01431a4ed1be43f70c221faee974bf3fa2fb`.

- **RN Android**: `7ee37b47-3972-4185-ab17-2e7b92f099c9`; `2026-10-21T12:42:13.544940+00:00`; **27,248 bytes**. SHA-256: `900a64f89b85a9cecdaf96463f565c726cfd87fdaaf6b284987011e8f998426c`.

- **Native Android**: `b3e90cc4-6a5e-42df-a673-a8ea2151ee31`; `2026-10-21T13:07:18.114955+00:00`; **29,644 bytes**. SHA-256: `d9808b200fe375b7a1c1f53aca743c3976204058489a4deeaabdfd7d0296aade`.

These are the exact source objects checked before restore. A destination can
subsequently upload a newer backup after spending; that does not change which
object was selected for the restore test. Full bootstrap IDs, timestamps, sizes
and hashes are retained separately in the JSON evidence.

## Format comparison from actual app files

Every uploaded source object was downloaded and decrypted with the unchanged
`scripts/decrypt_backup.js` CLI. A private Node module wrapper supplies its
mnemonic in process memory, keeping it out of the OS argument list and logs.
Envelope version and manifest `formatVersion` were **2** throughout: salt 16,
nonce 12, PBKDF2-SHA256 600,000 iterations, AES-256-GCM with a 16-byte tag. The
manifest's snapshot SHA-256 matched the extracted database bytes.

| Actual writer | ZIP order | Compression | General-purpose flags | ZIP extra fields | Snapshot / manifest bytes |
|---|---|---|---|---|---|
| RN iOS | `db.sqlite`, `manifest.json` | Deflate (8) | `0x0800` (UTF-8) | none | 290,816 / 385 |
| Native iOS | `db.sqlite`, `manifest.json` | Deflate (8) | `0x0800` (UTF-8) | none | 290,816 / 385 |
| RN Android | `manifest.json`, `db.sqlite` | Deflate (8) | `0x0808` (UTF-8 + data descriptor) | none | 299,008 / 385 |
| Native Android | `manifest.json`, `db.sqlite` | Deflate (8) | `0x0808` (UTF-8 + data descriptor) | none | 303,104 / 385 |

There was **no RN/native layout mismatch within either platform**. The platform
ZIP order/descriptor differences were accepted by the real readers. The
independent wallets produced different snapshot/encrypted sizes; size equality
between independent wallets is not a format requirement.
All manifests had the same eight fields: `formatVersion`, `createdAt`,
`snapshotSha256`, `network`, `walletFingerprint`, `serverPubkey`, `mailboxPubkey`,
`barkSchemaVersion`; network=`regtest`, schema=`42`. There was no variant parent
directory, WAL/SHM, MMKV, seed, Keystore or auth token in any v2 archive.

## Verification, harness findings and cleanup

Acceptance was executed incrementally with the committed runners' helpers and
Maestro flows while correcting setup selectors, then all saved source/restore/
spend evidence was rechecked together. It was not a second uninterrupted
invocation after resetting the shared stack. The runners intentionally refuse
reuse of their fixed vectors, and no stack reset was performed. Both comparison
self-checks and Python compilation passed; the normal commit hooks ran RN lint /
type checks and `cargo fmt --check` without bypassing them. Full native unit
suites, physical devices and push delivery were not part of this acceptance.

Initial failures were **harness/setup failures**, not application restore failures:
RN iOS groups its restore form into one accessibility node; Android native word
fields expose their `Word 1` label instead of a Compose test ID; Android with a
hardware keyboard treats Back as navigation; the default tiny AVD needed a
normal phone viewport. The final runner uses owned iPhone 17 portrait simulators
and Pixel 7 AVDs, system Paste, native field labels and the actual QR details
button. Some receive-sheet checks failed after funding had completed; payment
state was inspected and those payments were not repeated. The final runner
collects both public receive addresses before funding, avoiding the receive-success
screen changing the copy sheet. No download, decrypt, archive,
snapshot-install or SDK-open defect was found, and no production fix is proposed.

All four owned iOS simulators were identity-checked, confirmed shut down,
deleted and verified absent. All six owned Android AVDs (including unfunded
setup retries) were identity-checked, shut down, deleted and verified absent;
`emulator-5620` was absent from adb. Both Gradle clean operations passed; private
RN/native DerivedData, APKs, project build outputs and 35 owned Maestro temporary
driver/build directories were removed. Pre-existing tracked Gradle cache files
were restored unchanged. No Xcode/Gradle build process remained. Remaining private
runner logs and scratch data were removed after the final commit checks.

The S3-backed :3100 server still returned HTTP 200, and the shared stack's
Bitcoin, Ark, CLN, LND, server, Postgres and cache services remained running.
No container lifecycle action, protected simulator, physical device, main
checkout or translation-tool/catalog change was made.

---

# Funded native backups — 2026-10-20

Base: `feat/native-rewrite` at `0b3b545d19a2c5c389427edb84e5bf097d262334`,
bark-bolt-ffi 0.5.0. Both directions **passed** through real native app services
on an owned iPhone 17/iOS 27.0 simulator and Google APIs Android 15 ARM64 AVD.
Run instructions and exact coverage are in [README.md](README.md).

## Funding and transfer

For each independently generated regtest wallet, the runner sent 0.00010000 BTC
with `just bcli sendtoaddress`, mined exactly one block with `just bcli
generatetoaddress`, and paid two app-created BOLT11 invoices with
`just lncli payinvoice --force --json`: 31,000 and 7,000 sats. Receive fees were
162 and 114 sats, leaving **37,724 spendable Ark sats** and two successful
`lightning-receive` movements. The server uses CLN; LND is the payer. The app's
regtest configuration uses Bitcoin RPC `18443` and Ark `3535`, through
`localhost` on iOS and `10.0.2.2` on Android.

Each source exported through its production `BackupService` and closed before
the other app's `WalletRestorer` downloaded the unchanged encrypted bytes,
validated them, committed the filesystem/secure seed transaction, and reopened
through the production SDK opener. Only backup server listing/download metadata
was served by the local bridge. Phrases and encrypted exports stayed in host
memory; no phrase, password or private backup was logged or committed.

## Observed results

| Direction | Fingerprint, source and restored | Ark, source / restored | Confirmed onchain, source / restored | After restored spend |
|---|---|---|---|---|
| iOS → Android | `ce392bd0` | 37,724 / 37,724 | 10,000 / 10,000 | 36,724 Ark; 10,000 onchain |
| Android → iOS | `0bbe2205` | 37,724 / 37,724 | 10,000 / 10,000 | 36,724 Ark; 10,000 onchain |

All **14 balance rows**, fingerprints, raw SDK movement counts and projected
history records matched both immediately after reopen and after explicit sync.
The nonzero rows were Onchain=Confirmed=10,000 and
Offchain=Spendable=37,724; the other ten rows were zero. After spending,
Offchain=Spendable=36,724 and the onchain rows were unchanged.

Both wallets retained these movement IDs, amounts, fees, types and statuses:

| SDK ID / history ID | Direction / type | Amount (sats) | Offchain fee (sats) | Status |
|---|---|---|---|---|
| 1 / `movement-1` | incoming / Bolt11 | 31,000 | 162 | successful |
| 2 / `movement-2` | incoming / Bolt11 | 7,000 | 114 | successful |
| 3 / `movement-3`, added by restored wallet | outgoing / Arkoor | 1,000 | 0 | successful |

Each source also had its incoming 10,000-sat onchain history row, preserved
through restore and spend:

- iOS source: `onchain-wallet-a34a7d86befc799aaa5e3d69107d75e37b235c33714634d7f99bf277918f44b5`.
- Android source: `onchain-wallet-89b4cee7379cd320377e9b92a894be7595fcf65414b55d2a44ee02b5d3485016`.

Each restored wallet sent **1,000 Ark sats** to a fresh Bark receive address.
The runner checked the production send receipt, exact debit, a new successful
movement, unchanged older history/onchain funds, and a new receiver movement.
Bark recorded **movement 57** for the Android-restored wallet and **58** for the
iOS-restored wallet. SDK movement counts went **2 → 3**; combined displayed
history counts went **3 → 4**.

## Verification and cleanup

The final `nix develop --command python3 apps/BackupTests/run.py` exited **0**:
two iOS test invocations and one Android instrumentation test passed, covering
both exports, restores, sync comparisons and spends. The comparison self-check
also passed, including negative identity/balance/history/spend checks and Bitcoin
CLI string/array parsing. Public state evidence was written to
`/private/tmp/noah-funded-backup-evidence.json`.

Owned devices **NoahFundedBackup_028d3506**, simulator
`C8D9A7D7-9E36-4D15-9CF7-D79C18243B6E` and `emulator-5618`, were identity-checked,
shut down, confirmed stopped, deleted and verified absent. Owned DerivedData,
result bundles and logs were removed. Earlier setup attempts were also cleaned.
No protected or physical device, shared container lifecycle, production code,
`client/` or `server/` implementation was changed.

No backup/restore defect was found. Initial harness attempts exposed CLI output
parsing, LND's default table output and nonportable copied Xcode caches; the final
runner uses checked CLI output and a fresh build directory. Ktor's generated
absolute API paths are routed by the test transport to the producer fixture.
This verifies service paths inside both regtest apps. Onboarding/share-sheet UI,
S3/authentication, and the complete native unit suites were not rerun.
