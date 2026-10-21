# Wallet and money screen behaviour audit — 2026-10-21

Native starting point: `2b65e49652fd3d44f997a78107fca498e47f7af3`, on the isolated `t3/wallet-behavior-parity-audit` worktree. Shipping RN reference: **`origin/master` at `a280fdd277ecd350aa759f0861013791a0d9d9b0`**, fetched for this audit. RN source was read with `git show` into a private temporary reference directory; the worktree's `client/` was not the reference. Comparing the two revisions confirms that the in-scope RN screen files themselves are unchanged; the newer preimage mapping and Auto-Board implementation are in their hooks/services.

This is a source-by-source behaviour audit, supplemented by owned-device checks of the fixes. It is not a new funded acceptance run of every payment/recovery path. The existing M1–M8, Send success (`db692822`), repeat Auto-Board and recovery evidence remains relevant. No send, board, exit, claim, journal, reconciliation, lock, settings authority or recovery execution logic was changed. No `client/` or `server/` file was modified.

Paths in the tables are relative to **RN `client/src/`**, **iOS `apps/ios/Noah/`**, and **Android `apps/android/app/src/main/java/com/noahwallet/`**, respectively. Every reference includes its source line. “Match” means equivalent user behaviour, not identical implementation. “Intentional” identifies the preserved platform/safety difference. Visual hierarchy, layout and wording are reserved for the design/copy pass.

Fixed in **`3113de0d`**: six screens, on both platforms. `06228133` follows with current-row selection counts/value/button state; shared `wallet-selection.json` fixtures cover removed/unknown IDs, empty selections and SDK order without changing review/execution IDs. `8145d3c2` makes the Android screenshot harness accept a summary that fits without scrolling and checks its accessibility descendants after rendering. All implementation commits carry the requested co-author trailer; hooks were not bypassed.

## TransactionsScreen

| RN behaviour | iOS | Android | Status |
|---|---|---|---|
| All / Lightning (Bolt11 + Lnurl) / Ark / Onchain; full local history, no pagination. `screens/TransactionsScreen.tsx:63` | Same filters and lazy list. `Features/History/TransactionsView.swift:13` | Same filters and lazy list. `ui/TransactionsScreen.kt:42` | match |
| Merged boarding/onchain accounting, latest first, pending onchain first; canceled/transfer signs; preferred bitcoin unit. `hooks/useTransactions.ts:396` | Mapping, merge, deterministic sorting and row formatting. `Features/History/HistoryTransaction.swift:182`; `Features/History/TransactionsView.swift:161` | Mapping/sorting and preferred-unit rows. `history/HistoryTransaction.kt:69`; `ui/TransactionsScreen.kt:123` | match |
| Loading, error + Retry, empty view; refresh toolbar; suspension disables automatic queries. `screens/TransactionsScreen.tsx:223`; `hooks/useTransactions.ts:464` | Loading/error/Retry/empty; toolbar and pull-to-refresh. Session errors clear wallet rows. `Features/History/HistoryModel.swift:102` | Same states; suspension clears cached rows. `history/HistoryModel.kt:121` | intentional — native also offers pull-to-refresh and explicit unavailable-wallet errors |
| Detail sheet/pane; repeat hands off to Send; export only filtered rows. `screens/TransactionsScreen.tsx:84` | Detail + repeat + filtered CSV share. `Features/History/TransactionsView.swift:93` | Detail + repeat + filtered CSV chooser. `ui/TransactionsScreen.kt:51` | match; intentional CSV escaping/formula protection and explicit sats header; native file/share lifetime differs by OS |


## TransactionDetailScreen

| RN behaviour | iOS | Android | Status |
|---|---|---|---|
| Amount, historical fiat if available, status, destination, date/confirmation, route, entered fiat, fees, repeat comment; copy destination and Pay again only for successful outgoing repeatable records. `screens/TransactionDetailScreen.tsx:218` | Same receipt fields and repeat handoff. `Features/History/TransactionDetailView.swift:21` | Same receipt fields and repeat handoff. `ui/TransactionDetail.kt:59` | match — RN description is always empty in both history mappers, so no live Note field is missing |
| Collapsed technical details: payment/transaction IDs, successful-movement preimage, invoice, chain confirmation/fee/raw tx, movement/subsystem/anchor/balance data and destinations. `screens/TransactionDetailScreen.tsx:415` | All fields, including the newer preimage, plus selectable metadata and VTXO IDs. `Features/History/TransactionDetailView.swift:59`; `Features/History/HistoryMapping.swift:90` | All fields, including the newer preimage, plus metadata/VTXO IDs. `ui/TransactionDetail.kt:94`; `history/HistoryMapping.kt:63` | match; intentional additional technical data |
| Full-value copy; network explorer for applicable onchain records; close returns to list/Home. `screens/TransactionDetailScreen.tsx:240` | Copy rows and explorer URL, close callback. `Features/History/TransactionDetailView.swift:67` | Copy rows, explorer and missing-browser error; close/back. `ui/TransactionDetail.kt:101` | match — clipboard expiration and system browser handling are platform appropriate |


