# Native behaviour parity, 2026-10-20

Base: `feat/native-rewrite` at `10c0b996`. Implementation stayed in the isolated
`t3/native-behavior-parity` worktree. No SettingsStorage/native-store, launch-gate,
recurring, `client/`, or `server/` files were changed. The WalletTabs portion of
RootView changed only for repeat handoff and Send visibility.

1. **Home balance header.** Pending balance appears only when nonzero. Details expand
   independently of balance visibility; every displayed amount is masked when hidden.
   Totals continue to use SDK accounting: Ark total includes spendable, needs refresh,
   and every pending operation; pending total includes all pending Ark buckets plus
   unconfirmed/immature onchain funds. Details retain RN labels and expose the additional
   SDK Ark buckets so totals do not silently omit funds.
   - iOS: `apps/ios/Noah/Features/Home/HomeView.swift:204`;
     bucket rules in `apps/ios/Noah/Services/Wallet/WalletBalance.swift:96`.
   - Android: `apps/android/app/src/main/java/com/noahwallet/ui/HomeScreen.kt:98`;
     bucket rules in `apps/android/app/src/main/java/com/noahwallet/services/wallet/WalletBalance.kt:38`.
   - Limitation: SDK 0.5.0's OnchainBalance has only confirmedSats, pendingSats, totalSats.
     Both apps show combined **Pending**, and derive **Immature** as total minus confirmed
     minus pending. The exact RN Trusted Pending / Untrusted Pending split remains open
     until FFI exports it. No guessed values or altered dependency were introduced.

2. **Home background strip.** The RN copy, “Background task in progress...”, follows
   WalletBackgroundActivity and disappears on completion. iOS already had a progress
   label; this puts it in the existing card treatment. Android now subscribes to the
   existing activity stream and shows a progress strip.
   - iOS: `apps/ios/Noah/Features/Home/HomeStatusViews.swift:62`,
     `apps/ios/Noah/Features/Home/HomeModel.swift:108`.
   - Android: `apps/android/app/src/main/java/com/noahwallet/ui/home/HomeBanners.kt:74`,
     `apps/android/app/src/main/java/com/noahwallet/services/wallet/WalletBackgroundActivity.kt:10`.

3. **iOS repeat payment.** Home and History share the same handoff. The pending value
   is consumed once, only when the Send draft is editable. Prefill resets the prior
   route/review, restores destination/comment and amount, preserves fiat input only
   for the matching currency, and opens the normal recipient step. Continue performs
   normal parsing, resolution, amount validation and rail selection; a note still
   requires Lightning. Review and confirmation remain mandatory.
   - iOS: `apps/ios/Noah/Features/Shell/RootView.swift:103`,
     `apps/ios/Noah/Features/Send/SendModel.swift:319`.
   - Android's existing reference behavior remains at
     `apps/android/app/src/main/java/com/noahwallet/send/SendModel.kt:154`.

