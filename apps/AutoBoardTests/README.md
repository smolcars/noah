# Native repeat Auto-Board verification — 2026-10-21

Native-only fix, based on `300b3193` (`feat/native-rewrite` at task start).
Android `3bf66526`, iOS `57902c6b`; foreground admission follow-up `c3da2ac8`. Final verification is recorded below. No `client/` or `server/` source changes.

## Funding identity and migration

Both apps persist `native-auto-board-funding-v1` in their existing atomic native
settings file: wallet fingerprint → a set of `txid:vout` strings. Eligible funding
comes from the native onchain wallet's confirmed **local** UTXOs whose transaction
has positive net balance change. Unconfirmed outputs, exit pseudo-outputs, unknown
transactions and board/send change do not create new deposit prompts. Spent
outpoints remain in the ledger, so a later reappearance cannot replay a prompt.
Both apps consume `apps/fixtures/auto-board-funding.json` in their unit tests.

The RN `hasAttemptedAutoBoarding` field remains readable for compatibility but no
longer controls the native prompt. An old `true` with no native funding ledger
allows one review of current eligible funds, after pending and unresolved board
gates pass. Disabling/re-enabling the toggle never resets the funding ledger.

`AutoBoardReview` rechecks the live caller after each async read/preparation.
It persists funding and installs the review in one synchronous UI-actor step only
while enabled, foreground, ready, current and without another review. A preparation
or estimation error leaves funding unhandled, releases preparation ownership via
the existing cleanup path, and imposes a **30-second**, process-local retry backoff.
The next eligible foreground timer tick can retry; no durable in-progress lock is
introduced. Once displayed, confirmation, dismissal, expiry, navigation,
backgrounding and submission failure cannot replay that funding. Summary refreshes
leave the visible quote, reservation and original 120-second deadline intact.

## Money safety and rejected boards

The apps still prepare with `confirmedOnly = true`, reserve exact inputs, show
exact signed amounts/fees, require net ≥ max(server minimum, 20,000 sats), and
retain ≥5,000 sats. Confirmation checks wallet identity, session ownership and the
120-second deadline. Automatic confirmation rereads confirmed balance; a decrease
rejects the review before claiming it. Added funds do not alter its signed amount.
The SDK also checks the reviewed outpoints against both local and chain spends
before submitting, so another outgoing transaction cannot silently replace inputs
or submit a newly sized amount. Initial-sync and insufficient-funds copy is kept.

Manual and automatic submissions share the same journal admission and session
preparation ownership. Android serializes onchain mutations; iOS's journal actor
holds its running gate across submission. Prepared IDs are single-claim, and the
SDK's input reservations also cover overlapping sends/boards. The exact reviewed
txid is durably recorded **before** entering `submitPreparedBoard`.

A successful receipt with an exact matching txid, a confirmed chain status and no
broadcast retry no longer blocks a new board. Its receipt remains separately
available; Done is optional. Unknown/unreadable journals, unresolved attempts and
broadcast retries remain blocked. No amount matching or automatic resubmission is
used. After app death during submit, the stored exact txid is reconciled against
SDK/chain evidence; absent evidence remains uncertain until the existing explicit
override. SDK delivery retries and cache recovery use the original transaction/ID.

FFI **0.5.0** pins Bark **bfbe6b7c3088a09dd4e81c4ac24cdc763a627a8c**.
In that revision `bark/src/board.rs` performs minimum/fee/cosign checks before
creating the board movement/checkpoint, and returns success after durable
acceptance even if its initial drive fails. FFI
`src/core/prepared_board.rs:163` checks exact input availability and chain spends;
`:301` maps a pre-acceptance `board_psbt` rejection to `NotAttempted`. Native
handling removes only a definitely unsubmitted attempt (`NotAttempted` or the
existing typed prepared-input/identity/parameter failures). Cleanup keeps ownership
if release fails. Unknown errors and accepted-but-unconfirmed delivery never use
this release path. Regression tests explicitly reject a board, then successfully
submit a later manual **and** automatic board.

## Regtest procedure and results

Used the already-running Docker stack; no start, stop, reset or container lifecycle
operation. Funding used `just bcli sendtoaddress <owned-app-address> 0.00150529`.
Each deposit was confirmed separately with `just bcli generatetoaddress 1` to a new
**Core-owned** `getnewaddress`, never an app address. Each accepted board was given
three confirmations, matching `required_board_confirmations = 3`.