## VTXOsScreen

| RN behaviour | iOS | Android | Status |
|---|---|---|---|
| All / Active / Expiring / Expired / Locked, counts on status filters, SDK order without pagination. `screens/VTXOsScreen.tsx:75`; `screens/VTXOsScreen.tsx:142` | Same predicates; filter counts restored. `Features/Onchain/VtxosView.swift:74` | Same predicates; filter counts restored. `onchain/VtxosScreen.kt:34` | fixed `3113de0d` |
| Select mode, selected count/value, Select visible, Select expiring (including expired), Clear; locked rows cannot be selected. `screens/VTXOsScreen.tsx:106` | Count/value restored before review and tied to current rows; existing selection actions preserved. `Features/Onchain/VtxosView.swift:86` | Count/value restored before review and tied to current rows; checkbox semantics retained. `onchain/VtxosScreen.kt:38` | fixed `3113de0d`, `06228133` |
| Refresh estimates selected IDs, confirms fee, syncs, then delegates refresh; detail/back navigation. `screens/VTXOsScreen.tsx:218` | Review revalidates exact selection/cost and journals delegated refresh; no explicit RN pre-sync. `Services/Wallet/WalletSession+Onchain.swift:315` | Same guarded review and delegated refresh, without the explicit pre-sync. `onchain/OnchainOperations.kt:146` | needs-decision D2 and D5 — execution unchanged |
| Loading and filter-specific empty views; queries gated when suspended/backgrounded. `screens/VTXOsScreen.tsx:89` | Loading/empty/error and readiness gates, read-only refresh/polling. `Features/Onchain/OnchainViews.swift:196` | Loading/empty/error, pull-to-refresh and readiness gates. `onchain/OnchainScreen.kt:65` | intentional — native exposes recoverable read errors rather than RN’s empty-looking failed queries |


## VTXODetailScreen

| RN behaviour | iOS | Android | Status |
|---|---|---|---|
| Amount, raw State and derived Status, current tip, expiry, remaining blocks or Expired, exit delta. `screens/VTXODetailScreen.tsx:257` | State/Status separated; current tip restored; expired countdown no longer negative. `Features/Onchain/VtxosView.swift:155` | State/Status separated; current tip and blocks remaining restored. `onchain/VtxosScreen.kt:68` | fixed `3113de0d` |
| Copy full ID, anchor and server public key; anchor explorer strips output index. `screens/VTXODetailScreen.tsx:296` | Explicit copy buttons restored using shared detail row; anchor link added. `Features/Onchain/VtxosView.swift:164` | Explicit copy buttons and anchor link restored. `onchain/VtxosScreen.kt:75` | fixed `3113de0d`; intentional no M6 regtest link to the public signet explorer |
| Expiring/expired warning; fresh unlocked VTXO can enter reviewed refresh. `screens/VTXODetailScreen.tsx:138` | Warning and existing refresh review; removed VTXO cannot submit. `Features/Onchain/VtxosView.swift:174` | Warning restored; existing refresh review and vanished-row state retained. `onchain/VtxosScreen.kt:79` | fixed `3113de0d` (Android warning); needs-decision D2 for pre-sync |
| Live wallet row supersedes navigation snapshot; unavailable queries cannot perform a refresh. `screens/VTXODetailScreen.tsx:125` | Live overview; unavailable wallet clears data; additional SDK kind/depth/weight/registration fields. `Features/Onchain/VtxosView.swift:152` | Live overview and same readiness boundary/additional SDK fields. `onchain/VtxosScreen.kt:65` | intentional — native does not keep a removed VTXO’s route snapshot as current data |


## UnilateralExitScreen

