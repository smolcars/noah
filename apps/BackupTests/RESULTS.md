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