Owned devices only:

- iOS: **Noah AutoBoard Sol f1c98**, iPhone 17 / iOS 27.0,
  `C3FB94EA-45EF-4FE4-ADBA-3A93B6CD3C9A`.
- Android: **NoahAutoBoardSolf1c98**, Android 15, console **5660**,
  serial `emulator-5660`.

Wallet setup used opt-in native test code, never the phrase UI. Both started with
Auto-Board enabled and the old attempted flag set to true. No recovery phrase was
displayed, captured, exported or logged. Existing devices/physical hardware were
untouched.

1. Three separate **150,529-sat** deposits on each platform produced three
   foreground reviews and exactly three boards after explicit confirmation.
   Never pressed Done. First review: gross **145,374**, Ark fee **330**, chain fee
   **155**, net **145,044**, reserve **5,000 sats**. Second and third: gross
   **150,316**, Ark fee **330**, chain fee **213**, net **149,986**, reserve
   **5,000 sats**. Core independently matched all six exact input sets, vsize,
   chain fees and gross funding outputs. Android stayed foreground for timer
   discovery; no pull-to-refresh was needed.
2. A fourth **150,529-sat** deposit prompted once on each. Dismissed via iOS sheet
   gesture / Android system Back, keeping Auto-Board on. Both retained three
   receipts and four handled deposit outpoints. Terminated and reopened both apps;
   the same funding did not prompt again. The final admission builds also reopened
   with that funding suppressed.
3. On final build `c3da2ac8`, a fifth **150,529-sat** deposit produced a new review
   on both apps. Public snapshots about a minute apart retained the same amounts
   and review across the foreground refresh cadence, without a submission. iOS
   then confirmed one board: gross **300,787**, Ark fee **401**, chain fee **271**,
   net **300,386**, reserve **5,000 sats**, including the dismissed fourth deposit.
   Android reached the original 120-second expiry before the confirmation tap;
   the sheet closed, no board was submitted and its five funding outpoints stayed
   handled. A sixth **150,529-sat** Android deposit then prompted once and confirmed
   one board: gross **451,258**, Ark fee **552**, chain fee **328**, net **450,706**,
   reserve **5,001 sats**. No Done press anywhere. Both wallets finish with exactly
   four receipts. Core independently confirms and matches all eight boards.

Android hit an ANR opening the OS battery-settings screen before funding. Only the
owned app was force-stopped; its battery whitelist was set on the owned emulator,
and subsequent wallet flows succeeded. Backup failures from the local server were
visible independently of boarding and were not changed by this fix.

## Final verification and cleanup

Final focused iOS admission/rejection/journal/settings: **37 tests / 55 expanded
executions**, zero failures or skips. Both focused Android admission/rejection
suites passed. The exact requested Android full command ran once after the final
source changes: **3,671 tests / 157 suites**, zero failures, errors or skips; APK
assembled and Android-test Kotlin compiled. The full iOS `Noah-Regtest` suite ran **once after final source changes**, following
XcodeGen, signed arm64 with `ONLY_ACTIVE_ARCH=YES`, two jobs and parallel testing
disabled, using private DerivedData `/private/tmp/noah-autoboard-sol/dd`:
**860 total / 833 passed / 27 explicitly gated skips / zero failures**, across
**172 suites**; **1,682 expanded passed executions / 27 skipped**.
`xcresulttool get test-results summary` supplies both counts. The owned iOS
simulator's exact name, UDID, type and **Shutdown** state were checked immediately
before deleting it; absence was verified. Android was identity-checked via console name and serial, shut down
through the Device panel, verified absent from adb, matched against its AVD path
and manager listing, then deleted and verified absent. Full evidence is under
[`evidence/`](evidence/). No physical-device or external-network testing. Process death during
broadcast is simulated by durable-state unit tests, not an actual mid-broadcast
kill. Real post-dismissal process death/reopen was exercised on both platforms.

## Source map