| RN behaviour | iOS | Android | Status |
|---|---|---|---|
| Tracked, Pending, Claimable, Claiming, Claimed, available eligible count/value; chain/exit tips and stale-exit warning; below-dust count/value excluded from selection. `screens/UnilateralExitScreen.tsx:1290`; `screens/UnilateralExitScreen.tsx:758` | Restored in read-only summary. `Features/Onchain/UnilateralExitView.swift:123` | Restored in read-only summary. `onchain/UnilateralExitScreen.kt:92` | fixed `3113de0d` |
| All eligible/selected exit, estimates, explicit start/progress/cancel/claim confirmations and external destination; deposit-address QR/copy. `screens/UnilateralExitScreen.tsx:1133` | Existing exact-ID reviews, deposit QR/copy/share, external-destination warning; cancel is on detail. `Features/Onchain/UnilateralExitView.swift:31` | Equivalent existing reviews/deposit controls and external-destination warning; cancel on detail. `onchain/UnilateralExitScreen.kt:62` | intentional — extra native review/journal protections and detail navigation; no execution change |
| Estimate failure permits explicit Continue without estimate / Start tracking anyway. `screens/UnilateralExitScreen.tsx:876` | Refuses to produce a review on estimator failure. `Features/Onchain/OnchainModel.swift:160` | Same refusal. `onchain/OnchainModel.kt:93` | needs-decision D3 — existing recovery-policy difference |
| Read/status states, manual Progress, focused polling and suspended/background gating. `hooks/useUnilateralExit.ts:81` | Loading/error/empty, read-only pull/poll (60s), fresh fee review before manual progression; persisted terminal exits. `Features/Onchain/OnchainViews.swift:196` | Same loading/error/empty/read-only refresh and SDK terminal exit history. `onchain/OnchainScreen.kt:65` | intentional — RN’s refresh calls syncExits; native separates status refresh from fee-paying progression |


## ExitVtxoDetailScreen

| RN behaviour | iOS | Android | Status |
|---|---|---|---|
| Amount/state, current height, exit tip, claimable height with claimable_since fallback, Remaining as Unknown/Now/singular/plural. `screens/ExitVtxoDetailScreen.tsx:292` | All block-status fields/fallback restored; unknown tip stays Unknown. `Features/Onchain/UnilateralExitView.swift:198` | Same restored block-status fields/fallback. `onchain/UnilateralExitScreen.kt:147` | fixed `3113de0d` |
| Current-state block references and grouped timeline: count, height range, current state; confirmed/claimable/scanned/claimed block identity; exit/child/claim tx explorer. `lib/exitTimeline.ts:131` | Current-state section, claimed block and full block hashes restored; existing grouped timeline/links retained. `Features/Onchain/UnilateralExitView.swift:210` | Same current-state/claimed-block/full-hash restoration and grouped timeline/links. `onchain/UnilateralExitScreen.kt:156` | fixed `3113de0d`; intentional extra copy/selectable transaction data and SDK Swept state |
| Loading/error/not-found, refresh and back. `screens/ExitVtxoDetailScreen.tsx:232` | Direct SDK terminal lookup plus readiness/error/not-found; read-only pull refresh; cancellation remains reviewed. `Features/Onchain/UnilateralExitView.swift:157` | Direct SDK terminal lookup, read/error/not-found, back and read-only refresh. `onchain/UnilateralExitScreen.kt:120` | intentional — same read-only refresh/progression distinction as overview; native preserves terminal records across reopen |


## HomeScreen

| RN behaviour | iOS | Android | Status |
|---|---|---|---|
| Total/fiat, nonzero pending, expanded buckets and privacy masking; recent three transactions/detail/repeat. `screens/HomeScreen.tsx:156` | Existing accounting, masking and recent/detail/repeat handoff. `Features/Home/HomeView.swift:248` | Existing accounting, masking and recent/detail/repeat handoff. `ui/HomeScreen.kt:28` | needs-decision D1 — exact trusted/untrusted pending split unavailable in FFI |
| Pull sync, loading/empty/error, suspended card with Settings, banners (backup, round, auto-board, email/update), shortcuts/facts. `screens/HomeScreen.tsx:173` | Existing Home model, banners, shortcuts and suspended Settings route. `Features/Home/HomeView.swift:187` | Existing Home state/banners and suspended Settings route. `ui/HomeScreen.kt:42` | match — native additional upgrade notices are intentional; no new Home source change |


## SendScreen

