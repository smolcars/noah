# Native Send success receipt parity (2026-10-21)

Implementation: `db692822`, based on `300b3193`. Both success screens show an optional
fiat estimate, Paid via, To, Transaction ID (preferred) or Payment preimage, and
Completed. Copy buttons retain full values while displaying the first 14 and last
10 characters for values longer than 32. IDs are `send-success-copy-destination`,
`send-success-copy-proof` and `send-success-done`.

The new presentation models read the preimage from the exact outgoing successful
History movement identified by the receipt's movement ID. They never match by
amount or destination. Completion uses the matching History date when available,
otherwise the attempt's start time. No receipt, journal, lock, reconciliation,
retry, wallet SDK, logging or telemetry implementation changed. No persisted model
changed, so the existing receipt formats and decode behavior remain unchanged.
Existing submitted/confirmed/broadcast-pending copy, fee evidence, status refresh,
selectable txid and iOS explorer link remain. Android also exposes the equivalent
network explorer link outside regtest. Already-paid and failed outcomes are unchanged.
History's copyable rows are reused, preserving their default appearance and behavior.

Translations ran offline with `bun apps/tools/translations.mjs seed`, generation,
and `--check`. RN seeded the three newly inventoried labels; Transaction ID and
Completed already existed. Zero untranslated keys in es/ja/ko/sv on both platforms.
No paid translation command ran.

## Source map

- iOS `apps/ios/Noah/Features/Send/SendSuccessDetails.swift:14` — proof selection, identity, date and truncation.
- iOS `apps/ios/Noah/Features/Send/SendView.swift:390` — success display, fiat and History lookup; new rows at line 530.
- iOS `apps/ios/Noah/DesignSystem/NoahDetailRow.swift:3` — extracted existing History copy row; tests in `apps/ios/NoahTests/SendSuccessDetailsTests.swift:5`.
- Android `apps/android/app/src/main/java/com/noahwallet/send/SendSuccessDetails.kt:10` — corresponding presentation model.
- Android `apps/android/app/src/main/java/com/noahwallet/ui/SendScreen.kt:261` — success display and History lookup; new rows at line 303.
- Android `apps/android/app/src/main/java/com/noahwallet/ui/TransactionDetail.kt:144` — existing shared detail row, extended with a display value and copy-button modifier; tests in `apps/android/app/src/test/java/com/noahwallet/send/SendSuccessDetailsTest.kt:10`.

## Automated verification

Full suites ran once after the final native source/test changes:

- Android requested Gradle command: **3,661 passed / 0 failed, errors or skipped**
  across **158 suites**; regtest APK assembly and Android-test Kotlin compilation
  passed. Separate Send-only run: **582 passed / 18 suites**, no failures/skips.
- iOS XcodeGen then Noah-Regtest `test`: **852 total / 825 passed / 27 opt-in skips /
  0 failed**, **173 suites**; expanded execution **1,677 passed / 27 skipped**.
  Separate Send-only run: **113 total / 106 passed / 7 opt-in skips / 0 failed**,
  **24 suites**; expanded execution **188 passed / 7 skipped**.
- New presentation coverage: **5 tests per platform** (Swift: **9 executions**).
  Covers Lightning/LNURL/offer proof; onchain/Ark txid and txid priority; missing,
  empty, unrelated, incoming and unsuccessful proof; exact movement identity;
  completion date; truncation at 32/33+ and full copy values.
- Repository commit hooks passed: client lint, React Compiler lint, **148 client
  unit tests**, TypeScript and `cargo fmt --check`. No client/server files changed.

Xcode used two jobs, signed arm64 simulator builds, disabled parallel testing and
private DerivedData `/private/tmp/noah-send-success-dd`. After all full-suite tests
passed, Xcode stalled collecting `simctl diagnose` for the owned simulator. Only
that verified diagnostic child was terminated; Xcode then finalized a successful
result bundle. Its summaries and test trees were retained outside the repository.
An existing StoryPlaybackTests AVAudioSession main-thread warning was emitted.

## Live regtest evidence

Only the already running stack was used; no Docker lifecycle command ran. Funding
and invoices used the repository `just bcli`, `just bark` and `just lncli` wrappers.
The existing opt-in FFI050 create stages initialized empty disposable wallets
without displaying a recovery phrase. Each wallet received 100,000 Ark sats and
50,000 onchain sats; exactly one block was mined by this task to confirm both
onchain funding transfers. Other work advanced the shared chain independently.

Both platforms sent 1,000 sats to LND, 1,000 over Ark to Bark and 1,000 from the
onchain wallet to Core. Lightning's actual fee was 79 sats; onchain's was 143 sats
(142 vB, one input). Full preimages copied from the production success buttons
matched LND's settled invoices in memory; no preimage was logged or saved in JSON.
Full Send txids copied from those buttons matched full txids copied from History.
Core independently confirmed the same txids and 1,000-sat recipient outputs.
Ark SDK receipts have no txid, so the proof row is correctly absent.

The owned Android AVD crashed during onchain preparation, before confirmation.
It was restarted with its existing data. The Send journal was absent, and the
unsubmitted review was recreated; no payment was resubmitted. Theme recreation
also produced two transient privacy-screen captures, which were replaced using a
fresh 1,000-sat Ark payment and a fresh 1,000-sat LND invoice. All spends were on
regtest and new invoice/destination identities were used for those replacements.

Screenshots are retained under `/private/tmp/noah-send-success-shots/`. Main
captures show status/amount and receipt rows; supplemental onchain detail captures
show the lower proof/Completed/refresh/Done area. These are actual device images,
with no phrase screen captured. Public JSON, summaries and build/hook logs are in
`/private/tmp/noah-send-success-evidence/`.


- `ios-lightning-light.png`, `ios-lightning-dark.png` — Lightning success with truncated preimage and fiat.
- `ios-ark-light.png`, `ios-ark-dark.png` — Ark success with destination and no proof row.
- `ios-onchain-light.png`, `ios-onchain-dark.png` — onchain status, amount and actual fee.
- `ios-onchain-light-details.png`, `ios-onchain-dark-details.png` — onchain proof, Completed, full selectable txid and Done.
- `android-lightning-light.png`, `android-lightning-dark.png` — Lightning success with truncated preimage and fiat.
- `android-ark-light.png`, `android-ark-dark.png` — Ark success with destination and no proof row.
- `android-onchain-light.png`, `android-onchain-dark.png` — onchain status, amount and actual fee.
- `android-onchain-light-details.png`, `android-onchain-dark-details.png` — onchain proof, Completed, refresh and Done.

The read-only History lookup may temporarily omit the preimage if History is
unavailable; it never changes the proven Send result. Mainnet/signet explorer
navigation and broadcast-pending fault injection were not repeated live; existing
Send tests cover the status/lock behavior. No physical device was used.

Cleanup verified each device's exact identity before shutdown, confirmed shutdown,
then deleted it and verified absence:

- iPhone 17 / iOS 27.0: **Noah Send Success 4c97**, `1E95603C-FB6B-4749-88FC-9D56BA8727CC`.
- Pixel 7 / Android 15: **NoahSendSuccess4c97**, `emulator-5640` (console 5640, gRPC 8640).

The private iOS DerivedData and all four owned `.xcresult` bundles were removed.
All sixteen public screenshots and sanitized evidence remain. Other simulators,
emulators, physical devices and the running Docker stack were untouched.
