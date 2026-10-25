# iOS in-place upgrade checks

`run.sh` installs the native app over a real RN regtest install (same
`com.noahwallet.regtest` sandbox, no uninstall in between) on a disposable
simulator. It then checks that the wallet, seed, settings, live RN MMKV bytes,
widgets and the server's recurring sync survive the replacement.
`UpgradeCompatibilityTests` only runs over a sandbox that `run.sh` has prepared.

## What the checks expect

Seed + wallet database opens Home. Only a readable `isInitialized=false` resumes
onboarding. Settings are imported once from an integrity-verified immutable
snapshot through scratch MMKV; native writes go to its own JSON store, and the live
RN files stay byte-identical. RN recurring schedules are never imported: the first
native Home shows a one-time recurring notice, and the server sync sends
`{"schedules":[]}` after admission. The full contract is the
[settings upgrade policy](../../android/UpgradeTests/STORES.md#settings-upgrade-policy).

## Prerequisites

- The Nix development shell, with XcodeGen and Maestro on `PATH`.
- A simulator you created for this run. The runner wipes **only** that simulator's
  regtest app and keychain before RN setup, and refuses the protected shared
  simulator names listed in `run.sh`.
- A Release RN regtest `.app` for the simulator. Build it from the repository root,
  inside Nix:

  ```sh
  bun install --frozen-lockfile
  just ios-prebuild
  cd client && SENTRY_DISABLE_AUTO_UPLOAD=true EXPO_PUBLIC_REGTEST_URL=127.0.0.1 \
    EXPO_PUBLIC_REGTEST_SERVER_URL=http://127.0.0.1:3000 \
    xcodebuild build -workspace ios/noah.xcworkspace -scheme Noah-Regtest \
    -configuration Release -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath <private-tmp>/rn-ios-dd ARCHS=arm64 ONLY_ACTIVE_ARCH=YES -jobs 2
  ```

  Keep the generated `Podfile.lock` matching `Pods/Manifest.lock` during the build,
  then restore the tracked lockfile.
- The regtest stack and Noah server already running (server at `127.0.0.1:3000`).
  `check-server-sync.py` reads the `scripts-noah-server-1` log and the
  `scripts-postgres-1` database read-only. Don't restart shared containers.
- For `FUNDED=1`, Bark needs at least 100,000 spendable sats. If it has less:
  `just bark onchain address`, `just bcli sendtoaddress <address> <btc>`,
  `just generate 1`, `just bark board "300000 sats"`, `just generate 1`. Always pass
  an explicit block count.

## Run

From the repository root, inside Nix:

```sh
FUNDED=1 INSTALL_WHILE_RUNNING=0 apps/ios/UpgradeTests/run.sh <simulator-udid> <RN-regtest.app>
FUNDED=1 INSTALL_WHILE_RUNNING=1 apps/ios/UpgradeTests/run.sh <simulator-udid> <RN-regtest.app>
```

Run both modes for the full acceptance check. Each needs a fresh RN setup.

- `FUNDED=1`: through RN's UI, receives 100,000 Ark sats and sends 5,000 to Bark,
  creates a recurring rule, changes unit, currency, name, auto-board, privacy,
  debug and language (Swedish), then receives 30,000 onchain sats and mines one
  block. It also places an RN widget. Without it, the runner only creates a wallet.
- `INSTALL_WHILE_RUNNING=1` (default): replaces RN while it is running and writing
  MMKV. `=0` replaces a stopped RN and also requires unchanged MMKV bytes across
  installation.
- `RN_PREPARED=1`: retry after RN setup has already completed. It skips the
  destructive setup and funding, and checks that the installed RN build number and
  JS bundle hash match the supplied app. Never use it after native was installed.
- `UPGRADE_DERIVED_DATA`: keep DerivedData at this path (default: a temporary
  directory removed on exit). Remove it yourself afterwards.
- `UPGRADE_EVIDENCE` (default `/private/tmp/noah-upgrade-evidence.json`) and
  `UPGRADE_SCREENSHOT` (default `/private/tmp/noah-upgrade.png`): where the result
  JSON and final screenshot go.

On a shared machine, `JAVA_TOOL_OPTIONS=-Duser.home=/private/tmp/<your-maestro-home>`
keeps Maestro's Java logs and cache separate. Don't capture the onboarding
recovery-phrase screen.

## Reading the results

The runner stops at the first failed check. On success it prints the RN baseline,
`Wallet/settings/recurring upgrade checks passed.` and the elapsed time. It checks:

- the wallet fingerprint, every movement row, the Ark and confirmed onchain balances,
  and the database inode are unchanged;
- every live RN MMKV file (data and CRC) is byte-identical after native Home, a
  restart, the SDK open and the recurring screen, and the immutable snapshot
  matches it;
- imported settings match a private import of RN's MMKV bytes;
- the recurring notice shows once (not after a restart), and the recurring screen
  is empty;
- the server log has a successful sync with `recurring_schedule_count:0` for this
  wallet, from the native build, after the replacement;
- widget identities match (`check-widgets.py`), and the placed SpringBoard widget
  survives (`check-widget-placement.py`).

`UpgradeCompatibilityTests` runs with `TEST_RUNNER_NOAH_UPGRADE_CHECK=1`. On a
simulator, `registeredExpoIdentityAndTokenAreUnchanged()` is skipped, because RN
never registers for push there. That skip is expected. The result JSON holds only
public identity and counters, never the phrase, keys, auth JWT or Expo token.

## Expo token on a physical iPhone

Push-token continuity can only be checked on a device.

1. Build RN regtest (Release) for the device with `EXPO_PUBLIC_REGTEST_URL` and
   `EXPO_PUBLIC_REGTEST_SERVER_URL` pointing at the Mac's LAN IP. Install it,
   complete onboarding and allow notifications.
2. Read the RN wallet's Expo push token privately from the server database.
3. Build native with `-configuration Release-Regtest NOAH_REGTEST_HOST=<lan-ip>
   NOAH_REGTEST_SERVER_URL=http://<lan-ip>:3000` and the same signing identity.
   Install it over RN with `devicectl`; keep the app and keychain.
4. Run `UpgradeCompatibilityTests` on the device with `TEST_RUNNER_NOAH_UPGRADE_CHECK=1`
   and the token in `TEST_RUNNER_NOAH_UPGRADE_EXPECT_EXPO_TOKEN`
   (`TEST_RUNNER_NOAH_UPGRADE_EXPECT_EXPO_ID` is optional). The test compares
   values without printing them. Check a delivered push separately.

Use a fresh RN install. If the RN wallet was deleted in RN Settings before
onboarding again, RN keeps writing to its deleted MMKV file, so native finds no
settings and resumes onboarding. That is an RN bug, not an upgrade failure.

## Widgets

`check-widgets.py` compares RN and native widget kinds, extension bundle IDs and
app-group entitlements for every variant. Given the RN and native `.app` paths, it
also checks the built regtest products. These identifiers must not change:

- Mainnet: `NoahWidgetMainnet`, `com.noahwallet.mainnet.NoahWidgetMainnet`.
- Signet: `NoahWidgetSignet`, `com.noahwallet.signet.NoahWidgetSignet`.
- Regtest: `NoahWidgetRegtest`, `com.noahwallet.regtest.NoahWidgetRegtest`.

`rn-place-widget.yml` places an RN small widget if none is placed.
`check-widget-placement.py` records SpringBoard's placed instance and requires the
identical record after native UI checks. `widget-survives.yml` returns to Home
without adding another widget.

## Process-death restore checks

`settings-restore-faults.sh` kills the test host at each restore-settings boundary
and checks recovery in a fresh process. See
[Restore tests](../RestoreTests/README.md#process-death-recovery-checks).

## Related unit suites

These need no prepared sandbox: `RNStoreTests` (RN store envelopes, from
`apps/fixtures/rn-store-compatibility.json`), `MMKVRecoveryTests`,
`SeedSafetyTests`, `LaunchStateTests` and `ExpoInstallationIDTests`. The RN store
inventory is in [STORES.md](../../android/UpgradeTests/STORES.md).