| RN behaviour | iOS | Android | Status |
|---|---|---|---|
| Amount-first/recipient-first, scan/paste, parsed rails, amount/fiat, MAX, available balance, pending-round explanation, recipient suggestions/notes, fee confirmation. `screens/SendScreen.tsx:33` | Existing Send flow/repeat-prefill and explicit review. `Features/Send/SendView.swift:3` | Existing Send flow/repeat-prefill and explicit review. `ui/SendScreen.kt:49` | match for user inputs/actions; intentional exact signed native preparation and confirmed-only onchain MAX |
| Loading/errors, success/failed outcomes, in-flight navigation protection; suspended/unavailable wallet cannot submit. `screens/SendScreen.tsx:14` | Existing persisted receipt/uncertain state, read-only status retry, 10s visible receipt refresh and submission gates. `Features/Send/SendView.swift:47` | Existing persisted receipt/uncertain state, status retry/poll and submission gates. `ui/SendScreen.kt:71` | intentional — native proven-outcome/repeat locks and explicit overrides remain; no money logic changed |


## components/SendSuccessBottomSheet

| RN behaviour | iOS | Android | Status |
|---|---|---|---|
| Amount/fiat, Paid via, copy full To, txid preferred over preimage, Completed, Done; truncate >32 to 14…10. `components/SendSuccessBottomSheet.tsx:31` | Already aligned by db692822; exact History movement supplies optional preimage without altering receipt. `Features/Send/SendSuccessDetails.swift:14` | Same existing alignment. `send/SendSuccessDetails.kt:10` | match — prior funded evidence retained; no new Send success change |
| Done closes success; system haptic; raw proof remains copyable. `components/SendSuccessBottomSheet.tsx:116` | System haptic/clipboard; existing receipt status, actual fees and explorer stay visible. `Features/Send/SendView.swift:480` | System haptic/clipboard and existing receipt status/fees/explorer. `ui/SendScreen.kt:323` | intentional — additional native receipt/status information |


## ReceiveScreen

| RN behaviour | iOS | Android | Status |
|---|---|---|---|
| Fresh unified BIP321 Ark + onchain QR per receive session; Lightning requires amount; fiat/note sheet, remove amount; copy full unified/rail values and share unified URI. `screens/ReceiveScreen.tsx:77` | Same request composition, amount/note/copy/share controls. `Features/Receive/ReceiveView.swift:60` | Same request composition and controls. `ui/ReceiveScreen.kt:64` | match; intentional Android retains the request across activity recreation |
| Base loading/failure + retry; invoice error keeps prior request; Ark/LN completion closes sheets and opens receive success; no pagination/filter. `hooks/useReceiveRequest.ts:373` | Generation/cancellation gates, retry, prior-request preservation and completion handling. `Features/Receive/ReceiveModel.swift:67` | Request/readiness gates, retry and completion observer. `ui/ReceiveScreen.kt:89` | match — observation is session-owned and cancellation-safe; unavailable generation cannot create a request |


## ReceiveSuccessScreen

| RN behaviour | iOS | Android | Status |
|---|---|---|---|
| Received amount, optional current fiat, Settled, refreshed wallet total; Done returns Home. `screens/ReceiveSuccessScreen.tsx:15` | All fields; completion triggers app refresh and Done selects Home. `Features/Receive/ReceiveSuccessView.swift:3` | All fields, app refresh and onDone Home callback. `ui/ReceiveScreen.kt:108` | match — native screen is hosted in Receive instead of pushing onto Home; same Done destination |


## QRHubScreen

| RN behaviour | iOS | Android | Status |
|---|---|---|---|
| My code is exactly Lightning address, optional name, full-value copy; missing identity routes to Profile. `screens/QRHubScreen.tsx:103` | Literal address QR/name/copy/Profile. `Features/QRHub/QRHubView.swift:3` | Literal address QR/name/copy/Profile. `ui/QRHubScreen.kt:29` | match — does not require a live wallet for an existing public identity |
| Scan/paste routes to Send; close/denial/tab loss returns My code. `screens/QRHubScreen.tsx:51` | Existing scanner/paste + Send import; blocks replacement of a live payment; iOS QR-only scanner. `Features/QRHub/QRHubView.swift:89` | Existing QR/EAN13 scanner/paste + guarded Send import and reset. `ui/QRHubScreen.kt:29` | intentional — iOS privacy PasteButton/share/camera APIs; Android back/permissions; EAN13 is not a payment QR |


## BoardArkScreen