4. **Send during a refresh round.** Send and Home show the pending-in-round amount
   and explain that these funds become available after the round confirms. Zero pending
   funds show no explanation. Spendable amounts update with the same summary as the
   explanation; Home continues to mask the explanation's amount when hidden. Send does
   not mask it: like RN, "Hide wallet balance" (`isHomeBalanceHidden`) applies to Home
   only, and Send already shows the spendable amount unmasked because the user needs it
   to pay (`client/src/screens/HomeScreen.tsx` is RN's only reader).
   - iOS: `apps/ios/Noah/Features/Send/SendView.swift:159`,
     `apps/ios/Noah/Features/Home/HomeView.swift:261`.
   - Android: `apps/android/app/src/main/java/com/noahwallet/ui/SendScreen.kt:120`,
     `apps/android/app/src/main/java/com/noahwallet/ui/HomeScreen.kt:102`.

5. **Send/Board alignment choices.**
   - **Receipt refresh: Android behavior.** Both refresh immediately when a Send receipt
     becomes visible, then every ten seconds, as well as the existing post-submit refresh.
     iOS observation cancels when tab/scene/wallet/receipt visibility changes.
     iOS: `apps/ios/Noah/Features/Send/SendView.swift:13`,
     `apps/ios/Noah/Features/Send/SendReceiptRefresh.swift:4`.
     Android: `apps/android/app/src/main/java/com/noahwallet/ui/SendScreen.kt:55`.
   - **Network copy: iOS behavior.** Explanations and repeat-payment warnings match,
     including notFound, mempool, confirmation, broadcast-pending and uncertain outcomes.
     iOS: `apps/ios/Noah/Services/Wallet/ChainTransactionStatus.swift:14`,
     `apps/ios/Noah/Features/Send/SendPayment.swift:39`.
     Android: `apps/android/app/src/main/java/com/noahwallet/services/wallet/ChainStatus.kt:10`,
     `apps/android/app/src/main/java/com/noahwallet/send/SendPayment.kt:36`.
   - **Lookup errors: transient, recoverable display state on both screens.** A failed
     lookup preserves the last receipt/evidence and leaves an error beside it with the
     existing status retry. A successful check clears the error. Send moves errors into
     controller message state; Board retains them in memory by attempt ID. Serialization
     excludes these fields. Restarts retain exact txids, receipts, conflicts and locks,
     and perform a fresh lookup rather than treating an old error as evidence.
     iOS: `apps/ios/Noah/Features/Send/SendPayment.swift:399`,
     `apps/ios/Noah/Features/Onchain/OnchainOperationJournal.swift:84`.
     Android: `apps/android/app/src/main/java/com/noahwallet/send/SendController.kt:180`,
     `apps/android/app/src/main/java/com/noahwallet/onchain/OnchainOperationJournal.kt:89`.
   - **Boarding typed errors: iOS wording, with explicit insufficient-confirmed-funds copy.**
     Minimum amount describes net Ark funds; reserved inputs ask to close the other review;
     failed broadcast warns that submission happened and must not be repeated;
     notAttempted explicitly says it was not submitted. Native diagnostics are not shown.
     iOS: `apps/ios/Noah/Features/Onchain/OnchainModels.swift:112`,
     `apps/ios/Noah/Features/Onchain/OnchainSubmission.swift:5`.
     Android: `apps/android/app/src/main/java/com/noahwallet/onchain/OnchainModels.kt:40`,
     `apps/android/app/src/main/java/com/noahwallet/onchain/BoardSubmission.kt:9`.
   - **Boarding receipts: Android behavior.** Both show Ark receives and Total leaving
     onchain wallet next to fees. Total uses actual onchain fee when available, otherwise
     the reviewed fee. The shared receipt fixture checks both paths.
     iOS: `apps/ios/Noah/Features/Onchain/OnchainViews.swift:49`.
     Android: `apps/android/app/src/main/java/com/noahwallet/onchain/OnchainScreen.kt:138`.
   - **Incomplete probe cleanup: iOS behavior.** Android checks its retained preparation
     handle after cleanup and stops before a second preparation if cleanup is incomplete.
     It no longer depends on SDK reservations rejecting the second prepare.
     iOS reference: `apps/ios/Noah/Services/Wallet/WalletSession+Onchain.swift:123`.
     Android: `apps/android/app/src/main/java/com/noahwallet/onchain/OnchainOperations.kt:39`,
     `apps/android/app/src/main/java/com/noahwallet/onchain/OnchainWalletAccess.kt:70`.

The exact-txid-before-submit path, exact-txid-only recovery, prepared-board journaling,
submission entrypoints, and lock rules are unchanged. Status notFound and transport
errors never prove failure or release a lock. Existing reorg/conflict/recovery tests
were retained and extended for transient lookup errors.

Added tests: nine iOS test functions and five Android tests. iOS adds Home bucket/hiding
and pending-round rules (WalletBalanceTests), strip visibility (HomeParityTests), three
repeat-prefill/revalidation tests (SendRepeatParityTests), and four copy/receipt/polling/
serialization tests (PaymentParityTests). Android adds HomeBalanceDetailsTest,
HomeBackgroundStripTest, two PaymentParityTest cases, and the incomplete-probe-cleanup
regression in OnchainOperationsTest. Existing chain-status, conflict, refresh, journal,
receipt and typed-error tests assert the new behavior and unchanged safety locks.

Both platforms consume `apps/fixtures/home-balance-details.json` (one case covering
all fourteen displayed buckets) and `apps/fixtures/payment-status-copy.json` (fourteen
wording entries and two receipt amount cases).

Final verification after the last code change:

- Android: the required command
  `nix develop --command sh -c 'cd apps/android && ./gradlew --max-workers=2 :app:testRegtestDebugUnitTest :app:assembleRegtestDebug'`
  passed. JUnit XML: **3,012 tests, 109 suites, zero failures/errors/skips**; regtest APK
  assembled. The preceding targeted Send/Board/Home run passed 827 tests.
- iOS: XcodeGen generated the project before the full NoahTests run. Noah-Regtest,
  ARCHS=arm64, ONLY_ACTIVE_ARCH=YES, code signing enabled, serial tests, two build jobs,
  DerivedData and result bundles under `/private/tmp`. xcresult summary: **670 tests,
  648 passed, 22 skipped, zero failures**. The skipped tests are opt-in live/integration/
  upgrade tests, whose required external setup was not enabled. Targeted tests used
  function identifiers ending in `()`; the final payment/board/status subset passed
  28 tests. The generated iOS app was verified as arm64 with an ad-hoc signature.
- No Docker, physical devices, existing emulators, or prohibited simulators were used.
  The owned simulator was `Noah Parity Agent 8346`, UDID
  `029F9961-590A-4265-81F1-78E43FCF9262`. Its identity and Shutdown state were confirmed
  before deletion. All task DerivedData and result bundles were deleted after retaining
  their summary counts. All task build/test commands exited; an idle/reused shared
  Gradle daemon whose latest build belonged to another worktree was left untouched.
- No live payment acceptance or visual-design pass was performed. The SDK limitation
  in item 1 is the only requested behavior left open.

Implementation commits:

- `5b9dbe19` feat(ios): show pending balance and expandable buckets
- `5f0554bf` feat(android): show pending balance and expandable buckets
- `38091ce1` feat(ios): show background activity in a Home strip
- `c943ed51` feat(android): show background activity in a Home strip
- `2b82b29e` fix(ios): consume repeat payments through recipient review
- `75b4b84d` fix(ios): explain funds pending in refresh rounds on Send
- `b2dd68c8` fix(android): explain funds pending in refresh rounds on Send
- `c08b4b67` fix(ios): align Send and boarding receipt behavior
- `397859cf` fix(android): align Send and boarding receipt behavior

Every commit includes `Co-Authored-By: GPT-6.1 Sol <noreply@openai.com>`.
