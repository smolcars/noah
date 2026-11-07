# Android Send tests

Send's JVM tests run in `:app:testRegtestDebugUnitTest`. The live instrumentation
tests below spend regtest coins and are skipped unless their argument is passed.
Emergency exits, boarding and VTXOs are in [ExitTests](../ExitTests/README.md); iOS's
counterpart is [apps/ios/SendTests](../../ios/SendTests/README.md).

## What's covered

- JVM (`com.noahwallet.send`): parsing and composing (`SendFixtureTest`,
  `SendAmountTest`, `SendAmountErrorTest`, `SendDestinationNativeTest`, `SendOfferTest`,
  `SendLnurlTest`, `SendRecipientTest`, `SendPasteTest`, `SendScannerTest`); submission
  safety and recovery (`SendSafetyTest`, `SendPreparedTest`, `SendDiscardTest`,
  `SendConflictTest`, `SendLightningTest`, `SendLightningRetryTest`, `SendOffboardTest`,
  `SendChainStatusTest`, `SendMovementIdentityTest`, `SendJournalOwnershipTest`,
  `SendOverrideAuditTest`, `SendUnresolvedOverrideTest`, `SendRefreshTest`,
  `SendLoadingTest`); the success receipt (`SendSuccessDetailsTest`).
- Instrumented, always on: `SendJournalDeviceTest` (fsynced, versioned journal across a
  fresh reader; legacy files stay untrusted).

`SendFixtureTest` reads the shared `send-flow.json`, `send-utils.json`,
`onchain-send.json` and `lightning-invoice.json` straight from
[apps/fixtures](../../fixtures/README.md) (the JVM resource set includes that folder).
They come from RN's implementations; see [iOS](../../ios/SendTests/README.md#whats-covered)
for the generator. Some invoices in `send-utils.json` are decoder-only, with invalid
signatures: never submit them.

## Running

From the repository root:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'
```

Add `--tests 'com.noahwallet.send.*'` to run only Send. Reports are in
`apps/android/app/build/reports/tests/testRegtestDebugUnitTest/`.

## Live instrumentation tests

They need the local regtest stack (`just up`) and your own disposable emulator.
`./gradlew install*` and `connected*` target every connected device, so build the
APKs and install them on your emulator's serial with `adb -s` instead:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew :app:assembleRegtestDebug :app:assembleRegtestDebugAndroidTest -PnoahRegtestServerURL=http://10.0.2.2:3100'
adb -s "$SERIAL" install -r apps/android/app/build/outputs/apk/regtest/debug/app-regtest-debug.apk
adb -s "$SERIAL" install -r apps/android/app/build/outputs/apk/androidTest/regtest/debug/app-regtest-debug-androidTest.apk
adb -s "$SERIAL" shell am instrument -w -e class com.noahwallet.send.SendLiveTest#bootstrapThrowawayWallet \
  -e noahSendLive disposable-emulator com.noahwallet.regtest.test/androidx.test.runner.AndroidJUnitRunner
```

`-PnoahRegtestServerURL` points the build at your Noah server; the emulator reaches the
host as `10.0.2.2`. Tests write their evidence inside the app. Read it with
`adb -s "$SERIAL" shell run-as com.noahwallet.regtest cat <path>`. Fund and mine with
`just send-to <address> <btc>`, `just bcli`, `just bark`, `just cln`, `just lncli` and
`just generate 1`.

Fund each published address once. The tests never retry a submission. If one fails,
read the receipt and history before anything else, and never rerun a creating stage in
a wallet that already holds funds.

| Test (`-e class …`) | Arguments | What it does | Writes |
| --- | --- | --- | --- |
| `SendLiveTest#bootstrapThrowawayWallet` | `-e noahSendLive disposable-emulator` | Creates a wallet in an empty app. Needs a build with `-PnoahRegtestServerURL=http://10.0.2.2:3100` | `files/send-live-addresses.json` (Ark and onchain address) |
| `SendLiveTest#fundedPreparedLifecycle` | `-e noahSendPreparedLive disposable-emulator -e recipient <Core address>` | Prepares, discards and re-prepares a 5,000-sat onchain send across close/reopen; nothing is broadcast. Fund the onchain address first | `files/send-live-lifecycle.json` |
| `SendLiveTest#sendComposerLoads` | `-e noahSendStartup disposable-emulator` | Opens Send on the existing wallet and checks that an empty journal lets you compose | — |

## bark-bolt-ffi stage tests

Each runs one stage per `am instrument` call, refuses a wallet it didn't create
(through an owner argument and a fingerprint marker), and writes public evidence to
`cache/ffi0x0-<stage>.json`. The host funds, pays and mines between stages.

| Test | Owner and stage arguments | Stages |
| --- | --- | --- |
| `com.noahwallet.send.Ffi060LiveTest` | `-e owner ffi060-disposable -e noahFfi060 <stage>` | `create`, `receive` (pay the onchain address and mine; the host pays the invoice in `cache/ffi060-invoice.txt`), `ark` (optional `-e probe`), `ln-send` and `ln-fail` (`-e invoice <BOLT11>`; for `ln-fail` hold and then cancel it), `offboard` (`-e recipient`), `offboard-confirmed` (after one block), `reopen` |

`Ffi060LiveTest` runs against a Bark 0.8 stack and refuses a build without its endpoints. Build with
`-PnoahRegtestArkURL=http://10.0.2.2:13535 -PnoahRegtestBitcoindURL=http://10.0.2.2:28443 -PnoahRegtestServerURL=http://10.0.2.2:13000`
(use your stack's ports). `nix develop --command sh apps/android/scripts/check-regtest-endpoints.sh`
checks how those properties are validated.

## Reading results

`am instrument -w` prints each test's result. A live test that wasn't given its
argument reports as skipped (an assumption failure), not as a pass. Read the stage's
evidence before mining or paying for the next one. It holds amounts, txids and
receipts, never seeds or phrases. Keep runtime destinations, invoices and wallet data
out of the repo.

The Send and Receive screenshot gallery (`SendReceiveGalleryTest`) is described in
[apps/android/README.md](../README.md).
