# Android native Send (M3) verification

## bark-bolt-ffi 0.6.0 adoption (2026-10-22)

Base `f42f175d`. Android pins **`io.github.niteshbalusu11:bark-bolt-ffi-android:0.6.0`**
(Bark 0.8.0, `lib-0.8.0` `959a489f`, tag `b5aed0bb`). The AAR (`4e6582c8…`) and POM
(`80920813…`) match the release `SHA256SUMS`, and Gradle's verification metadata
pins both. Every LOAD segment of the arm64-v8a, armeabi-v7a and x86_64
`libbark_bolt_ffi.so` is `0x4000` ([artifact verification](ffi060/artifact-verification.txt)).
The built APK carries the AAR's arm64 library byte for byte.

- **Lightning options.** Invoice and offer sends pass
  `LightningSendOptions(wait = true, retryForSecs = 60)` (`SendLightningSubmission.options`):
  60 s is today's server default, set explicitly; `wait = false` stays for the
  async-payments phase. `checkLightningPayment(hash, wait = false)` is unchanged.
  Android has no `payLightningAddress` / `payLnurl` caller (LNURL resolves to an
  invoice first), so nothing else passed `wait`.
- **Movement identity (FFI 16).** `sendArkoorPayment` returns the payment's movement
  id and `sendOnchain` / `offboardVtxos` return `OffboardResult { txid, movementId }`.
  The Send receipt stores the id, so the Send journal and the recurring journal
  (which keeps the whole `SendAttempt`) carry it across restarts, and the Send
  result's `SendSuccessDetails` links the exact History row. Offboard receipts fill
  the actual amount/fee only when the returned id and txid agree. Repeat-payment
  metadata goes on that exact id with `updateHistoryMetadata` (or on the
  hash-bound Lightning movement), never on a destination/amount match; movements
  that predate the attempt (an inherited checkpoint) are untouched.
- **Old journals.** `movementID` was already an optional receipt field, so journals
  written before 0.6.0 decode unchanged: offboards still bind by txid alone and Ark
  attempts without a receipt still show candidates. A missing id never means
  "not sent"; the crash window between Bark recording a payment and Noah saving
  its receipt stays uncertain (FFI 19).
- **Errors.** `BarkErrorCode` keeps 0–12 and adds `INVALID_PAYMENT_REQUEST` (13),
  raised only by the new payment-request APIs. Every Android mapping is an
  allowlist with a conservative default (`SendOnchainSubmission.notSubmitted`,
  `boardFailure`, `OnchainSubmission.preBroadcast`, the exit-estimate fallback), and
  the existing tests iterate `BarkErrorCode.entries`, so code 13 is covered with no
  mapping change.
- **Debug info.** Settings › Debug Screen › "Get Debug Info" shows `debugInfo()`'s
  network, mailbox id and VTXO xpub in the Result row, which copies only when
  tapped. Nothing logs it; Sentry attaches no screenshots, view hierarchy or replay.
  The label reuses the existing es/ja/ko/sv translations (0 untranslated).
- **Regtest endpoints.** `-PnoahRegtestArkURL` / `-PnoahRegtestBitcoindURL` replace
  the host's `:3535` / `:18443`. Only the regtest flavor sets `REGTEST_ARK_URL` /
  `REGTEST_BITCOIND_URL` (empty otherwise, validated like the server URL), and only
  the regtest branch of `WalletConfiguration.base` / `ArkInfoEndpoints` reads them.
  With both flags passed, mainnet and signet debug/release BuildConfig stay empty
  ([evidence](ffi060/regtest-overrides.txt)). For `noah-bark08`:
  `-PnoahRegtestArkURL=http://10.0.2.2:13535 -PnoahRegtestBitcoindURL=http://10.0.2.2:28443
  -PnoahRegtestServerURL=http://10.0.2.2:13000`.