Review follow-up (orphan board movement): pinned Bark can save a board movement
before persisting its checkpoint. iOS previously treated that movement's
`chain_anchor` as submission evidence, creating a broadcast-retry receipt that
could never resolve or be explicitly overridden. Reconciliation now accepts only
an exact checkpoint/local-wallet transaction txid or mempool/chain evidence;
movement metadata alone leaves the journal uncertain and its existing explicit
override available. A real offline SDK-wallet regression inserts the orphan
movement and output ID, confirms there is no checkpoint, local transaction or
stored VTXO, and checks recovery against a loopback `notFound` response. Separate
cases preserve checkpoint, BDK, mempool and chain receipts. Android already
requires a stored output VTXO when resolving movement IDs and never reads
`chain_anchor`; its production code is unchanged, with an added missing-evidence
override guard. Exact pre-submit journalling, fail-closed reads, reservations and
single-use preparations are unchanged. Each requested full suite ran once after
the final source change: Android **3,677 tests / 158 suites**, zero failures,
errors or skips, APK assembled and Android-test Kotlin compiled; iOS **867 total /
840 passed / 27 gated skips / zero failures**, **174 suites / 1,696 expanded
passed executions**. Focused iOS recovery/safety checks passed **53 tests / 80
expanded executions**. The dedicated iPhone 17 / iOS 27.0 simulator
**Noah Orphan Board t3-1d8f55ce** (`997DE09B-F634-4CEB-9611-2E1311FC53DF`)
was shut down, identity-checked and deleted; deletion was verified. No Docker,
physical devices or other agents' devices were used for this follow-up.

Android:

- `app/src/main/java/com/noahwallet/onchain/AutoBoardFunding.kt:5` — persisted
  outpoints; `:37` — foreground admission, post-await checks and retry delay.
- `onchain/AutoBoardPrompt.kt:57` — lifecycle and open-review gates; confirmation
  continues through the existing lifecycle mutation/session path.
- `onchain/OnchainOperationJournal.kt:54` — resolved-board blocking predicate.
- `onchain/OnchainOperations.kt:29` — exact receipt reconciliation and pending-board
  gate before reading confirmed funding; existing execute/reserve path is retained.
- `recurring/RecurringForegroundRunner.kt:28`, `recurring/RecurringRuntime.kt:44`,
  `MainActivity.kt:114`, `ui/AppModel.kt:318` — foreground summary publication and
  a revision that also detects changed funding with an equal aggregate balance.
- `OnchainModelTest`, `OnchainJournalTest`, `OnchainOperationsTest`,
  `RecurringForegroundRunnerTest`, `SettingsFeaturesTest` — parity, persistence,
  admission/refresh/backoff, rejection/retry and migration regressions.

Paths above are relative to `apps/android/app/src/main/java/com/noahwallet/`
except tests (`app/src/test/java/com/noahwallet/`).

iOS (relative to `apps/ios/Noah/`):

- `Features/Onchain/AutoBoardFunding.swift:5` — funding ledger;
  `:43` — foreground admission and retry delay.
- `Features/Onchain/BoardArkView.swift:63` — summary/lifecycle observation; the
  visible quote survives summary updates and keeps its original expiry.
- `Features/Onchain/OnchainOperationJournal.swift:27` — resolved-board admission
  while retaining the receipt.
- `Services/Wallet/WalletSession+Onchain.swift:82` — reconciliation, pending-board
  gate and native funding snapshot; prepared execution remains unchanged.
- `App/AppModel.swift:259` — fresh-summary revision.
- `NoahTests/BoardPreparedTests.swift`, `SettingsFeatureTests.swift`,
  `OnchainSafetyTests.swift` — shared fixtures, presentation/refresh/backoff,
  rejected-board recovery, overlap, migration and journal regressions.

Verification commands:

```sh
nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug :app:compileRegtestDebugAndroidTestKotlin'
cd apps/ios && /opt/homebrew/bin/xcodegen generate
xcodebuild -project Noah.xcodeproj -scheme Noah-Regtest \
  -destination 'platform=iOS Simulator,id=C3FB94EA-45EF-4FE4-ADBA-3A93B6CD3C9A' \
  -derivedDataPath /private/tmp/noah-autoboard-sol/dd \
  -resultBundlePath /private/tmp/noah-autoboard-sol/ios-full.xcresult \
  -jobs 2 -parallel-testing-enabled NO ARCHS=arm64 ONLY_ACTIVE_ARCH=YES \
  CODE_SIGN_IDENTITY=- test
```

All commits ran the installed hooks (`bun client check`, including 148 RN unit
tests, and `cargo fmt --check`); no `--no-verify`, no push. The initial Android full
run preceded the additional foreground-admission review; final counts above come
from the single completed run after the final source changes. One superseded
Android run was interrupted while tightening that admission path. Public evidence
and result summaries were exported before removing owned DerivedData, result
bundles and scratch logs. No Docker or unrelated-device cleanup was performed.