| RN behaviour | iOS | Android | Status |
|---|---|---|---|
| Confirmed onchain balance/minimum; sats amount/MAX; fee display, result funding txid copy/explorer; busy/invalid/error states and back. `screens/BoardArkScreen.tsx:132` | Existing signed Amount/confirmed-only MAX review, exact fees/net/total, retained receipt and txid copy/status. `Features/Onchain/BoardArkView.swift:3` | Same signed review/receipt, amount/MAX and readiness. `onchain/BoardArkScreen.kt:15` | intentional — native requires a fresh signed review; RN submits from its estimate screen; no boarding logic changed |
| No submit with invalid amount; suspended/unavailable wallet prevents work. `screens/BoardArkScreen.tsx:53` | Existing readiness and preparation cleanup/expiry. `Features/Onchain/OnchainViews.swift:196` | Existing readiness and preparation cleanup/expiry. `onchain/OnchainScreen.kt:33` | match; intentional M6 regtest public-explorer suppression and native status/retry receipt |


## Auto-board prompt

| RN behaviour | iOS | Android | Status |
|---|---|---|---|
| Enabled + foreground + confirmed deposit; repeated deposit prompts, threshold/reserve, explicit Yes / No turn off; pending-board block; preparation retry backoff. `components/AutoBoardingService.tsx:86` | Existing funding-outpoint ledger, SDK pending-board + journal gate, visible review consent and 30s preparation backoff. `Features/Onchain/AutoBoardFunding.swift:43` | Existing equivalent funding ledger/review/backoff. `onchain/AutoBoardFunding.kt:37` | match on normal repeated-deposit behaviour; intentional SDK checkpoint knowledge versus RN pending-movement fallback |
| Failed pre-submit status/plan recheck releases newly claimed funding for later review; final live rechecks can replace changed plan. `components/AutoBoardingService.tsx:215` | Funding consumed at review presentation; expiry/background/pre-submit rejection does not release it. `Features/Onchain/AutoBoardFunding.swift:58` | Same presentation-time consumption. `onchain/AutoBoardFunding.kt:49` | needs-decision D4 — stricter native policy, unchanged |


## BtcMapScreen

| RN behaviour | iOS | Android | Status |
|---|---|---|---|
| Bundled/offline places, live sync/cache, retained viewport, category filters, city/merchant search, clustering and detail navigation. `screens/BtcMapScreen.tsx:74` | Existing bounded repository/cache, MapLibre map, search/categories/details. `Features/BtcMap/BtcMapView.swift:3` | Existing equivalent map/repository/search/details. `btcmap/BtcMapScreen.kt:41` | match — no wallet dependency, no pagination of payments |
| Location on request/previous consent, denial/error handling; directions/website/phone/social links, address copy and merchant share, comments/acceptance metadata. `screens/BtcMapScreen.tsx:144`; `screens/BtcMapScreen.tsx:245` | User-requested permission, sanitized links/system maps/ShareLink, copy, offline detail fallback. `Features/BtcMap/BtcMapDetailView.swift:93` | Explicit location permission, sanitized web/dial intents, copy/chooser, offline fallback. `btcmap/BtcMapDetailView.kt:23` | intentional — OS maps/share/permission behaviour; native accessible Places in view list adds an alternate route |


## RecurringPaymentsScreen

| RN behaviour | iOS | Android | Status |
|---|---|---|---|
| Label/destination/status, preferred-unit amount, interval/paid counts, next/end/last-run/error; new/edit/pause/resume/cancel and Run due now. `screens/RecurringPaymentsScreen.tsx:85` | List amount now follows selected unit/locale and preserves wide unsupported stored values. Existing actions unchanged. `Features/Recurring/RecurringPaymentsView.swift:91` | List amount now follows selected unit/locale. Existing actions unchanged. `recurring/RecurringPaymentsScreen.kt:56` | fixed `3113de0d` (unit formatting) |
| Cancel confirmation, supported-platform message, execute-on-open/manual run, sorted stored schedules; no transaction export/explorer. `screens/RecurringPaymentsScreen.tsx:286` | Existing protected store/executor, approval/uncertain-state UI, cancellation confirmation and readiness. `Features/Recurring/RecurringPaymentsView.swift:66` | Existing protected store/executor, approval/uncertain UI and confirmation. `recurring/RecurringPaymentsScreen.kt:77` | intentional — native safety approvals, journals, policy authority, unsupported legacy rows and non-GMS support remain as previously reviewed |


## RecurringPaymentEditorScreen