**No SQLite migration.** `bark/src/persist/sqlite/migrations` is identical between
`bfbe6b7` (0.5.0's Bark) and `lib-0.8.0`; the newest is still `m0042`.

**Tests.** Targeted Send run: 587 tests / 19 suites; debug/localization/settings
14; endpoints/privacy 16; the new recurring regression 5 (all `SendMovementIdentityTest`).
Full run once, no exclusions:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebugAndroidTest'
```

**3,818 tests / 179 suites, zero failures, errors or skips**; instrumentation APK
assembled (2m 21s) ([JUnit totals](ffi060/junit-summary.json)). New:
`SendMovementIdentityTest` (returned id survives restart for Ark / offboard /
`sendOnchain` without reconciliation; metadata only on the exact new id; offboard id
and txid must agree; pre-0.6.0 journals keep txid and candidate matching; a recurring
Ark id survives restart without the Send journal), the explicit 60 s options,
`debugInfo` JSON, and override defaults / mainnet-signet isolation.

**Live checks on `noah-bark08`** (owned AVD `noah-ffi-phone`, API 35 arm64,
emulator-5592; opt-in `Ffi060LiveTest`, `-e noahFfi060 <stage> -e owner ffi060-disposable`;
the host acted between stages only through `docker exec` and mined blocks 884 and 885):

| Stage | Result |
| --- | --- |
| `create` | Wallet `5b25b077` against `10.0.2.2:13535` |
| `receive` | Core paid 200,000 sats on-chain (confirmed in 884). LND paid a 30,000-sat invoice: one `bark.lightning_receive`, 29,840 spendable (server fee 160) |
| `ark` | 8,000 sats to a throwaway in-process peer: receipt and journal carry movement 2 (`bark.arkoor` send to that address); repeat metadata only on 2. The peer paid 3,000 back and it arrived as an arkoor receive. The peer then paid a 1,000-sat LND invoice with `retryForSecs = 45`: captaind logged `retry_for: 45s`, so the SDK forwards the option |
| `ln-send` | 5,000-sat LND invoice settled through the production controller: proven receipt, movement 4, fee 80; captaind's xpay span shows `retry_for: 60s` |
| `ln-fail` | LND hold invoice held, then cancelled: Send ended Failed, movement 5 `failed`, spendable 19,760 before and after, nothing pending |
| `offboard` | `sendOnchain` 10,000 sats to Core: txid `b40b623c…` and movement 6 journaled; `bark.offboard` `send_onchain` with that txid; amount 10,000, fee 2,580; in mempool (Core: vsize 185, fee 1,853) |
| `offboard-confirmed` | After block 885 a fresh controller resolved the journal to Confirmed at 885 with the same id and txid |
| `reopen` | Close/reopen: same fingerprint, 6 movements, 7,180 spendable; `debugInfo()` returned regtest, mailbox id and xpub; the exported app log has no xpub |

The app UI then opened on Home (207,180 sats; the 8,000-sat row labelled from its
repeat metadata). The Debug screen listed "Get Debug Info" after "Get Ark Info" and
its Result showed the three fields; no screenshot of it was kept, and the app log
files and logcat (95,748 lines) had no xpub or mailbox id.
[Live summary](ffi060/live-summary.json).

**In-place upgrade.** A scratch copy of `f42f175d` (0.5.0, arm64 library from the
verified 0.5.0 AAR) plus only the regtest-override commit and
`Ffi060UpgradeLiveTest` (APIs both SDKs share) created wallet `c73094bd` on
`noah-bark08`, received 10,000 sats over Lightning, sent 3,000 Ark and 2,000
Lightning: 3 movements, 4,797 spendable. Installing the 0.6.0 APK over it with
`adb install -r` (same signer) opened it through `openExisting` with the same
fingerprint, every movement's id/status/kind/amounts/fee and the same balance,
before and after a sync. Bark's `migrations` stayed at 42 and the `sqlite_master`
schema hash (`ce937661…`) was identical before and after. Home showed ₿ 4,797 and
the three rows.

**Existing compatibility tests on the 0.6.0 build.** `CrossPlatformBackupTest` and
`WalletDatabaseDeviceTest` on the same emulator: 8 passed, 1 opt-in generator
skipped. `restoresIOSServiceExport` restored the committed iOS export (a
bark-bolt-ffi 0.5.0 database) through the production restorer and opened it with
the expected fingerprint, movement count and balance; the read-only preflight
cases (missing, empty, foreign, wrong network, WAL without index, corrupt) still
refuse without touching files. The JVM run covers the RN-migrated MMKV/store
fixtures. The funded RN → native runner (`UpgradeTests/run.sh`) was not rerun: it
needs an RN regtest build and funds through the default `scripts` stack, which is
pre-0.8. Its last funded Android run already ran on 0.5.0's Bark, whose client
SQLite schema 0.6.0 leaves unchanged.

Observed, unrelated: Home showed "Backup failed" for both disposable wallets. The
0.6.0 snapshot succeeded (the app reached `POST /v0/backup/v2/upload`), and
`noah-bark08`'s server answered 500 there, as it already had at 02:53Z before this
run.

**Invoice retry (observed, not fixed).** On the upgraded wallet, through the real
Send UI, the same 1,000-sat BOLT11 invoice was paid with the LND–CLN link held
down, then again with it up. The failure showed "Payment failed · ₿1,000 is back
in your wallet" and left no History row. Bark 0.8 reused the failed movement
(same id and `createdAt`) for the retry, which paid once (LND settled 1,000;
balance −1,079), but Send showed **"Invoice already paid"** with "Actual fee ₿0":
the attempt's snapshot already held that movement id, so
`SendReconciliation.lightning` returns `AlreadyPaid`. History shows one
Successful −1,000 row dated at the first attempt, with "Ark fee ₿0" (the reused
movement's fee field stays 0 while its effective change is −1,079). A retry that
failed again showed "Payment failed" correctly, and the third try again read
"Invoice already paid". No double payment. [Evidence](ffi060/invoice-retry.json).

The LND–CLN link was held down only during the three failing attempts, with
repeated `lncli disconnect` calls (CLN redials within ~2 s); no channel was closed
and no lifecycle command was run. Those calls raised CLN's redial backoff to 256 s,
so a later single disconnect by another session on the stack (03:37:59Z) took
about four minutes to heal on its own; the peer and channel `170x1x0` were back
and stable at 03:42Z.

This run's liquidity changes were reversed: LND's outbound was returned to its
starting 46,530 sats with CLN payments after the receive stages, and the two
retried 1,000-sat payments were sent back to CLN afterwards. Another session paid
20,000 sats out of LND at 03:34:19Z, which is not reversed here. The owned
emulator, its AVD, the scratch 0.5.0 copy, APK copies, the temporary invoice-retry
probe and host-side scratch were deleted afterwards.

**Not adopted yet** (after the Send/Receive redesign, with a parity review):
`parsePaymentRequest` would sit beside `SendDestination.parse`
(`send/SendDestination.kt:20`): the recipient step in `SendModel`, the
`quoteResolved` guard in `SdkSendWallet.kt`, and `RecurringRecipient.resolve`.
`sendPayment` could replace only the final per-method SDK call inside
`SdkSendWallet.payInternal`, keeping the checkpoint/journal guards and prepared BDK
sends, with `PaymentResult.ark` / `.onchain` mapping to today's receipt fields.
`estimatePaymentFee(s)` would replace the per-method estimators in
`SdkSendWallet.estimateFee` / `quoteResolved`. `bip321Uri` would back
`ReceiveRequest.uri()` and `SdkHandles.receiveRequest` / `receiveInvoice`
(`WalletSession.kt`), keeping RN's receive rules and cancelling abandoned invoices
with `cancelLightningReceive`.

## Recurring occurrence handoff (2026-10-20)

Base `7e88fdd8`. A recurring terminal receipt is now copied into its occurrence's
fsynced journal before success is published or the shared Send slot can be
cleared/reused. Restored receipts use the same handoff. Handoff failure preserves
the receipt and blocks both Done and destructive removal until its evidence is
safe. Done and an unrelated Send cannot affect recurring settlement, which reads
only the occurrence journal. The retained-result cache/API is gone; normal Send
submission, exact-txid journaling/recovery and chain-status evidence are unchanged.
Full format, crash-boundary tests, counts and cleanup are recorded in
[RecurringTests](../RecurringTests/README.md#durable-occurrence-settlement-2026-10-20).


## Real Pixel 6 Send (2026-10-20)

Regtest debug APK on the Pixel 6 (`26011FDF600649`, Android 17), installed with
`install -r` over the upgraded wallet, against the local stack through adb reverse.

- **Locked VTXOs were a pending refresh, not a bug.** All six VTXOs (96,234 sats)
  showed Locked and Send offered 0 sats in Ark. The wallet had joined captaind
  round `a4c0b6e3…` (6 inputs, 96,234 sats, 150-sat refresh fee); regtest had not
  mined since. After one block, Bark settled it with no app action: one Active
  VTXO of 96,084 sats, expiring at block 1091.
- **BOLT11, 2,000 sats.** The 83-sat review fee matches captaind's Lightning
  schedule (75 base + 4,000 ppm at 479 blocks to expiry). The CLN invoice shows
  `paid` with 2,000,000 msat received, and History shows Lightning −2,000,
  Successful. The Ark balance went from 96,084 to 94,001.
- **Exact onchain send, 10,000 sats.** Sent from 50,000 sats funded in one input,
  at 1 sat/vB. The review showed a 143-sat fee, 142 vB and 1 input. Bitcoin Core's
  mempool entry for `b1277a35…` agrees: vsize 142, weight 568, fee 143 sats.
  Outputs are 10,000 sats to the Core address and 39,857 sats change. After one
  block, Check status showed Payment confirmed.
- **Fix: Lightning result sheet.** The sheet read "Payment submitted / Status:
  Submitted" after a preimage-proven Lightning payment. It now says "Payment
  sent" and shows the chain Status row only for receipts with a txid, matching
  iOS. A second payment (1,000 sats, 79-sat fee = 75 + 4) confirmed this on the
  device, and CLN shows that invoice paid.

## Review follow-up: padded URI amounts and final verification (2026-10-20)

The Send-only decimal parser now removes insignificant integer leading zeros and
fractional trailing zeros before enforcing the 32-significant-digit and normalized
scale bounds. Full ASCII-string validation, the 96-character work bound, bounded
exponents/scales and exact 21-million-BTC supply check remain. It then follows
RN's `Math.round(btc * 100_000_000)` semantics, including IEEE-754 halfway cases,
rather than accepting a rounded Decimal approximation. More than eight fractional
digits therefore match RN instead of being silently truncated.

Shared `send-amount-safety.json` now has **108 vectors**. Twenty padded/precision
results (two replace old raw-digit-limit expectations; eighteen are new) were
generated by running RN's pinned **bip-321@0.0.11** and its `btcToSats` expression.
Each records the RN BTC/satoshi result. Leading zeros, trailing zeros, both,
padded zero, one sat, supply maximum, exponent padding, the 96-character boundary,
and excess-fraction rounding are included. For example, `1.000000005` is
100,000,000 sats and `0.000000145` is 14 sats, matching RN's floating-point result.
Regenerate with `bun apps/fixtures/tools/generate-send-amount-padding.ts` using
the pinned client dependency. Both platforms consume the same file.

Targeted Send verification: **560 JUnit cases / 14 suites passed**, no failures,
errors or skips (including all 394 existing RN Send fixtures and 108 amount
vectors). The full Android suite ran **once** after final Android code/test
changes: **1,133 cases / 56 suites passed**, **0 failures, errors or skips**,
including **560 Send cases**. Regtest APK assembly passed in the same **1m 25s**
invocation, without exclusions:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'
```

Signed iOS targeted verification also passed **25 tests / 48 individual runs**
in **5 suites**, zero failures/skips. Full iOS results and cleanup are recorded
in `apps/ios/SendTests/README.md`. No funded payment, Android emulator, physical
device, other simulator, Docker, M6 or Recurring implementation was touched.
Logs and exported summaries are under `/private/tmp/noah-send-r2-*`.

## Review follow-up: blocked migration recovery (2026-10-20)

Base: `feat/native-rewrite` HEAD `99a9177c09ee7da2d7ef3df716f6aff6edc731ff`.
Identity-checked journals remain the active, read-only attempt when migration
persistence fails. **Retry** reuses that retained evidence and re-attempts the
migration without rereading the file, paying, or releasing the Send lock. This
also covers unsafe-outcome normalization; proven v1 receipts retain their proof.
The blocked screen now offers the existing two-step destructive history-check
override, with the full stored-transaction/retry/double-spend warning. Audit must
succeed before removal; either failure retains the original evidence and lock.

`SendConflictTest` adds recovery and blocked-override regressions, including a
single failed v1 write followed by successful Retry, repeated unsafe migration
failure, unavailable rereads, unconfirmed receipt locks, canceled confirmation,
audit/removal failures and original journal contents in the audit. All **7 cases**
pass in the targeted Send run. Round-1 fixes remain intact.

## Round-1 review fixes (2026-10-19)

Base: `feat/native-rewrite` HEAD `c1a1c8aca99f1136864e78d17ca9c0f780f62d6f`.
This section supersedes the older receipt override restrictions and version-1
journal notes below. Behaviour follows iOS Send rounds 7–8 and the SDK 0.4.0
prepared-send contract.

- Broadcast-pending, unlocated and conflicted receipts retain ordinary sending
  and Done locks, but allow the explicit two-step destructive history-check
  override. The confirmation says that forgetting the attempt does not cancel
  Bark's stored transaction or future broadcast retries, and sending again may
  spend twice. Accepted/confirmed receipts and active submissions retain their
  ordinary controls.
- Before removal, `noah-send-overrides.json` durably records the timestamp,
  exact title/warning/confirmation, status/message, original txid, candidates,
  possible txids and complete original journal (including prepared input
  outpoints). Identity-free legacy evidence has `ownershipUnverified: true`
  without claiming the current wallet's identity. Audit history keeps its last
  100 entries. Corrupt audit files are renamed to unique `.unreadable-<UUID>`
  siblings and never deleted. Unreadable active journals and their sidecars are
  copied and fsynced to quarantine before audit/removal; the active file stays
  in place until that sequence succeeds. Quarantine, audit or removal failures
  retain the lock. These files stay in the wallet-private, backup-excluded datadir.
- Session-owned disposal retains the native preparation until discard succeeds,
  blocks claiming/consuming once disposal starts, coalesces concurrent callers,
  and tries at most three times per pass. It outlives caller cancellation,
  including native cancellation failures. Failed quote validation uses the same
  ownership path. Exhausted cleanup retries on counted wallet activity or close;
  close retains the open session/native handles when cleanup still fails.
- Refresh exceptions preserve the last receipt, outcome and durable journal.
  The UI says "Couldn't check the latest status; showing the last known status."
  A successful check clears that warning. Failed sync never promotes cached
  crash-recovery evidence or clears broadcast locks.
- Journal version 2 persists prepared input outpoints. Version-1 journals migrate
  durably without discarding proven receipts or inventing inputs; unversioned or
  unsafe evidence remains conservative. A different **confirmed** transaction
  sharing an original outpoint proves conflict, including after original-tx
  eviction. Older quotes can use cached original transaction bytes. Missing,
  malformed, unrelated and unconfirmed evidence cannot prove conflict. The
  receipt and override name the competitor; conflict retains the lock.
- BIP21/321 amount parsing bounds ASCII syntax, mantissa digits (32), exponent
  and effective decimal scale (-32…32) before conversion, preserving half-up
  satoshi rounding. Arithmetic failures return invalid destinations. All **90**
  shared `send-amount-safety.json` vectors cover extreme exponents, supply limits,
  small/large/negative values, NaN-like text, excess decimals and Unicode digits.
  iOS used the same vectors: its permissive Decimal prefix parsing accepted
  `1abc`, `1e` and `1e+` as 1 BTC; the Send-only parser fix is a separate commit.

Regressions: `SendUnresolvedOverrideTest`, `SendOverrideAuditTest`,
`SendDiscardTest`, `SendRefreshTest`, `SendConflictTest`, `SendAmountTest`,
updated `SendPreparedTest`/`SendSafetyTest` and counted `WalletSessionTest`
activity/close cases. Real temporary files exercise corrupt-byte preservation,
quarantine naming failure and missing-parent write failure; injected directory
sync/removal/native-discard errors exercise the remaining failure boundaries.
The amount vectors and all 394 existing RN Send fixtures passed together
(**484 JUnit cases**, no failures/errors/skips).

The full Android suite ran **once** after the final Android code/test changes:
**1,113 JUnit cases passed**, **56 suites**, **0 failures, errors or skips**.
That includes **540 Send cases** and **20 WalletSession cases**. Regtest APK
assembly passed in the same **3m 55s** invocation, with no exclusions:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'
```

The signed iOS parser/fixture target run passed **7 tests**, zero failures/skips.
The full signed `NoahTests` suite ran **once** after the final iOS changes,
without target/suite exclusions: **378 total, 362 passed, 16 opt-in skipped,
0 failed**, **84 suites**. Parameterized execution reports **476 passed /
16 skipped individual runs**. Both platforms consume the same 90 safety vectors.
iOS changes are limited to `SendDestination.swift` and `SendAmountTests.swift`.

All iOS runs used Nix, normal simulator code signing, `ARCHS=arm64
ONLY_ACTIVE_ARCH=YES`, `-jobs 2` and disabled parallel testing. XcodeGen used its
installed absolute path (`/opt/homebrew/bin/xcodegen`) because the Nix shell did
not expose it. Full-suite command:

```sh
nix develop --command sh -c 'cd apps/ios && xcodebuild -project Noah.xcodeproj -scheme Noah-Regtest -destination "platform=iOS Simulator,id=8A272030-EEBD-4281-94EB-8D12AA45EB6E" -derivedDataPath /private/tmp/noah-send-android-r1-ios-dd -resultBundlePath /private/tmp/noah-send-android-r1-ios-full.xcresult -jobs 2 -parallel-testing-enabled NO ARCHS=arm64 ONLY_ACTIVE_ARCH=YES test'
```

Cleanup completed: the dedicated **Noah Android Send R1 Parser Sol 1fb73e40**
iPhone 17 / iOS 27.0 (`8A272030-EEBD-4281-94EB-8D12AA45EB6E`) was shut down
through the Device panel, its exact identity and Shutdown state verified, then
deleted and confirmed absent. Removed the owned DerivedData and all three iOS
xcresult bundles after exporting summaries. Logs and summary JSON remain under
`/private/tmp/noah-send-android-r1-*`; Android Gradle output stays in this worktree.
No other devices or Docker were changed.

No new funded payment, live RBF/offline-broadcast injection or emulator was needed;
existing live M3 acceptance evidence below remains the live baseline. External
LNURL/BOLT12 and physical-camera limits remain as documented below.

## Initial Android M3 port verification

Ported against `feat/native-rewrite` at `4fa48411`, using the checked-in iOS Send
implementation as the behavior reference and RN copy/flow fixtures as the UX
reference. SDK: bark-bolt-ffi **0.4.0**. Verification ran October 19, 2026
America/New_York (October 20 UTC).

## Verification

The full suite ran **once** after the final production changes: **993 JUnit cases
passed, 0 failures, 0 errors, 0 skipped**, across **48 suites**.
`:app:assembleRegtestDebug` and `:app:compileRegtestDebugAndroidTestKotlin` also
passed in the same invocation (**BUILD SUCCESSFUL**, 53 seconds). No tests or
targets were excluded.

Targeted verification before the full run: **452 JUnit cases passed**, zero
failures/errors/skips: **434 Send cases** and **18 WalletSession cases**.
The Send cases include **394 cases** loaded directly from the RN-generated
`apps/fixtures/send-utils.json`, `send-flow.json`, `onchain-send.json`, and
`lightning-invoice.json`. The existing **48 fiat-currency cases** also run in the
full suite; no fixtures were copied, forked or regenerated.

Final command (the server override is supplied as a Gradle project-property
environment variable for this exact command):

```sh
ORG_GRADLE_PROJECT_noahRegtestServerURL=http://10.0.2.2:3100 \
  nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug :app:compileRegtestDebugAndroidTestKotlin'
```

Live APKs were built with
`-PnoahRegtestServerURL=http://10.0.2.2:3100`; normal dependency verification was
active. CameraX 1.4.2's five resolved AARs are independently downloaded from Google
Maven and SHA-256 pinned. The existing scaffold trust policy for other groups was
preserved.

## Live regtest evidence

Dedicated AVD: **NoahSendM3Sol2a46**, Pixel 6 profile / Android 15 API 35 ARM64.
It initially used `emulator-5560`; agent-device reported an old session claiming
that port for a different, retired AVD. Only our AVD was shut down and restarted
on **emulator-5594**. The Device panel and all subsequent driving used that serial
and the exact host/session flags returned by `device_open`. Existing emulators,
the physical Pixel 6, iOS simulators and Docker lifecycle/configuration were not
changed. Funding and chain inspection used only `just bcli`, `just bark` and
`just cln` through Nix.

Funded once with **20,000 Ark sats** and two onchain outputs of **60,000 + 40,000
sats**. Exactly **two blocks** were mined to confirm funding; no outgoing-payment
confirmations were mined. Signed sends used production Compose, WalletSession,
SDK, controller and durable journal. Public evidence is in
[`evidence.json`](evidence.json); no seed, invoice, preimage or database is included.

- **Ark:** amount-first keypad → recipient → fee review → Confirm. Sent
  **1,000 sats**, zero fee. The journal persisted `submitReturnedArkSuccess`;
  the CLI peer's movement **39** independently reports a successful **1,000-sat**
  Ark receive.
- **BOLT11:** recipient-first with a fresh **1,000-sat** CLN invoice. Receipt
  uses the actual movement: **1,000 sats + 76-sat fee**, `actualMovement` proof.
  `just cln listinvoices` independently reports paid and 1,000,000 msat received.
- **Onchain entered amount:** custom keypad → onchain address → On-chain wallet.
  Native **Back** from the signed review, then Continue, successfully prepared a
  fresh review. Confirm sent **5,000 sats**, **143-sat exact fee**, **142 vbytes**,
  **one input**, txid
  `da7b22e12ea0178ce3bd3fcb78bece143dfa848d86eeb1cd6aabfa551d82bd03`.
  Core `getrawtransaction` recipient outputs/vsize/inputs and `getmempoolentry`
  base fee equal the reviewed quote and persisted receipt exactly.
- **Onchain MAX:** **39,900 sats**, **100-sat exact fee**, **99 vbytes**,
  **one input**, txid
  `35f2f9c4769a88937cc9c912383eba9b1955547752281ac91a948ea2d540cb1c`.
  Core again matches review/receipt. The input is an original confirmed funding
  output: **40,000 confirmed sats** were spent, excluding **54,857 unconfirmed
  change sats** from the entered send. MAX did not spend that change.
- **Kill before Done:** force-stopped `com.noahwallet.regtest` with the MAX
  receipt unacknowledged, relaunched and opened Send. The same full txid,
  **39,900 sats**, **100-sat fee** and **Submitted** status remained. This is a
  kill after the receipt write; crash before receipt write is a regression test.
- The opt-in funded lifecycle test also prepared/discarded/reprepared, refused
  the discarded handle, closed/reopened and refused the pre-close handle.
  Preparation did not broadcast. The durable journal device test exercised
  fsync/AtomicFile, a fresh reader, `.bak` recovery, explicit version storage,
  unversioned-file quarantine and removal.

Screenshots were captured under `/tmp/noah-android-send-{ark-review,backout-review,
entered-review,max-review,max-relaunch}.png`. Private runtime inputs/receipts and
SDK inspection output are removed during cleanup. Public summary JSON is committed
above so the numeric evidence does not depend on temporary files.

## Safety coverage

- Recipient setters clear routes, fixed amounts, notes, suggestions, selection
  and reviews synchronously. Revision checks reject old recipient results.
  Review generations discard late prepared results and ignore old errors after
  exit/background/reset/edit/cancellation. Backgrounding during recipient
  resolution cannot start a preparation afterward.
- Confirmation synchronously locks the process controller. The atomically
  replaced/fsynced attempt precedes any creating/check-only payment API. The
  reservation holds counted, cancellation-shielded session calls through
  inspection, journaling and payment, and excludes replacement/close until the
  call drains. No app payment API is retried automatically.
- Prepared onchain sends use confirmed-only `Amount`/`DrainAll`; exact signed
  amount/fee/vsize/input count are reviewed. The txid is journaled before the
  first/only broadcast. Accepted → Submitted; failed → locked broadcast-retry
  receipt; notAttempted → not submitted. Unknown errors remain uncertain. Only
  the two audited pre-recording prepared-ID/input error codes can unlock.
  Two-minute expiry and all unbroadcast exits release the handle; close joins
  in-flight expiry disposal before closing native wrappers.
- Restart recovery uses only exact prepared/returned txids. Unconfirmed
  crash-recovered transactions retain the broadcast lock until confirmation.
  Failed sync cannot upgrade cached evidence. Confirmation is revalidated after
  a reorg. History differences and Ark/offboard candidates remain uncertain;
  only the explicit two-step destructive history-check override clears an
  uncertain attempt without a proven receipt.
- BOLT11/LNURL inspect pending checkpoints before pay. Canonical invoice, hash,
  amount, fee/total and movement/revocation identity bind adoption; it is
  journaled before `checkLightningPayment`, under the reservation. A vanished
  checkpoint never falls through to pay. Mismatches show the existing real
  amount/fee and stay locked across restart. Offers block on any checkpoint.
- LNURL resolves in-app, checks bounds/whole-satoshi amount/network/expiry and
  validates Ark routes; metadata hash mismatch produces a fixed, nonsecret
  warning. A previously paid invoice requires a real successful movement and
  displays its actual amount/fee.
- Offboard MAX pins reviewed VTXO IDs, labels recipient/fee as estimates, and
  reads actual output/fee only from txid-bound evidence. All six rails have
  separate evidence rules. Successful Ark submission provenance survives restart.
- Versioned attempts/reviews bind fingerprint + network. Session wallet-change
  signals invalidate the process controller after close/create/reopen, covering
  lifecycle deletion/restoration without edits to the concurrent restore UI.
  Restores replace the datadir (the journal is outside SQLite backup snapshots);
  same-wallet suspension/reopen retains the lock. Foreign journals are discarded;
  identity-free journals are quarantined. Failed journal writes/removals fail
  closed. No mnemonic/key/signature/preimage/raw invoice is logged by Send.

## Platform differences and limits

Android uses Compose/Material 3 sheets and native permission/settings controls,
with Noah's existing theme and the RN camera-denial wording. The custom amount
keypad never requests a system keyboard. History repeat prefill uses the existing
shared metadata codec and always returns to recipient validation. The process
controller survives navigation; the session's wallet-change flow supplies the
lifecycle hook (this checkout has no separate WalletLifecycle wallet-changed
callback). Android's journal now uses version 2; version 1 migrates without losing proven
receipts, while unsafe/unversioned records stay conservative rather than importing
iOS journal versions.

SDK limitations match iOS: external offboard chain status and durable Ark/offboard
operation IDs are still unavailable (handoff items 15/16). Offboards retain estimate
copy; only prepared onchain-wallet sends have exact fee/amount binding. Check-only
checkpoint adoption, broadcast failed/notAttempted, crash-before-receipt,
reorgs and filesystem failure injection are deterministic tests, not induced
faults in the shared stack. External LNURL/BOLT12 provider acceptance, a live held
Lightning checkpoint and physical-camera scanning remain unverified. No API 24
emulator or physical-device run was performed; minSdk remains 24 and the date
parser reuses the existing API-24-compatible History parser.

A startup UI repro exposed an immediate-dispatch Compose snapshot race. Deferring
SendRuntime startup until composition is applied fixes it. The initial
accessibility-based instrumentation repro was unusable because agent-device's
UiAutomation was already registered; the retained regression composes the real
Send screen directly and does not compete for UiAutomation. Temporary diagnostics
were removed. Final startup/camera results and cleanup status are recorded below.

## Final device checks and cleanup

The final APK passed the real-screen composition startup regression and the
journal test together: **2 tests, 0 failures**, 5.129 seconds. Across the live
work, **four distinct instrumentation tests passed** (bootstrap, funded prepared
lifecycle, journal and startup), **five passing case executions** including the
repeated journal check. One earlier automation-harness invocation crashed on
UiAutomation registration, before reaching its assertion; it is not counted as
a passing payment test.

On the final APK, camera permission was denied: the scanner retained the RN copy
**“Permission required” / “Camera permission is required to scan QR codes.”**
Allow camera reopened the Android prompt; granting it kept the scanner open and
started the CameraX emulator preview. No physical camera or live scanned payload
is claimed; generated QR frames are decoded in JUnit at 0/90/180/270 degrees.

Cleanup completed: mapped `emulator-5594` with `adb ... emu avd name` to
`NoahSendM3Sol2a46`, shut it down through the Device panel, verified the serial
was absent, then deleted only that AVD. Its `.avd` directory and `.ini` are absent.
Removed the temporary extracted SDK/JAR directory and private invoice, address,
raw-attempt and driving-script files. Gradle output stays inside this worktree;
shared Gradle/SDK caches and all other devices were left alone. Public summaries,
verification logs and screenshots remain under the documented `/tmp` prefix.


## bark-bolt-ffi 0.5.0 network receipts (2026-10-20)

Android now pins verified release `68b88c73` / **0.5.0**. The supplied AAR and POM
SHA-256 values match downloaded bytes; all four LOAD segments in each of
arm64-v8a, armeabi-v7a and x86_64 are `0x4000`.
[Artifact verification](ffi050/artifact-verification.txt).

Ark→onchain `sendOnchain` / offboard receipts now query `Wallet.transactionStatus`
by the exact returned txid. They show **Not yet seen by the network**, **In mempool**,
or **Confirmed at block h**; notFound never means failed or unlocks a payment.
The receipt refreshes while visible and supports Check status. BDK onchain-wallet
receipts query their own `transactionStatus` as additional display evidence:
existing cached-confirmation/reorg/competing-input and failed-broadcast locks still
govern safety. Transport errors retain the last known receipt/status and show an
error. Prepared offboards and durable Ark/offboard operation keys remain absent,
so reviewed estimates, exact VTXO selection and conservative recovery remain.

Live on owned API-35 emulator **NoahFfi050Android4978 / emulator-5592**, wallet
**a9efc44c**, funded only through Core. Production SendController + SendJournal
submitted **10,000 sats** from Ark to a fresh Core address. The exact receipt txid
is `a74ed9841a2479601958fab04132b29fcda77fe885ff28e5b9a4cc7400a6dd8d`.
Both the reopened real Send sheet and Core showed it **in mempool**. After exactly
one `just generate 1`, the visible sheet refreshed automatically to **Payment
confirmed / Confirmed at block 629**. Core reports one confirmation in block
`21459fd807ab7ca0aabd72561ca241f7c3aeb67ea7838e72b16eb342f95ad321`.
The txid and 10,000-sat recipient amount remained unchanged. Bark's movement fee
shown in the receipt is **2,580 sats**; Core's funding transaction fee is **1,853
sats**, vsize **185**. These are different fee scopes; offboards retain the existing
movement-fee receipt convention.

[Mempool receipt](ffi050/offboard-mempool.json), [visible sheet](ffi050/offboard-mempool.png),
[Core mempool entry](ffi050/offboard-getmempoolentry.json),
[confirmed receipt](ffi050/offboard-confirmed.json), [visible confirmation](ffi050/offboard-confirmed.png),
[Core confirmation](ffi050/offboard-confirmed-core.json). Core evidence contains
amounts, txids, outpoints and statuses, without signed transaction bytes.

Prepared amount/MAX boarding, exact Core comparisons, Auto-Board reserve,
terminal exit reopening and cleanup are recorded in
[Android ExitTests](../ExitTests/README.md#bark-bolt-ffi-050-adoption-2026-10-20).

Required full check ran once:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'
```

**1,376 tests in 63 suites passed; zero failures, errors or skips**, with APK
assembly successful (**59 seconds**). [JUnit totals](ffi050/junit-summary.json).
Earlier targeted Send/M6/session verification passed **820 tests**, and the final
M6 model/operation checks and instrumentation-source compilation passed. A final
receipt-only display addition (showing the prepared Ark fee/net/total alongside
the chain fee) was separately compiled/assembled after the full run.

Added deterministic JUnit coverage includes exact review/submission identity,
durable txid-before-submit, outcomes and retry locks, Auto-Board reduced-gross
reserve, typed construction failures, two-minute expiry, late generations/errors,
coalesced cancellation/discard, accepted-cache persistence repair, crash recovery
by exact network/pending/cached identity, claimed/canceled SDK rows and chain
status/reorg/error preservation. Shared RN fixtures remain unchanged.

Limits: live failed/notAttempted/cache-write/transport faults and a killed process
mid-broadcast were not induced in the shared stack; deterministic tests cover
those cases. A new live claimed exit was not mined through its 144-block timelock;
claimed-row and claim-receipt status behavior is covered in JUnit. No physical
phone, iOS, server, RN or recurring-payment code was changed.

The fingerprint-scoped live harness passed **6 stage executions**, zero failures/skips: create, auto-review, cancel-cycle, cancel-reopen, offboard and offboard-status. The last stage reopened the final APK and retained the same txid / Confirmed at block 629 receipt. The two board submissions and Auto-Board decline also ran through the real Compose UI.

Cleanup completed: verified `adb -s emulator-5592 emu avd name` was **NoahFfi050Android4978**, shut down that Device-panel device, confirmed its serial absent and ports closed, then deleted its AVD. Both AVD directory and ini are absent. Removed the task-owned SDK download/extraction and scratch files outside this worktree; shared Gradle/Android caches and all other devices/containers remain intact. [Cleanup evidence](../ExitTests/ffi050/device-cleanup.txt).

Final chain-status guard verification: **3 targeted SendChainStatusTest cases passed**, including absent/mempool evidence preserving unresolved locks; APK rebuilt successfully in 6 seconds. The full suite was not repeated. [Build log](ffi050/gradle-chain-guard.log).

## 0.5.0 review fixes, round 1 — durable receipt before status

Submission now returns its exact txid/proof without a network-status lookup.
SendController saves that receipt before the optional lookup and persists the
returned status separately; a failed receipt write skips the lookup. A blocked
lookup can be interrupted without losing the exact identity used after restart.
Deterministic regression covers Amount/MAX offboards and BDK sends, plus receipt
write failure. Targeted SendRefreshTest, SendSafetyTest and SendChainStatusTest:
**17 tests passed, zero failures/errors/skips**. No live process kill was induced.

### Offboard status parity

Exact offboard receipts now query Bark status without BDK sync or graph reads,
matching iOS. BDK sends still require a successful sync before cached confirmation,
reorg or competing-input evidence is considered; status alone never clears their
retry lock. Regressions make both BDK reads fail while offboard status remains
available, and verify BDK sync failure/reorg rules. SendChainStatusTest,
SendConflictTest, SendRefreshTest and SendOffboardTest: **19 tests passed**,
zero failures/errors/skips.

### Round-1 final Android verification

Ran the requested full check exactly once after all code changes:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'
```

**2,770 tests in 70 suites passed**, zero failures/errors/skips; regtest APK
assembly succeeded in the same **1m 4s** run. Final targeted runs for fixes 1–5
passed **17 / 40 / 19 / 61 / 1,357 tests**, respectively (overlapping suites).
No live device, funded broadcast or actual process kill was used; interruption
and recovery were checked deterministically. No iOS, server or RN files changed.


## 0.5.0 review fixes, round 2 — BDK downgrades survive status errors (2026-10-20)

Base: local `feat/native-rewrite` HEAD `51c24bc0`. Fix: `556f9840`.
After a successful BDK sync, eviction and confirmed competing-input evidence now
reach SendController and its durable journal even when the subsequent network
status lookup throws. The lookup error is saved separately on the updated
receipt, retaining its last known chain status. The existing Send receipt UI
shows the shared warning: **Couldn't check network status. Showing the last
known status. Try again.** A successful lookup clears that warning. Behaviour
follows iOS `beb2586e`: chain status adds evidence and cannot block a BDK-required
downgrade. Failed BDK sync still gates cached evidence.

Two new SendConflictTest regressions start with a confirmed receipt and no retry
flag, then sync eviction or a confirmed competitor before the status lookup
fails. Both assert durable unlocated/conflicted receipts, preserved chain status,
separate lookup errors and held acknowledgement/restart locks. The same checks
cover notFound, mempool and confirmed responses; none overrides BDK's downgrade.
A subsequent successful lookup clears only the warning. Both new tests failed
before the fix. Targeted SendConflictTest, SendChainStatusTest, SendRefreshTest and
SendOffboardTest: **21 tests / 4 suites passed**, zero failures/errors/skips.

Board crash recovery with optional status errors is recorded in the
[ExitTests round-2 section](../ExitTests/README.md#050-review-fixes-round-2--exact-board-recovery-despite-status-errors-2026-10-20).

The requested full check ran **once**, after both fixes and all test changes:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'
```

**2,773 tests / 70 suites passed**, zero failures/errors/skips, with no suite
exclusions. Regtest APK assembly succeeded in the same **2m 25s** invocation.

No device, emulator, simulator, Docker command, funded broadcast or live process
kill was used. Crash/status faults use deterministic tests. Changes are limited
to Android Send/Onchain code, tests, these verification docs and Android TODO
lines; recurring, iOS, server and RN code were untouched.

## Initialized launch I/O (base `37ab2125`)

The session's suspension preflight now joins the same non-cancellable IO context
as its counted reservation; its monitor, Send mutex, drain and teardown ordering
are unchanged. Journal loading retains the synchronous blocked state until the
entire reservation returns. Only the matching wallet-change generation may
publish its result. Failed migration still retains the original read-only evidence.
Acknowledgement, quarantine and override auditing are awaited on IO; the controller
remains locked/checking until durable removal succeeds. Override evidence is
captured before suspension, and audit still precedes removal.

`SendLoadingTest` covers held reads, stale empty reads after wallet invalidation,
and pending removal; `WalletSessionTest` holds the suspension read off the caller
thread and proves that no reservation is granted on its suspended result. Existing
journal-before-payment, migration-failure, audit-failure and counted-session tests
remain in the targeted runs. Funded-device StrictMode counts and final verification
are recorded in [ParityTests](../ParityTests/README.md#initialized-wallet-main-thread-io-base-37ab2125).

## Launch-threading regression fixes (base `1d9aff5d`)

Acknowledgement and the destructive history-check override now acquire the same
counted Send session reservation as journal loading/submission. They validate the
captured controller generation and owning wallet after reservation acquisition and
after the suspending identity lookup, before quarantine, audit or removal. Audit
and removal retain ownership until completion, so lifecycle drain cannot finish
and recreate the datadir underneath an old clear. Unbound/unreadable evidence uses
the wallet identity captured during load; it never authorizes deleting another
wallet's journal. Failure keeps the lock and evidence.

The journal-write audit covered foreign-wallet removal, unsafe-outcome/v1
migration, Lightning inspection markers, pending attempts, recurring stopped
attempts, receipt/status persistence and refresh/reconciliation. Those paths
already retain counted reservations. Added generation checks after suspending
identity/preparation/payment/receipt refresh prevent a stale operation from
writing/publishing into a new controller generation; stale preparation also
releases its review. Quarantine and audit were the other unreserved mutations;
both now share clear's reservation. Recurring acknowledgement names the exact
attempt captured before settlement I/O and cannot clear a replacement payment.

`SendJournalOwnershipTest` derives its delayed-clear race from the review probe.
It **failed before** the fix when replacement overtook removal and **passes after**
the fix, with replacement waiting for the old clear and retaining its own receipt.
Further cases delay reservation/identity for both acknowledgement and override,
with and without invalidation, and invalidate a pending preparation. They assert
zero stale mutation/submission and preserved replacement evidence.

`WalletSessionUiReadTest`, also probe-derived, **failed before / passes after**:
`isOpen` returns while an IO suspension read owns the session monitor. Session
state is volatile; UI reads take no monitor. Observer subscription/cleanup,
close/settled-close, direct create, and restore reservation setup run on IO;
action-time suspension checks and counted drain remain authoritative. Existing
creator, lifecycle and restore-recovery reservation call sites already run on IO.

Final counts and owned-emulator StrictMode evidence are in
[ParityTests](../ParityTests/README.md#launch-threading-review-regressions-base-1d9aff5d).

The final full check ran once: **2,899 tests / 88 suites passed**, zero failures/
errors/skips, with APK assembly in the same run. Owned-AVD launch/Home/Send/
Recurring/foreground StrictMode samples all measured zero violations; the wallet
was initialized and unfunded. No live payment or crash was induced.
