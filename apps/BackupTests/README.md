# Funded native backup acceptance

Run from the repository root on an Apple Silicon Mac with Xcode 27, XcodeGen,
the host Android SDK (`~/Library/Android/sdk`, API 35 Google APIs ARM64 image),
and an **already running, initialized** regtest stack:

```sh
nix develop --command python3 apps/BackupTests/run.py
```

The runner creates a uniquely named iPhone 17/iOS 27.0 simulator and AVD, using
console port **5618** with `-no-window -no-snapshot`. It refuses an occupied port.
It uses two Xcode jobs, Gradle `--max-workers=2 --no-daemon`, and private temporary
DerivedData/result bundles. On exit it checks device identity, shuts down,
confirms shutdown, deletes only its devices, and removes private build artifacts.
It never starts, stops, recreates or resets Docker services. Missing required
containers fail preflight. Bitcoin mining advances the shared regtest chain.
When LND has less than 80,000 sats of local channel balance, the runner pays a
fresh 80,000-sat LND invoice from CLN over the existing channel so it can be rerun.

`FundedCrossPlatformBackupTests` (Swift) and `FundedCrossPlatformBackupTest`
(Kotlin instrumentation) exercise production wallet creation, receive/claim,
sync, encrypted local export, restore transaction, SDK validation/reopen and Ark
send paths. Each source receives **10,000 confirmed onchain sats** through
`just bcli` and two Lightning payments (**31,000 + 7,000 sats**) through
`just lncli payinvoice --force --json` (the server uses CLN, so LND is the payer).
Receive fees are reflected in the recorded spendable balance.
The source closes before the other platform restores its unchanged encrypted
file. Every balance row, fingerprint, movement count and projected history row
(ID, amount, type, direction, movement status/kind/fee) must match after sync.
Before-sync state is recorded separately. Each restored wallet sends **1,000 Ark
sats** to a fresh Bark address through the app's send service; sender accounting,
new successful movement, unchanged onchain funds and receiver history are checked.

Only the backup listing/download server is a fixture; no SDK, VTXO, database,
crypto, archive or filesystem restore implementation is mocked. This is service
acceptance inside the regtest apps, not a tap-through onboarding/share-sheet test
or S3/authentication test. Test wallets use separate datadirs; iOS uses a unique
test Keychain item and Android a unique Android Keystore alias and real atomic
encrypted seed file. Device deletion removes all
remaining test data. No production, `client/` or `server/` code is changed.

Phrases and encrypted exports travel only over loopback (Android uses an adb
reverse tunnel), remain in host memory, and are never written to evidence or
printed. No screenshots are taken. Public JSON evidence defaults to
`/private/tmp/noah-funded-backup-evidence.json`; override with `--evidence PATH`.
The comparison checker has an assert-based negative self-check:

```sh
python3 apps/BackupTests/run.py --self-test
```

Do not rerun an individual spend after an uncertain outcome. Inspect its movement
and Bark receiver state first. A full runner invocation always creates new wallets.