| RN behaviour | iOS | Android | Status |
|---|---|---|---|
| Sats input, preferred-unit per-payment annotation; max 64 UTF16 name units and 140 note units; notes only for Lightning address. `screens/RecurringPaymentEditorScreen.tsx:254`; `screens/RecurringPaymentEditorScreen.tsx:291` | Annotation now follows selected unit; UI input limits restored without cutting a composed character. `Features/Recurring/RecurringPaymentEditorView.swift:42` | Annotation follows selected unit; UI limits restored without splitting a surrogate pair. `recurring/RecurringPaymentEditorScreen.kt:28` | fixed `3113de0d` — stored/validated payment amount and executor untouched |
| Recipient validation, weekly/monthly/custom days, first date/time, never/end-date/count, next-three preview; save/error/back. `screens/RecurringPaymentEditorScreen.tsx:143` | Existing recipient/schedule validation, local calendar preview, save/back; RN 23:59 end-date rule retained. `Features/Recurring/RecurringPaymentEditorView.swift:124` | Equivalent existing local date/time preview/save/back. `recurring/RecurringPaymentEditorScreen.kt:32` | match — native edit and explicit policy reapproval are additional existing safeguards; no schedule math/authorization change |


## LightningAddressScreen

| RN behaviour | iOS | Android | Status |
|---|---|---|---|
| Normalized username/domain + optional npub preview; existing values prefilled; unchanged Save exits without mutation; server errors; success returns after 2s. `screens/LightningAddressScreen.tsx:56` | Existing normalization/npub validation, no-op unchanged save, safe structured errors and delayed return. `Features/Onboarding/LightningAddressView.swift:133` | Already fixed account-audit normalization/no-op and safe errors. `ui/Onboarding.kt:221` | match — settings Back remains available, busy save is protected |
| Onboarding Skip/Continue; no wallet money operation. `screens/LightningAddressScreen.tsx:77` | Existing Skip also dismisses Settings without mutation, equivalent to cancel/back. `Features/Onboarding/LightningAddressView.swift:123` | Existing onboarding/settings completion routing. `ui/Onboarding.kt:216` | intentional — extra iOS Settings dismissal action; wording belongs to copy/design pass |


## Decisions required — no money/recovery change made

**D1 — Home onchain pending sub-buckets (existing FFI gap).** RN shows Trusted Pending and Untrusted Pending separately (`screens/HomeScreen.tsx:392`, `lib/balanceUtils.ts:10`). SDK 0.5.0 gives native only confirmed/pending/total; native combines pending and derives immature. Native references: `Services/Wallet/WalletBalance.swift:96`, Android `services/wallet/WalletBalance.kt:38`. Proposed fix: extend FFI with the trusted/untrusted buckets, add shared accounting fixtures, then add the two Home rows. Do not infer a split from totals. This remains the existing open Cutover item.

**D2 — Explicit sync immediately before delegated VTXO refresh.** RN `screens/VTXOsScreen.tsx:218` and `screens/VTXODetailScreen.tsx:184` call walletSync before refresh. iOS `Services/Wallet/WalletSession+Onchain.swift:303` re-quotes/revalidates then invokes `refreshVtxosDelegated` at line 316; Android `onchain/OnchainOperations.kt:118` revalidates then calls `wallet.refresh` at line 146. Neither explicitly runs RN’s pre-sync at that boundary. Proposed fix, if required: add the sync inside the existing serialized authorized refresh path, then rebuild/reconfirm any changed quote/selection; add failure and stale-fee/selection regressions. Sync can progress/rebroadcast wallet state, so this audit did not insert it.

**D3 — Emergency estimate-unavailable fallback (previously documented safety difference).** RN `screens/UnilateralExitScreen.tsx:876`, `:1492`, `:1550`, `:1595` permits an explicitly confirmed start/claim without an estimate and start tracking with insufficient broadcast funding. Native iOS `Features/Onchain/OnchainModel.swift:160` and Android `onchain/OnchainModel.kt:93` require a successful quote before showing a review. Native’s insufficient-funding review already explains that more confirmed funds are required; the estimator-failure path has no override. Proposed decision: retain the documented refusal, or separately design and review a recovery fallback with explicit unknown-fee consent, durable scoping and zero automatic resubmission. Do not reuse a stale or fabricated zero-fee quote.

**D4 — Auto-Board failed-confirmation retry and funding consumption.** Shipping RN `components/AutoBoardingService.tsx:215` releases newly handled funding after failed pre-submit status/plan checks, applies 30s backoff and can offer a new review. Native iOS `Features/Onchain/AutoBoardFunding.swift:58` and Android `onchain/AutoBoardFunding.kt:48` persist handled funding when a visible review is installed, with no release on expiry, background invalidation or later pre-submit rejection. New deposits still prompt normally; manual Board remains available. Proposed decision: retain once-presented suppression, or release only provably unsubmitted, wallet-bound funding under the existing authority/locks and test interruption/retry cases. Unknown/accepted outcomes must remain handled. No funding-ledger or submit behaviour was changed here.

**D5 — Concurrent VTXO removal and refresh review inputs.** RN derives `selectedVtxoIdList` from current wallet rows (`screens/VTXOsScreen.tsx:106`). Native UI keeps its selected-ID set across overview updates and passes that original set to review (`Features/Onchain/VtxosView.swift:96`; Android `onchain/VtxosScreen.kt:43`). The new display correction counts/sums only current rows and disables refresh when none remain. For a partially disappeared selection, native fee review rejects the stale ID instead of silently shrinking it (`Services/Wallet/WalletSession+Onchain.swift:242`; Android `onchain/OnchainOperations.kt:87`). Proposed decision: retain this fail-closed “review again” result, or prune unavailable IDs before producing a **new** fee review, then require explicit confirmation of the resulting selection. Never alter an already reviewed/journaled input set. No execution-ID pruning was added here.

Known upstream limitations remain tracked in TODO: prepared-board parent reorg validation, the upstream movement/checkpoint crash window, pending-board exposure for RN, prepared offboards and durable pre-ID operation keys. This sweep does not claim to resolve them.

## Verification and evidence

Implementation and test-only commits: `3113de0d`, `8145d3c2`, `06228133`. The final full suites each ran once after the final source change (`06228133`). Earlier pre-final runs were used to develop and verify the fixes; their results are distinguished below.

- **Android final:** exact requested command, **3,683 JVM tests / 159 suites, 0 failures, 0 errors, 0 skips**. Regtest APK assembly and instrumentation Kotlin compilation passed. Final log: `/private/tmp/noah-parity-wallet-android-final-selection.log`; exported counts: `/private/tmp/noah-parity-wallet-evidence/android-final-selection.json`.
- **iOS final:** **875 tests: 846 passed, 29 opt-in skipped, 0 failed**; **1,702 expanded passes** including parameterized runs. XcodeGen ran first, then ad hoc signed arm64 `Noah-Regtest` / `Debug-Regtest`, `ONLY_ACTIVE_ARCH=YES`, two build jobs, serial testing, `-collect-test-diagnostics never`, private DerivedData `/private/tmp/noah-parity-wallet-dd`, and result `/private/tmp/noah-parity-wallet-ios-final.xcresult`. Final log: `/private/tmp/noah-parity-wallet-ios-final.log`; retained summary: `/private/tmp/noah-parity-wallet-evidence/ios-final.json`.
- **Focused checks:** Android wallet/translation regression run **14 passed**; final selection-only class **6 passed**, including all four shared `wallet-selection.json` cases. iOS wallet/translation/public-exit UI run **17 passed / 0 skips / 0 failures**, before adding the one final selection test. Android public exit-field/summary instrumentation: **2 passed** on the owned emulator. iOS public exit snapshots: two of those 17 passing tests. The final full suites include the final selection regression on both platforms.
- **Translations:** `bun apps/tools/translations.mjs seed`, ordinary generation and `--check` passed; all **16 translation-tool tests passed**. All new wording reuses RN keys, including the full plural stale-exit warning and per-payment annotation. **Zero missing translations** in es/ja/ko/sv on either platform. No paid `translate` command was run.
- **Hooks:** every commit ran the repository hook: client ESLint, React Compiler audit, **148 RN unit tests**, TypeScript and Cargo formatting. No bypass or push. `git diff --check` passes.

Exploratory failures were resolved before final verification: Robolectric needed the repository's SDK-35 configuration on Java 17; the new singular block message needed an explicit extractable localization call; translation coverage tests had hard-coded one used plural and now check both. The initial Android full run reported **3,682 tests, one failure** in that plural-count assertion. An earlier iOS targeted run reported **6 passed / 1 failed**, catching the untranslated singular. The pre-selection iOS full run subsequently passed **874 total / 845 passed / 29 gated skips / 0 failures** (1,701 expanded passes). The Android screenshot helper was corrected to accept a summary that fits without scrolling and to inspect Compose accessibility descendants after rendering; a UiAutomation ownership conflict was resolved by closing only this task's agent-device session before instrumentation. The final owned-emulator instrumentation run passed both tests. No unresolved test failure is being hidden by a rerun.

### Device and funding evidence

Owned devices only:

- iOS **Noah Wallet Parity 21c98b**, iPhone 17 / iOS 27.0, UDID **`1C1044B4-83F6-4CA0-98A1-B35D4EC47EA0`**.
- Android **NoahWalletParity21c98b**, Pixel 7 / Android 15, serial **`emulator-5670`**, console **5670**.

Existing opt-in FFI create stages initialized fresh test wallets without displaying or entering their phrases. Each wallet received **12,000 Ark sats** through `just bark` and **50,000 onchain sats** through `just bcli`. Exactly **one block** was mined to a Core-owned address to confirm both onchain deposits. No Docker service was started, stopped or reset. No physical device or another agent's device was used.

Funded UI checks verified VTXO filter counts, Select visible and exact selected totals (iOS 2 VTXOs / 12,000 sats; Android 4 / 12,000), raw Spendable versus derived Active, tip 219 / expiry 632 / 413 remaining blocks, and full copyable ID/anchor/server-key fields. Explorer URL parsing/network selection and regtest suppression are covered by unit tests; public explorer launch was not exercised on regtest.

Recurring UI checks selected BIP-177 and showed **₿ 1,234** in both editors and lists. Each temporary schedule was dated **2026-10-22 at 09:00 local**, then canceled through the normal confirmation before it became due; the empty lists were verified. No recurring payment executed. A separate unsaved draft on each platform received 65 ASCII name characters and retained exactly 64; assertions and screenshots were saved. Unit tests cover the 140-unit note bound and Unicode boundaries. Drafts were discarded with app/session closure. No board, VTXO refresh, exit start/progression, claim or send was submitted by this sweep.

The exit claimed-block/claimable-height/stale-tip/below-dust screenshots use explicitly **public, read-only fixtures rendered by the production summary/detail components**. They prove presentation, not a new funded claim lifecycle. Those tests expose no mutation path and show no phrase. The concurrent-removal selection correction is covered by shared deterministic fixtures; it was not induced by spending a live selected VTXO.

### Screenshots

All screenshots are retained under **`/private/tmp/noah-parity-wallet-shots/`**. For each of `ios` and `android`:

- `<platform>-vtxo-filter-counts.png` — All/Active/Expiring/Expired/Locked counts.
- `<platform>-vtxo-selection-total.png` — selected count and preferred-unit total.
- `<platform>-vtxo-detail-status.png` — raw state, derived status, current/expiry/remaining heights.
- `<platform>-vtxo-detail-copy.png` — anchor/server key and explicit copy controls.
- `<platform>-exit-summary.png`, `<platform>-exit-summary-bottom.png` — public-fixture counts, available value, stale tip and below-dust data; Android's whole summary fits, so its bottom capture is equivalent.
- `<platform>-exit-blocks.png` — public-fixture claimable fallback, Now and claimed block identity.
- `<platform>-recurring-editor-unit.png`, `<platform>-recurring-list-unit.png` — selected BIP-177 display for 1,234 sats.
- `<platform>-recurring-input-limit.png` — 64-character retained name after 65-character input.

Additional Android context: `android-vtxos-list.png`. The screenshot manifest, sizes and SHA-256 values are in `/private/tmp/noah-parity-wallet-evidence/screenshots.json`. No phrase was displayed, logged or captured.

### Cleanup and limits

Both owned devices were identified by exact name and UDID/AVD before shutdown and deletion. The iOS simulator was confirmed Shutdown before `simctl delete`; the Android serial was confirmed to report `NoahWalletParity21c98b` before shutdown, and that AVD's directory and `.ini` were verified absent after deletion. Sanitized identity/cleanup records are retained in `/private/tmp/noah-parity-wallet-evidence/{ios,android}-device-{before-shutdown,cleanup}.json`.

Removed the task's private iOS DerivedData, all five task xcresult bundles, the temporary shipping-RN reference, and this worktree's Android `app/build`, `build`, `.gradle` and `.kotlin` directories. Final result summaries were exported before removal; screenshots, logs and sanitized evidence remain. Build cleanup manifests: `/private/tmp/noah-parity-wallet-evidence/{ios,android}-build-cleanup.json`. Shared Docker services, global caches and other devices/builds were left running.

Remaining acceptance limits: no new live money/recovery lifecycle, real-camera scan, external browser/share-target acceptance, fault-induced network outage, physical hardware, or dark/landscape design pass was run. Existing lifecycle and presentation tests remain in the full suites. The five decision items above remain open, and the overall Cutover behaviour-audit parent is therefore left unchecked; the completed wallet/money sweep is linked beneath it.
