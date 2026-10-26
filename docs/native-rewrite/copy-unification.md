# Unifying iOS and Android copy

Both native apps now show the same English for the same message, and `bun apps/tools/translations.mjs --check` (CI: "Check native translation drift and single-platform copy" in `.github/workflows/native-generated.yml`) fails on copy only one platform uses unless [`apps/i18n/platform-only.json`](../../apps/i18n/platform-only.json) lists it for that platform with a reason. A listed entry that both platforms, or neither, now use fails too, so the list stays honest.

Paths below are relative to `apps/ios/Noah/` (iOS) and `apps/android/app/src/main/java/com/noahwallet/` (Android).

## Counts

From `apps/fixtures/translations-coverage.json` (`coverage.{ios,android}.used`):

| | Before (`ac43b695`) | After |
| --- | ---: | ---: |
| Shared | 1,038 | 1,174 |
| iOS only | 280 | 119 |
| Android only | 371 | 183 |
| Allowlisted (all of the single-platform strings) | — | 302 (119 iOS, 183 Android) |
| … of which parity gaps | — | 223 (98 iOS, 125 Android) |
| Diagnostics and non-copy literals taken out of the catalogs | — | 86 |
| Catalog entries | 1,689 | 1,476 |

"Shared" grew because a unified pair leaves one string used by both, and a few strings iOS showed without extracting (the restore word counts, the verification code label) are now extracted.

## How the wording was chosen

In order: the approved specs and prototypes in `design/`, the copy rules in [`onboarding.md`](design/onboarding.md#principles), then the clearer and shorter. Meaning is kept exactly; errors keep their specificity, and money and safety warnings are never weakened. Almost every choice reuses a wording one platform already had, so its es, ja, ko and sv translations already existed. The only new keys are two curly-apostrophe variants of exit strings, whose translations are copied unchanged from the straight-apostrophe keys. Where a pair differed only by apostrophe, the prototype's curly ’ won.

## Changed strings

| Area | Old iOS | Old Android | Chosen | iOS files | Android files | Note |
| --- | --- | --- | --- | --- | --- | --- |
| Send | Instant · {fee} fee | Instant · {fee} estimated fee | Instant · {fee} fee | Features/Send/SendView.swift | ui/send/SendContent.kt | spec wording |
| Send | Seconds · {fee} fee | Seconds · {fee} estimated fee | Seconds · {fee} fee | Features/Send/SendView.swift | ui/send/SendContent.kt | spec wording |
| Send | About 20 min · {fee} fee | About 20 min · {fee} estimated fee | About 20 min · {fee} fee | Features/Send/SendView.swift | ui/send/SendContent.kt | spec wording |
| Send | to {recipient} | to {name} | to {recipient} | Features/Send/SendOutcomeView.swift | ui/send/SendContent.kt | spec placeholder |
| Send | up to {amount} | up to {fee} | up to {amount} | Features/Send/SendOutcomeView.swift | ui/send/SendContent.kt |  |
| Send | {amount} is back in your wallet. Lightning couldn’t reach {recipient}. | {amount} is back in your wallet. Lightning couldn’t reach {name}. | {amount} is back in your wallet. Lightning couldn’t reach {recipient}. | Features/Send/SendOutcomeView.swift | ui/send/SendContent.kt | spec placeholder |
| Send | Payment did not complete | Payment not sent | Payment not sent | Features/Send/SendOutcomeView.swift | ui/send/SendContent.kt | failed result title |
| Send | Payment sent (amount pending) / Submitted (broadcast retry) | Payment submitted | Payment submitted | Features/Send/SendOutcomeView.swift | ui/send/SendContent.kt | result title |
| Send | I've checked my history, start a new payment | Start a new payment after checking history | Start a new payment after checking history | Features/Send/SendOutcomeView.swift | ui/send/SendContent.kt | override button |
| Send | Keep waiting | Keep checking | Keep checking | Features/Send/SendOutcomeView.swift | ui/send/SendContent.kt | override dismiss |
| Send | Check payment status | Check status | Check status | Features/Send/SendOutcomeView.swift | ui/send/SendContent.kt |  |
| Send | Start a new payment | Done | Done | Features/Send/SendOutcomeView.swift | ui/send/SendContent.kt | not-sent result button |
| Send | Close | Leave | Close | Features/Send/SendOutcomeView.swift | ui/send/SendContent.kt | broadcast-retry result button |
| Send | Fee | Actual fee | Fee | Features/Send/SendOutcomeView.swift | ui/send/SendContent.kt | recorded fee row (redesigned Send only) |
| Send | Total (reviewed coins) | Total | Total (reviewed coins) | Features/Send/SendReviewView.swift | ui/send/SendContent.kt | review, selected VTXOs |
| Send | The fee and total are estimates based on two inputs and two outputs. The final fee and total may be higher if more coins are needed. | The fee is an estimate. The final fee may change before submission. | The fee and total are estimates based on two inputs and two outputs. The final fee and total may be higher if more coins are needed. | Features/Send/SendPayment.swift | ui/send/SendContent.kt, ui/SendScreen.kt | keeps "may be higher" |
| Send | A payment of {amount} sats for this invoice is already in progress. Do not pay again. | A payment of {nativeValue1} sats for this invoice is already in progress. Do not pay again. | A payment of {amount} sats for this invoice is already in progress. Do not pay again. | Features/Send/SendOutcomeView.swift | ui/send/SendContent.kt | Android result now localizes it |
| Send | Recurring payment stopped before submission. No payment was sent. | This recurring payment was stopped before submission. | Recurring payment stopped before submission. No payment was sent. | Features/Send/SendPayment.swift | send/SendPayment.kt | keeps "No payment was sent" |
| Send | Unable to reserve the wallet. No payment was submitted. | Unable to reserve the wallet. Check payment status before sending again. | Unable to reserve the wallet. Check payment status before sending again. | Features/Send/SendPayment.swift | send/SendController.kt | Android's is true in both its states |
| Send | Unable to reserve the wallet to check payment status. | Couldn't check the latest status. Showing the last known status. | Couldn't check the latest status. Showing the last known status. | Features/Send/SendPayment.swift | send/SendController.kt | check-status reservation failure |
| Send | Unable to resolve this Lightning recipient. Please check the address. | Unable to resolve this recipient. Please check the address. | Unable to resolve this recipient. Please check the address. | Features/Send/SendModel.swift | send/SendModel.kt | Android's also covers non-Lightning cases |
| Send | Invalid BOLT12 offer: Invalid checksum | Invalid BOLT12 offer | Invalid BOLT12 offer | Features/Send/SendDestination.swift | send/SendDestination.kt | any unparsable offer, not only a checksum |
| Send | Scan payment QR | Scan QR code | Scan QR code | Features/Send/SendScanner.swift | ui/SendScanner.kt | scanner title |
| Send | No camera is available. You can paste the payment request instead. | No camera device found. | No camera is available. You can paste the payment request instead. | Features/Send/SendScanner.swift | ui/SendScanner.kt |  |
| Send | Delete digit | Delete | Delete digit | Features/Send/SendChrome.swift | ui/send/SendParts.kt | keypad accessibility label |
| Receive | From any Lightning wallet | Scan with any Lightning wallet | From any Lightning wallet | Features/Receive/ReceiveFormat.swift | ui/receive/ReceiveContent.kt | prototype; Ways to pay you row |
| Receive | For {amount} only | Scan with any Lightning wallet | For {amount} only | Features/Receive/ReceiveFormat.swift | ui/receive/ReceiveContent.kt | prototype; invoice row with an amount |
| Receive | Invoices need an amount | Add an amount to receive over Lightning | Invoices need an amount | Features/Receive/ReceiveFormat.swift | ui/receive/ReceiveContent.kt | prototype; invoice row without an amount |
| Receive | From any bitcoin wallet, about 20 min | Scan with any bitcoin wallet | From any bitcoin wallet, about 20 min | Features/Receive/ReceiveFormat.swift | ui/receive/ReceiveContent.kt | prototype; Bitcoin address row |
| Receive | Add an amount first | (blank) | Add an amount first | Features/Receive/ReceiveCopySheet.swift | ui/receive/ReceiveContent.kt | prototype; invoice row's value before an amount |
| Receive | Copy {label} | Copy {name} | Copy {label} | Features/Receive/ReceiveCopySheet.swift | ui/receive/ReceiveContent.kt | copy button accessibility label |
| Receive | Description | Add a note | Add a note | Features/Receive/ReceiveAmountSheet.swift | ui/receive/ReceiveContent.kt | note field placeholder (spec) |
| Receive | The Lightning payment could not be received. Generate a new payment request and try again. | Lightning receive {nativeValue1}. Generate a new payment request and try again. | The Lightning payment could not be received. Generate a new payment request and try again. | Features/Receive/ReceiveModel.swift | ui/ReceiveScreen.kt | Android interpolated an untranslated "canceled"/"failed"; it now shows iOS's "Lightning Receive Canceled" / "Lightning Receive Failed" title above it |
| Home | Couldn't load your balance. | Couldn't load your balance. Pull down to try again. | Couldn't load your balance. Pull down to try again. | App/AppModel.swift | ui/AppModel.kt | both Homes pull to refresh |
| Home | Couldn't open your wallet. | Couldn't open your wallet. Please try again. | Couldn't open your wallet. Please try again. | App/AppModel.swift | ui/AppModel.kt |  |
| Home | Your wallet is currently suspended. All wallet operations are disabled. / Resume your wallet in Settings → Advanced. | All wallet operations are disabled. Resume your wallet in Settings. | All wallet operations are disabled. Resume your wallet in Settings. | Features/Home/HomeChrome.swift | ui/home/HomeContent.kt | iOS's two lines become one (copy rule: one short line); "Open Settings" sits under it |
| Home | {amount} available in Ark | Boarding completed | {amount} available in Ark | Features/Home/HomeView.swift | ui/HomeScreen.kt | automatic-boarding toast; Android formats the amount as Home does, so a hidden balance shows •••• |
| Home | Loading transactions | Loading activity | Loading activity | Features/Home/HomeActivityViews.swift, Features/History/TransactionsView.swift | ui/activity/ActivityContent.kt |  |
| Home | Refunded · back in your wallet | Refunded + "· back in your wallet" | Refunded · back in your wallet | Features/Home/HomeActivityViews.swift | ui/home/HomeParts.kt | Android joined two strings |
| Home | Status / Final / Successful / Error / Yes / No (label and value) | Status: {nativeValue1} / Final: {nativeValue1} / Successful: {nativeValue1} / Error: {nativeValue1} | Status, Final, Successful, Error, Yes, No | Features/Home/HomeStatusViews.swift | ui/home/HomeBanners.kt | Pending Rounds sheet; Android now translates the label and value and joins them with ": " |
| Home | Unsigned tx {value1} | Unsigned tx {nativeValue1} | Unsigned tx {value1} | Features/Home/HomeStatusViews.swift | ui/home/HomeBanners.kt | Pending Rounds sheet |
| Activity | {amount} at the time | {value} at the time | {amount} at the time | Features/History/ActivityDetailRules.swift | ui/activity/ActivityDetail.kt | placeholder only |
| Activity | Intended balance change | Intended change | Intended change | Features/History/TransactionDetailView.swift | ui/activity/ActivityDetail.kt | prototype |
| Activity | Effective balance change | Effective change | Effective change | Features/History/TransactionDetailView.swift | ui/activity/ActivityDetail.kt | prototype |
| Activity | Exiting | Exiting to the chain | Exiting | Features/History/ActivityDetailRules.swift | ui/activity/ActivityRules.kt | detail status line; the spec wins over the prototype |
| Settings | Couldn't update this setting. Please try again. | Couldn't update mailbox notifications. | Couldn't update mailbox notifications. | Features/Settings/AdvancedPages.swift | ui/settings/NotificationsPages.kt | iOS's generic error replaced by the specific one |
| Settings | Couldn't update this setting. Please try again. | Couldn't save authentication preference. | Couldn't save authentication preference. | Features/Settings/SettingsView.swift | ui/settings/SettingsRoot.kt |  |
| Settings | Couldn't update this setting. Please try again. | Couldn't change wallet suspension. Please try again. | Couldn't change wallet suspension. Please try again. | Features/Settings/AdvancedPages.swift | ui/settings/AdvancedPages.kt | suspend and resume |
| Settings | Couldn't update this setting. Please try again. | Couldn't register again. Please retry. | Couldn't register again. Please retry. | Features/Settings/AdvancedPages.swift | ui/settings/AdvancedPages.kt | reset registration |
| Settings | Registered again | Registered with the server again | Registered again | Features/Settings/AdvancedPages.swift | ui/settings/AdvancedPages.kt | toast; shorter |
| Settings | {count} blocks | {n} blocks | {n} blocks | Features/Settings/ArkInfoView.swift | ui/settings/AdvancedPages.kt | Ark server page |
| Settings | Ark server info is unavailable. Pull to refresh. / Couldn't load Ark info. Pull to refresh. | Ark server info is unavailable. Try refreshing. | Ark server info is unavailable. Try refreshing. | Features/Settings/ArkInfoView.swift | ui/settings/AdvancedPages.kt | true on both (pull, or Try again) |
| Settings | Encrypted with your recovery phrase before it leaves this phone. + To restore, enter your recovery phrase when you set up Noah on a phone. | Encrypted with your recovery phrase before it leaves this phone. To restore, enter your recovery phrase when you set up Noah on a phone. | (Android's single string) | Features/Settings/BackupSettingsView.swift | ui/settings/BackupScreens.kt | same English; iOS joined two strings |
| Settings | Waiting to back up | Backup pending | Backup pending | Features/Settings/BackupSettingsView.swift | ui/settings/BackupScreens.kt |  |
| Settings | Backs up when your wallet changes | Back up now or wait for the next change | Backs up when your wallet changes | Features/Settings/BackupSettingsView.swift | ui/settings/BackupScreens.kt | never backed up, automatic on; shorter |
| Settings | Couldn't complete the backup operation. Please try again. | Couldn't back up your wallet. Please try again. / Couldn't update backup settings. / Couldn't delete backup. / Couldn't prepare the file for sharing. Please try again. | (Android's, per action) | Features/Settings/BackupSettingsView.swift | ui/settings/BackupScreens.kt | iOS's generic error replaced by the specific ones |
| Settings | Exported | Export Complete | Exported | Features/Settings/BackupSettingsView.swift | ui/settings/BackupScreens.kt | toast; sentence case like the other toasts |
| Settings | Not changed. + {error} | {error} | {error} | Features/Settings/EsploraSettingsScreen.swift | ui/settings/AdvancedPages.kt | iOS no longer prefixes (it read "Not changed. Endpoint not changed. …") |
| Settings | The wallet is using the previous endpoint. / Restart Noah to reopen the previous endpoint. | Endpoint not changed. The wallet is using the previous endpoint. / Endpoint not changed. Restart Noah to reopen the previous endpoint. | (Android's) | Features/Settings/EsploraSettingsScreen.swift | ui/settings/AdvancedPages.kt |  |
| Settings | Regtest wallets use Bitcoin Core instead of Esplora. | Regtest wallets use Bitcoin Core. | Regtest wallets use Bitcoin Core. | Features/Settings/EsploraSettingsScreen.swift | ui/settings/AdvancedPages.kt | prototype; the longer one stays as the switch error |
| Settings | Remove Screenshot | Remove screenshot | Remove screenshot | Features/Settings/FeedbackView.swift | ui/settings/OtherScreens.kt | sentence case |
| Settings | Logs are redacted before you share them. | Shared logs never include your phrase or keys. | Shared logs never include your phrase or keys. | Features/Settings/FileShare.swift | ui/settings/AdvancedPages.kt | prototype |
| Settings | Couldn't share logs. Please try again. | Couldn't prepare the file for sharing. Please try again. | Couldn't prepare the file for sharing. Please try again. | Features/Settings/FileShare.swift | ui/settings/BackupScreens.kt | Android uses one share helper for logs and export |
| Settings | Copy | Copy result | Copy | Features/Settings/DebugView.swift | ui/settings/AdvancedPages.kt | Debug result |
| Settings | Dangerously remove this VTXO? / Offboard all funds? | {action}? | {action}? | Features/Settings/DebugView.swift | ui/settings/AdvancedPages.kt | prototype |
| Settings | This action changes your wallet. Type delete to confirm. | This changes your wallet and can lose funds. | This changes your wallet and can lose funds. | Features/Settings/DebugView.swift | ui/settings/AdvancedPages.kt | prototype; the field still says "Type delete to confirm" |
| Settings | Action completed successfully | Server refreshed / Maintenance completed / Delegated maintenance completed / VTXO removed / VTXO unlocked | (Android's, per action) | Features/Settings/DebugView.swift | services/wallet/WalletSession.kt | Debug results, shown verbatim |
| Settings | Couldn't save your name. Please try again. | Couldn't save your name. | Couldn't save your name. | Features/Settings/ProfileView.swift | ui/settings/ProfilePages.kt | shorter |
| Settings | Enter the code we sent to {email}. | Enter the code we sent to {email} | Enter the code we sent to {email} | Features/Settings/ProfileView.swift | ui/settings/ProfilePages.kt | the onboarding string both share |
| Settings | Couldn't save your preference. Please try again. | Couldn't save your preference. | Couldn't save your preference. | Features/Settings/RecoveryPhrasePage.swift | ui/settings/RecoveryPhrasePage.kt | shorter |
| Settings | {n} words that restore your wallet. / The words that restore your wallet. | 12 words that restore your wallet. | (iOS's) | Features/Settings/RecoveryPhrasePage.swift | ui/settings/RecoveryPhrasePage.kt | Android said 12 for a 24-word phrase too |
| Settings | Authenticate to view your seed phrase | View your seed phrase | View your seed phrase | Features/Settings/RecoveryPhrasePage.swift | ui/settings/RecoveryPhrasePage.kt | biometric prompt |
| Settings | Authenticate to unlock Noah | Unlock Noah | Unlock Noah | Features/Settings/WalletLock.swift | ui/settings/WalletLock.kt | biometric prompt; prototype |
| Settings | Authenticate to enable biometrics / Authenticate to disable biometrics | Enable wallet authentication / Disable wallet authentication | (Android's) | Features/Settings/WalletLock.swift | ui/settings/WalletLock.kt | biometric prompt |
| Settings | The wallet couldn't be deleted. Restart Noah and try again. | Couldn't delete wallet. Restart Noah if a restore is pending, then try again. / Couldn't delete wallet. Restart Noah and try again. | Couldn't delete wallet. Restart Noah and try again. | Features/Settings/DeleteWalletSheet.swift | ui/AppModel.kt, ui/LaunchGateView.kt |  |
| On-chain | Submitted but unconfirmed. Noah will keep trying the broadcast. Do not board these funds again. | Boarding submitted, but delivery is unconfirmed. Noah will keep retrying the broadcast. Do not board again. | Boarding submitted, but delivery is unconfirmed. Noah will keep retrying the broadcast. Do not board again. | Features/Onchain/OnchainViews.swift | onchain/OnchainOperationJournal.kt | status line for a board whose broadcast is retrying |
| On-chain | The recorded transaction is in this wallet's transaction history. | The recorded transaction was found. Review its network status. | The recorded transaction was found. Review its network status. | Features/Onchain/OnchainOperationJournal.swift | onchain/OnchainOperationJournal.kt | recovered receipt |
| On-chain | Refresh network status | Check status | Refresh network status | Features/Onchain/OnchainViews.swift | onchain/OnchainScreen.kt | a receipt's txid check (retry and unconfirmed keep "Check status") |
| On-chain | Repeat protection overridden (label + date) | Repeat protection overridden: {date} (interpolated) | Repeat protection overridden | Features/Onchain/OnchainViews.swift | onchain/OnchainScreen.kt | Android now translates the label |
| On-chain | VTXOs selected (label + count) | VTXOs selected: {nativeValue1} | VTXOs selected | Features/Onchain/OnchainViews.swift | onchain/OnchainScreen.kt | Android now translates the label |
| On-chain | Funding inputs | Inputs | Funding inputs | Features/Onchain/OnchainViews.swift, Features/Onchain/BoardArkView.swift | onchain/OnchainScreen.kt | signed board's inputs row |
| On-chain | Move onchain bitcoin into Ark using this exact signed funding transaction. … This review expires after two minutes. (+ MAX sentence) | Move onchain bitcoin into Ark. The amounts and fees shown are exact for this prepared transaction. … (+ MAX or fee sentence) | (iOS's two strings) | Features/Onchain/BoardArkView.swift, Features/Onchain/OnchainViews.swift | onchain/OnchainScreen.kt, onchain/BoardArkScreen.kt | Android's version was one concatenation and never localized; its quote also expires after 120 s |
| On-chain | Couldn't save auto-board setting. | Couldn't save auto-board preference. | Couldn't save auto-board preference. | Features/Onchain/BoardArkView.swift | onchain/BoardArkScreen.kt | iOS used both |
| On-chain | {count} blocks | {n} blocks | {n} blocks | Features/Onchain/OnchainDisplay.swift, Features/Onchain/VtxosView.swift | onchain/*.kt |  |
| On-chain | Block {height} + " · " + {id} | Block {height} · {id} | Block {height} · {id} | Features/Onchain/VtxosView.swift | onchain/VtxosScreen.kt | VTXO row; one template |
| On-chain | Reload list | Refresh list | Refresh list | Features/Onchain/VtxosView.swift | onchain/VtxosScreen.kt | ⋯ menu |
| On-chain | This VTXO is no longer in the wallet's current VTXOs. Refresh to check its status. | VTXO is no longer in this view. Check its operation receipt or refresh. | This VTXO is no longer in the wallet's current VTXOs. Refresh to check its status. | Features/Onchain/VtxosView.swift | onchain/VtxosScreen.kt | clearer |
| On-chain | {exitWeight} wu (verbatim) | {n} wu | {n} wu | Features/Onchain/VtxosView.swift | onchain/VtxosScreen.kt |  |
| On-chain | {n} h / {n} d | {n} hours / {n} days | {n} hours / {n} days (plural) | Features/Onchain/VtxosView.swift | onchain/VtxosScreen.kt | Next expiry in {time}; prototype; iOS now rounds as Android does |
| On-chain | This refreshes the selected VTXOs in a delegated Ark round. | Joins the next Ark round, so it stays spendable. / …so they stay spendable. | (Android's) | Features/Onchain/VtxosView.swift | onchain/VtxosScreen.kt | renew sheet line; the legacy review sheet keeps the shared original |
| Recurring | Every {n} weeks / Every {n} months (plural) | tr("Every {n} weeks") on a plural resource | Every {n} weeks / Every {n} months (plural) | Features/Recurring/RecurringPaymentEditorView.swift | recurring/RecurringPaymentsScreen.kt, recurring/RecurringPaymentEditorScreen.kt | Android showed a literal "{n}"; now trPlural |
| Recurring | Every 2 weeks (English description, never translated) | Every {n} days / weeks / months | Every {n} days, Every {n} weeks, Every {n} months | Features/Recurring/RecurringSchedule.swift, RecurringPaymentsView.swift, RecurringPaymentEditorView.swift, Features/History/TransactionDetailView.swift | recurring/RecurringPaymentsScreen.kt | iOS's interval label now translates |
| Recurring | Every {n} weeks (count 1: "Every 1 week") | Every week / Every month | Every week / Every month | Features/Recurring/RecurringPaymentEditorView.swift | recurring/RecurringPaymentEditorScreen.kt | stepper at 1; prototype |
| Recurring | {repeats} + " · " + next {date} | {interval} · next {date} | {interval} · next {date} | Features/Recurring/RecurringPaymentsView.swift | recurring/RecurringPaymentsScreen.kt | one template |
| Recurring | name@domain.com, ark1… or lno1… | Name, address or offer | Name, address or offer | Features/Recurring/RecurringPaymentEditorView.swift | recurring/RecurringPaymentEditorScreen.kt | prototype |
| Recurring | e.g. Rent, Donation, VPN | e.g. Rent | e.g. Rent | Features/Recurring/RecurringPaymentEditorView.swift | recurring/RecurringPaymentEditorScreen.kt | prototype |
| Recurring | You'll get a reminder the day before and a notification after each payment. If a payment is missed while your phone is off, Noah skips it instead of paying twice. | A reminder comes the day before. A missed payment is skipped, never paid twice. | A reminder comes the day before. A missed payment is skipped, never paid twice. | Features/Recurring/RecurringPaymentEditorView.swift | recurring/RecurringPaymentEditorScreen.kt | prototype |
| Recurring | Save payment | Save | Save | Features/Recurring/RecurringPaymentEditorView.swift | recurring/RecurringPaymentEditorScreen.kt | prototype |
| Recurring | Remove payment | Remove | Remove payment | Features/Recurring/RecurringPaymentsView.swift | recurring/RecurringPaymentsScreen.kt | matches "Cancel payment" beside it |
| Recurring | Check Activity for this payment's last attempt and ask the recipient whether it arrived. | Check your history and confirm with the recipient. This payment may already have been sent. | (iOS's) | Features/Recurring/RecurringPaymentsView.swift | recurring/RecurringPaymentsScreen.kt | resume-after-checking sheet; closest to the prototype's "Check Activity and ask the recipient" |
| Recurring | I checked | I checked history | I checked | Features/Recurring/RecurringPaymentsView.swift | recurring/RecurringPaymentsScreen.kt | same sheet |
| Recurring | This payment may have been sent. Check its status before making another payment. (SendFailure.unresolved) | Check the current payment before making another payment. | This payment may have been sent. Check its status before making another payment. | Features/Recurring/RecurringExecutor.swift | recurring/RecurringExecutor.kt | Review and pay while another payment is open (shown) |
| Recurring | Recurring payment storage failed. Restart Noah; sending is stopped. | Unable to save recurring payments / Unable to save recurring payment evidence | Recurring payment storage failed. Restart Noah; sending is stopped. | Features/Recurring/RecurringStore.swift | recurring/RecurringStore.kt | Android showed these through e.message |
| Recurring | Recurring payment data couldn't be read. Your saved schedules have been preserved. Sending is stopped. | Unsupported recurring payment version. Sending is stopped. | (iOS's) | Features/Recurring/RecurringStore.swift | recurring/RecurringStore.kt | same case on iOS (unsupported envelope version) |
| BTC Map | Noah could not determine your current location. Try again. | Location is unavailable. You can still search for a city and browse the full map. | Location is unavailable. You can still search for a city and browse the full map. | Features/BtcMap/BtcMapModel.swift | btcmap/BtcMapScreen.kt | both have city search |
| BTC Map | Find places near me | My location | My location | Features/BtcMap/BtcMapView.swift | btcmap/BtcMapScreen.kt |  |
| BTC Map | Finding location (spinner label) | Locating… | Locating… | Features/BtcMap/BtcMapView.swift | btcmap/BtcMapScreen.kt |  |
| BTC Map | Updating places (spinner label) | Syncing… | Syncing… | Features/BtcMap/BtcMapView.swift | btcmap/BtcMapScreen.kt |  |
| BTC Map | Close place details | Close | Close | Features/BtcMap/BtcMapDetailView.swift | btcmap/BtcMapDetailView.kt |  |
| Onboarding | Creation Failed. Please try again. | Creation failed. Please try again. | Creation failed. Please try again. | Features/Onboarding/OnboardingFlow.swift | ui/onboarding/WelcomeStep.kt | sentence case |
| Onboarding | Verification code | 6-digit code | Verification code | Features/Onboarding/EmailScreen.swift, Features/Settings/ProfileView.swift | ui/onboarding/EmailStep.kt | code field's accessibility label |
| Onboarding | 12 words / 24 words (shown, not extracted) | 12 words / 24 words | 12 words / 24 words | Features/Onboarding/RestoreWalletScreen.swift | ui/RestoreWalletScreen.kt | iOS's picker now localizes them |
| Exit | (curly ’) | (straight ') | curly ’, as in exit-v1.html | Features/Onchain/* | onchain/ExitClaim.kt, ExitProgress.kt, ExitScreens.kt, ExitStart.kt | 14 strings that differed only by apostrophe: Check the address…; This phone’s on-chain wallet; Noah won’t move these back to Ark.; Every exit that’s ready…; Each transaction’s fee…; Another exit from your round…; Your VTXO leaves Ark…; Your bitcoin sits at the end of a path… (plural); Fees more than it’s worth; Emergency use only…; Can’t be undone; Add about {amount} on-chain first…; Noah then sends and fee-bumps…; Your VTXO’s path to the chain |
| Exit | Noah isn’t checking the chain for this exit right now. Use Check the chain to move it on. | Noah hasn't checked the chain for this exit in a while. Checking can also send or fee-bump its transactions. | Noah hasn’t checked the chain for this exit in a while. Checking can also send or fee-bump its transactions. | Features/Onchain/ExitProgressView.swift | onchain/ExitProgress.kt | prototype; curly key reuses the straight key's translations |
| Exit | The lock is open. Claim it to another wallet or to this phone’s on-chain wallet. | The lock is open. Claim it to another wallet or this phone's on-chain wallet. | (iOS's) | Features/Onchain/ExitProgressView.swift | onchain/ExitProgress.kt |  |
| Exit | It’s ordinary bitcoin now. | It's ordinary bitcoin now, on-chain. | It’s ordinary bitcoin now. | Features/Onchain/ExitProgressView.swift | onchain/ExitProgress.kt | the claim can go to another wallet |
| Exit | This VTXO was already spent, so it can’t exit. / An output this exit needs was spent on-chain, so it can’t finish. | Exit tracking found that this VTXO was already spent. / An ancestor exit input was swept. | (iOS's) | Features/Onchain/ExitProgressView.swift | onchain/ExitProgress.kt | explanation for spent and swept exits; plainer |
| Exit | Anchor + {fee} fee · {n} of {total} | Anchor + {amount} fee · {n} of {total} | Anchor + {fee} fee · {n} of {total} | Features/Onchain/ExitProgressView.swift | onchain/ExitProgress.kt | placeholder; prototype |
| Exit | Claimable in {n} blocks · {time} (plural; {time} = "about 16 h" / "under an hour") | Claimable in {n} blocks · about {time} ({time} = "16 hours") | (iOS's) | Features/Onchain/ExitProgressView.swift | onchain/ExitProgress.kt | prototype "about 10 h"; Android said "about under an hour" |
| Exit | Waiting · {n} blocks · {time} (plural) | Waiting · {n} blocks · about {time} | (iOS's) | Features/Onchain/ExitListView.swift | onchain/ExitList.kt | as above |
| Exit | Took + {n} blocks · {time} | Took about + {time} | Took + {n} blocks · {time} | Features/Onchain/ExitProgressView.swift | onchain/ExitProgress.kt | spec: "Took" is measured in blocks |
| Exit | {amount} · {left} left | {amount} + " · " + {amount} left | {amount} · {left} left | Features/Onchain/ExitProgressView.swift | onchain/ExitProgress.kt | Fees paid row; one template |
| Exit | Your bitcoin is on-chain, locked for {n} blocks. … (L10n.plural) | tr() on the plural resource (showed a literal "{n}") | (plural on both) | Features/Onchain/ExitProgressView.swift | onchain/ExitProgress.kt | Android now trPlural |
| Exit | Couldn’t check the fees of {n} VTXOs, so All waits. Choose them one by one, or try again. | Some VTXOs' fees couldn't be estimated, so All waits for them. You can still choose VTXOs one by one. | (iOS's, plural) | Features/Onchain/UnilateralExitView.swift | onchain/ExitScreens.kt | keeps the count |
| Exit | Left out: {amount}, too small to exit. / Left out: {amount}, in use right now. / Left out: {amount} that costs more in fees than it’s worth. Choose it to exit anyway. | Left out, too small to exit: {amount}. / Left out, in use right now: {amount}. / Left out, fees more than they're worth: {amount}. Choose them to exit them anyway. | (iOS's) | Features/Onchain/UnilateralExitView.swift | onchain/ExitScreens.kt | closest to the prototype's "Left out: ₿290, too small to exit, …" |
| Exit | {amount} · fees {fee} (fee pre-prefixed "~"; "—" without an estimate) | {amount} · fees ~{fee} (amount alone without one) | {amount} · fees ~{fee} | Features/Onchain/UnilateralExitView.swift | onchain/ExitScreens.kt | prototype |
| Exit | Expires in {time} ("6 h") | Expires in {n} hours / days (plural) | Expires in {n} hours / days | Features/Onchain/ExitViews.swift | onchain/ExitScreens.kt | Choose rows; the VTXO list's helper on both |
| Misc | Noah is locked / Unlock to see your wallet. (lock cover) | Noah is Locked / Authenticate to access your wallet (dialogs covered while locked) | Noah is locked / Unlock to see your wallet. | Features/Settings/WalletLock.swift | ui/WalletPrivacy.kt | prototype SCR.locked; Android already used them on its lock cover |
| On-chain | Renew {n} VTXOs (L10n.plural) | tr() on the plural resource (showed a literal "{n}") | (plural on both) | Features/Onchain/VtxosView.swift | onchain/VtxosScreen.kt | renew sheet title; Android now trPlural |

## Formatting bugs fixed on the way

- **Android `tr()` on plural-only sources** returned the raw source, so the screen showed a literal `{n}`: recurring "Every {n} weeks" and "Every {n} months" (list and editor), the exit's "Your bitcoin is on-chain, locked for {n} blocks…", and the renew sheet's "Renew {n} VTXOs". All now use `trPlural`, as iOS uses `L10n.plural`. A sweep of both apps for `tr(` / `L10n.text(` / `Text(` on a plural source finds no others.
- **iOS recurring intervals past one** ("Every 2 weeks") were built in English and never translated; they now use the same plural strings as Android.
- **iOS Esplora errors** were prefixed "Not changed." even when the message already said "Endpoint not changed. …".
- **Android's board review explanation** and several Android rows ("VTXOs selected: 3", "Repeat protection overridden: …", the Pending Rounds lines, "Lightning receive canceled") were built by interpolation and could never be translated; they now translate.

## Diagnostics

Log lines, preconditions, identifiers and exception messages that never reach the UI now use the form the extractor skips (`#"…"#` in Swift, `"""…"""` in Kotlin), which keeps their values byte for byte. Shown diagnostics keep the user-visible behaviour: they take the other platform's user-facing message for the same situation where one exists, and stay (allowlisted) otherwise.

| Platform | String | File | Shown? | Outcome |
| --- | --- | --- | --- | --- |
| ios | Reconciled payment; repeat metadata could not be saved | Services/Wallet/WalletSession+Send.swift | no (log) | removed |
| android | Journal quarantine unavailable | send/SendController.kt | no (caught by clear(), which shows its own message) | removed |
| android | Override audit storage unavailable | send/SendController.kt | no (caught by clear()) | removed |
| android | Recurring evidence storage unavailable | send/SendController.kt | no (caught; Send shows "Payment status could not be saved…") | removed |
| android | Combined errors render their children | send/SendDestination.kt | no (programmer error) | removed |
| android | Unable to save override audit | send/SendOverrideAudit.kt | no (caught by clear()) | removed |
| android | Unable to preserve unreadable Send evidence | send/SendOverrideAudit.kt | no (caught by clear()) | removed |
| ios | M / H (QR error-correction levels) | DesignSystem/StyledQRCode.swift, Features/Receive/ReceiveView.swift | no (CoreImage parameter) | removed |
| ios | Receive operation failed ({nativeValue1}): {nativeValue2} | Features/Receive/ReceiveModel.swift | no (log) | removed |
| ios | Fiat rate unavailable: {nativeValue1} | Features/Receive/ReceiveView.swift | no (log) | removed |
| android | Receive amount must be a positive whole number of sats | services/wallet/ReceiveRequest.kt | no (caught; Receive shows "Could not generate a Lightning invoice…") | removed |
| android | Note must be 639 characters or fewer | services/wallet/ReceiveRequest.kt | no (caught; the note field shows the shared "…fewer." line) | removed |
| ios | Pending round status unavailable | Features/Home/HomeModel.swift | no (log) | removed |
| ios | Renewal status unavailable | Features/Home/HomeModel.swift | no (log) | removed |
| ios | Wallet status observation unavailable | Features/Home/HomeModel.swift | no (log) | removed |
| ios | Manual backup failed | Features/Home/HomeModel.swift | no (log) | removed |
| ios | Last backup {time} | Features/Home/HomeModel.swift | no (computed, never shown: Home toasts only "Backup completed") | removed |
| android | Last backup {nativeValue1} | ui/home/HomeRules.kt | no (computed, never shown) | removed |
| android | Refunded (row state token) | ui/home/HomeRows.kt | no (Home shows "Refunded · back in your wallet") | removed |
| android | {nativeValue1}: {nativeValue2} (round transaction line) | ui/home/HomeBanners.kt | yes (Pending Rounds sheet) | now tr(label) + ": " + txid |
| ios | History export failed | Features/History/ActivityExport.swift | no (log) | removed |
| ios | T (ISO date separator) | Features/History/HistoryTransaction.swift | no (date parsing) | removed |
| android | UTC (time zone ID) | btcmap/BtcMapData.kt, history/HistoryTransaction.kt, recurring/RecurringSchedule.kt, ui/settings/ExportFilename.kt | no (identifier) | removed |
| android | Cannot inspect history exports | history/HistoryExportFiles.kt | no (wallet deletion maps it to "Noah couldn't remove temporary wallet files…") | removed |
| android | Cannot delete history export | history/HistoryExportFiles.kt | no (as above) | removed |
| android | Unreadable image | ui/settings/OtherScreens.kt | no (caught; Feedback shows "Couldn't read the screenshot…") | removed |
| android | Unknown app variant | services/server/LightningIdentity.kt, services/server/ServerClient.kt, services/wallet/WalletConfiguration.kt, ui/settings/ArkInfoEndpoints.kt | no (programmer error) | removed |
| android | E (log level) | services/logging/AppLog.kt, ui/settings/AdvancedPages.kt | no (log format) | removed |
| ios | Action completed successfully | Features/Settings/DebugView.swift | yes (Debug result, verbatim) | now Android's per-action results |
| ios | Auto-board didn't offer a board: each preparation spent an exit claim to this phone | Services/Wallet/WalletSession+Onchain.swift | no (log) | removed |
| ios | Retaining prepared board for cleanup retry | Services/Wallet/WalletSession+PreparedBoard.swift | no (log) | removed |
| android | Prepared board cleanup failed; retaining its handle for retry | onchain/BoardPreparations.kt | no (log) | removed |
| android | Auto-board: every preparation spent a coin claimed to this phone, so no deposit is offered | onchain/OnchainOperations.kt | no (log) | removed |
| android | Cannot publish wallet journal | onchain/OnchainOperationJournal.kt | no (caught; the on-chain model shows its own message) | removed |
| android | Unsupported | onchain/OnchainWalletAccess.kt | no (interface default, programmer error) | removed |
| ios | Recurring wake schedule sync failed; retained for retry | Features/Recurring/RecurringExecutor.swift | no (log) | removed |
| ios | ArkInfo unavailable; using the published Lightning fee schedule | Features/Recurring/RecurringLive.swift | no (log) | removed |
| android | Audit unavailable | recurring/RecurringExecutor.kt | no (default dependency, replaced in production) | removed |
| android | Expected an integer token | recurring/RecurringStore.kt | no (row decode is caught; the row shows as unsupported) | removed |
| android | Duplicate recurring property | recurring/RecurringStore.kt | no (as above) | removed |
| android | Unable to save recurring payments | recurring/RecurringStore.kt | yes (editor and actions show e.message) | now "Recurring payment storage failed. Restart Noah; sending is stopped." |
| android | Unable to save recurring payment evidence | recurring/RecurringStore.kt | yes (executor shows e.message) | now "Recurring payment storage failed. Restart Noah; sending is stopped." |
| android | Unsupported recurring payment version. Sending is stopped. | recurring/RecurringStore.kt | yes (on save) | now "Recurring payment data couldn't be read. …" |
| android | Check the current payment before making another payment. | recurring/RecurringExecutor.kt | yes (Review and pay) | now "This payment may have been sent. Check its status before making another payment." |
| android | Recurring background work stopped. / Wallet suspended or replacement pending. Sending is stopped. / Unable to check this payment. Sending remains stopped. | recurring/*.kt | yes | kept, allowlisted (no iOS counterpart) |
| ios | © OpenStreetMap / © OpenMapTiles / BTC Map / GeoNames (attribution links) | Features/BtcMap/BtcMapView.swift | shown, but names, not copy | removed |
| ios | US (country code) | Features/BtcMap/CitySearch.swift | no (identifier) | removed |
| ios | {value1}\nhttps://btcmap.org/merchant/{value2} (share text) | Features/BtcMap/BtcMapDetailView.swift | shared text, not copy (as on Android) | removed |
| android | Invalid map data / Map data exceeds resource limits / Map request failed / Map cache could not be saved | btcmap/*.kt | no (the map shows its own state) | removed |
| android | Legacy Android callback (@Deprecated message) | btcmap/BtcMapLocation.kt | no (annotation) | removed |
| android | Existing wallet data is incomplete | services/wallet/WalletCreator.kt | no (onboarding shows "Creation failed. Please try again.") | removed |
| android | Noah support / Noah Android {version} ({variant}): / I will not share my recovery phrase. | ui/LaunchGateView.kt | copied to the clipboard for support, not localized | removed |
| android | Missing plural resource: {nativeValue1} | localization/L10n.kt | no (programmer error) | removed |
| android | Wallet seed unavailable | services/AppServices.kt, services/storage/StoredWalletOpener.kt | no (ServerFailure shows only the server's message; background opener) | removed |
| android | Interrupted restore not settled yet / Wallet is not ready for backup / Invalid export name / Backup download exceeds limit | services/AppServices.kt, services/backup/*.kt | no (caught and mapped) | removed |
| android | Cannot read staged files / Cannot inspect scratch files / Cannot inspect settings exports / Cannot inspect wallet directory / Cannot create wallet directory / Cannot read bark wallet properties | services/*.kt | no (caught and mapped) | removed |
| android | Server authentication failed / Server connection failed / Wallet unavailable | services/server/*.kt | no (ServerFailure.message shows only the server's message) | removed |
| android | Invalid server token expiry | services/server/JWT.kt | no | removed |
| android | Secure key unavailable / Secure file unreadable | services/storage/AndroidSecretStorage.kt | no (mapped to SecretFailure) | removed |
| android | Read only | services/storage/RNStoreCodec.kt | no (read-only store's write lambda) | removed |
| android | Finish onboarding before background wallet work | services/storage/StoredWalletOpener.kt | no (background work) | removed |
| android | HTTP failure | services/wallet/EsploraEndpoint.kt | no (rethrown as "Unable to reach the Esplora endpoint…") | removed |
| android | Restart Noah to settle the pending restore before deleting | services/wallet/WalletLifecycle.kt | no (Delete shows "Couldn't delete wallet. Restart Noah and try again.") | removed |
| android | Emulator / Android SDK built for | ui/launch/PlatformAccess.kt | no (Build.MODEL checks) | removed |
| android | UnifiedPush server registration failed; queued for retry | ui/launch/PlatformScreens.kt | no (log) | removed |
| ios | NoahAppVariant missing or invalid in Info.plist / Missing bundled BIP39 word list | App/AppVariant.swift, Services/Logging/AppLog.swift | no (preconditionFailure) | removed |
| ios | Failed to check app version, allowing app to continue / Successfully registered with server / Failed to save email prompt dismissal | Services/Server/ServerRegistration.swift | no (log) | removed |
| ios | Email already verified | Services/Server/ServerRegistration.swift | no (compared with the server's message) | removed |
| android | Noah is Locked / Authenticate to access your wallet | ui/WalletPrivacy.kt | yes (cover over dialogs while locked) | now the lock cover's "Noah is locked" / "Unlock to see your wallet." |
| android | Boarding completed (toast key) | ui/home/HomeRules.kt | no (the toast says "{amount} available in Ark") | removed |
| android | delete (launch gate field label) | ui/LaunchGateView.kt | yes (the word to type) | now SettingsRules.deleteWord, never translated |

Shown diagnostics that changed: Android recurring's storage and version errors (they reached the editor and list through `e.message`), Android's Review and pay guard, Android's dialog lock cover, and iOS Debug's generic result. Shown diagnostics kept and allowlisted: Android recurring's "Recurring background work stopped.", "Wallet suspended or replacement pending. Sending is stopped." and "Unable to check this payment. Sending remains stopped."; Android's "Saved settings could not be read…", "Secure storage unavailable", "Native settings write failed" and "Wallet not open", which can reach the recurring screens through `e.message`; and iOS Debug's push results.

## Allowlist

Every entry of `apps/i18n/platform-only.json`, grouped by its reason. Kinds: **platform** (a genuine platform difference), **spec** (a difference the exit spec chose), **shared** (both apps show it, but one app's copy sits where the extractor can't see it), **dead code**, **flagged** (left for a decision), **diagnostic** (shown, see above) and **parity-gap** (one app has it; "layout" when the other shows the same information differently, "accessibility" when only one labels it, "not localized" when one app builds it in English).

### iOS (124)

- **platform: Settings naming and wording for the location permission**
  - "You can still browse the full map. Enable location in Settings to see nearby places."
- **platform: after a denial, iOS grants camera access only in the Settings app**
  - "Allow camera access in Settings to scan a payment QR code."
- **platform: iOS blurs secrets itself when the app is inactive or captured; Android's FLAG_SECURE blanks the window**
  - "Hidden for your security"
- **platform: iOS names the device's biometric (Face ID, Touch ID or Optic ID)**
  - "Face ID"
  - "Optic ID"
  - "Touch ID"
- **platform: iOS's number-pad keyboard has no return key, so restore adds a Hide keyboard button**
  - "Hide keyboard"
- **dead code: PendingFeatureView (Features/Onboarding/OnboardingFlow.swift) has no callers**
  - "Coming soon in the native app."
- **dead code: SendQuote.recipientDescription (Features/Send/SendPayment.swift) is read only by tests**
  - "Recipient receives about {nativeValue1} sats"
- **flagged: Android's launch gate reads hand-written English-only values/native.xml (outside the catalogs and the extractor) and groups secure storage, invalid seed and seed conflict under one message; unifying this safety copy needs your wording decision**
  - "Conflicting wallet data"
  - "Noah can't read its secure storage yet. Unlock your device to continue."
  - "Noah couldn't finish or undo an interrupted restore. Your wallet files were left as they are. Restart Noah to try again."
  - "Recovery phrase needed"
  - "Restore didn't finish"
  - "Support code: SEED_INVALID. The stored recovery phrase could not be validated. Keep this app installed and contact Noah support at github.com/smolcars/noah. Never send anyone your recovery phrase, private keys, or a wallet database."
  - "Support code: WALLET_CONFLICT. Noah found wallet records that cannot be safely matched. Keep this app installed and contact Noah support at github.com/smolcars/noah. Never send anyone your recovery phrase, private keys, or a wallet database."
  - "Use the recovery phrase stored on this device to restore your backup."
  - "Waiting for secure storage"
  - "Wallet data exists on this device, but its recovery phrase is missing. Noah won't replace these files. Keep your recovery phrase and contact support for help recovering this wallet."
  - "Wallet recovery needed"
- **parity-gap (accessibility): Android's Edit link beside the address has no extra label**
  - "Edit email address"
- **parity-gap (accessibility): Android's deposit QR is labelled with its tap action ("Show payment details") instead**
  - "Bitcoin deposit address QR code"
- **parity-gap (accessibility): Android's map view has no accessibility label**
  - "Bitcoin merchant map"
- **parity-gap (accessibility): Android's on-chain progress bar has no label**
  - "Updating wallet"
- **parity-gap (accessibility): iOS labels onboarding's email field "Email Address"; Android labels it with its prompt ("Add your email")**
  - "Email Address"
- **parity-gap (accessibility): iOS labels the hidden phrase grid; Android labels its Show button instead**
  - "Recovery phrase hidden"
- **parity-gap (accessibility): iOS's end date picker has a label; Android's date row doesn't**
  - "End date"
- **parity-gap (accessibility): only iOS gives the hold-to-start pill a VoiceOver action with the amount**
  - "Start exit of {amount}"
- **parity-gap (accessibility): only iOS labels the shared-transactions picture; Android's exit picture labels itself instead**
  - "Shared transactions are sent and paid once."
- **parity-gap (layout): Android shows the minimum as a Smallest move row instead**
  - "At least {amount}."
- **parity-gap (layout): iOS explains a batched claim in a sentence; Android shows this exit's share in the Claim fee row**
  - "One claim for {n} exits: {amount} received after a {fee} fee."
- **parity-gap (layout): iOS folds an override's audit text into a disclosure; Android lists it**
  - "Override confirmation"
- **parity-gap (layout): iOS relabels Debug's Run button while it runs; Android shows a spinner**
  - "Running"
- **parity-gap (layout): iOS states the existing payment's fee as a sentence; Android shows an Existing fee row**
  - "Fee for the existing payment: {nativeValue1} sats"
- **parity-gap (layout): iOS takes a custom interval and a payment count in text fields; Android uses steppers**
  - "Every … days"
  - "Number of payments"
  - "days"
  - "payments"
- **parity-gap (layout): iOS titles a sent result without a receipt "Payment sent"; Android's says "Sent {amount}" with the reviewed amount**
  - "Payment sent"
- **parity-gap (layout): iOS titles its other Receive errors; Android titles only a failed Lightning receive**
  - "Couldn’t create a request"
- **parity-gap (layout): iOS writes the count line as one template; Android joins its parts**
  - "{n} in progress · {count} ready to claim"
- **parity-gap (layout): iOS's QR hub scan mode has a title, a line and a labelled paste button; Android's opens the camera**
  - "Paste payment request"
  - "QR mode"
  - "Scan a Bitcoin, Lightning, or Ark QR code and send from Noah."
  - "Scan to pay"
- **parity-gap (layout): iOS's result line for a send whose amount bark hasn't recorded (a MAX offboard); Android shows "Actual fee pending" in the Fee row instead**
  - "Recipient amount pending"
- **parity-gap (layout): iOS's start review shows when the exit can be claimed; Android's summary says "about a day"**
  - "In {time}"
- **parity-gap (layout): iOS's status word for the sending phases; Android shows "Sending {n} of {total}" there**
  - "Sending to the chain"
- **parity-gap (layout): iOS's ⋯ menu groups the filters under a Filter heading; Android uses a chip**
  - "Filter"
- **parity-gap (layout): the BTC Map screens were built differently and have no shared design spec; iOS groups search results into cities and places and has its own empty states**
  - "Bitcoin places"
  - "Locations"
  - "No matching cities or Bitcoin places"
  - "No places in view"
- **parity-gap (layout): the BTC Map screens were built differently and have no shared design spec; iOS labels its loading and error states**
  - "Cannot open link"
  - "Could not open BTC Map"
  - "Loading details…"
  - "Opening the bundled BTC Map…"
- **parity-gap (layout): the BTC Map screens were built differently and have no shared design spec; iOS offers Retry buttons where Android shows a status line**
  - "Basemap unavailable · Retry"
  - "Offline · Retry"
- **parity-gap (layout): the BTC Map screens were built differently and have no shared design spec; iOS's load error has Retry, so it doesn't ask you to reopen the map**
  - "The bundled places file could not be read."
- **parity-gap (not localized): iOS localizes this with L10n, as RN did; Android builds it by interpolation in the model and always writes English (CSV date column; only iOS also shows it as a Home row's time)**
  - "Confirmed at block {height}"
- **parity-gap (not localized): iOS localizes this with L10n, as RN did; Android builds it by interpolation in the model and always writes English (CSV price header)**
  - "BTC Price ({currency})"
- **parity-gap (not localized): iOS localizes this with L10n, as RN did; Android builds it by interpolation in the model and always writes English (CSV type column; Android also uses it as a canceled Home row's title)**
  - "Canceled {value1}"
- **parity-gap (shown): iOS names the invalid field; Android shows one combined message**
  - "Custom intervals must be between 1 and 365 days."
  - "Enter an Ark address, Lightning address or BOLT12 offer."
  - "Enter how many payments should be made."
  - "The end date must be after the first payment."
- **parity-gap (shown): iOS's Settings Email page reports a failed server registration on its own; Android's registers inside Send code and reports a failed send**
  - "Failed to register with server. Please try again."
- **parity-gap (shown): only iOS checks recurring consumption evidence with its own message**
  - "Recurring consumption evidence is incomplete. Sending remains stopped."
- **parity-gap (shown): only iOS guards saving an identity while registration is running**
  - "Registration is in progress."
- **parity-gap (shown): only iOS names incomplete wallet data when creating; Android shows "Creation failed"**
  - "Existing wallet data is incomplete. Restore your wallet to continue."
- **parity-gap (shown): only iOS reads the recurring wallet identity with its own message**
  - "Recurring wallet identity couldn't be read. Sending is stopped."
- **parity-gap: Android hides the fiat line when there's no rate**
  - "{currency} rate unavailable"
- **parity-gap: Android shows no line for a paid Ark send with a fee**
  - "It arrived instantly."
- **parity-gap: Android shows no message when Send can't read balances**
  - "Unable to read available balances."
- **parity-gap: Android writes "{n} blocks" even for one block**
  - "{count} block"
- **parity-gap: Android's Email page shows no current-email placeholder or New email label (prototype has both)**
  - "New email"
  - "No email yet"
- **parity-gap: Android's To results don't show an invoice's amount**
  - "Asks for {amount}"
- **parity-gap: Android's VTXO select mode lacks Select visible, Select expiring and Clear selection (the settings notes keep them on both)**
  - "Clear selection"
  - "Select expiring"
  - "Select visible"
- **parity-gap: Android's board amount field accepts digits only, so it never needs this**
  - "Enter whole sats."
- **parity-gap: Android's coin rows have no accessibility hint (prototype: "Opens coin details")**
  - "Opens coin details"
- **parity-gap: Android's debug push action shows the raw registration outcome instead (debug-only, shown verbatim)**
  - "Push permission not granted"
  - "Push tokens are only available on a physical device"
- **parity-gap: Android's debug-mode switch has no save-failure message**
  - "Couldn't save debug setting."
- **parity-gap: Android's fiat/sats toggle has no accessibility label**
  - "Switch amount currency"
  - "Switch to sats"
  - "Switch to {value1}"
- **parity-gap: Android's method title shows the amount even for Max**
  - "Maximum available"
- **parity-gap: Android's scanner shows nothing while it asks for camera access**
  - "Requesting camera access…"
- **parity-gap: Android's status-unavailable result has no explanation line**
  - "Noah could not read or save the payment attempt. Sending is disabled until its status can be recovered."
- **parity-gap: Android's story player slider has no accessibility label**
  - "Playback position"
- **parity-gap: iOS doesn't know the bumped fee rate, so it can't say "Paying {rate} sat/vB" (prototype)**
  - "Fees rose. Paying more · waiting for block {height}"
- **parity-gap: iOS gives a not-sent result a fallback line; Android shows only the failure message**
  - "Submission stopped before a transaction could be recorded. Review the amount and fee before trying again."
  - "Wallet history confirms this payment failed or was canceled."
- **parity-gap: iOS names the invalid-destination and sub-satoshi cases; Android shows its generic recipient error**
  - "Enter a valid Bitcoin, Lightning, or Ark destination."
  - "The invoice must request whole satoshis."
- **parity-gap: iOS's already-paid result explains it; Android's shows only the title**
  - "No new payment was made for the amount you reviewed."
  - "This invoice was already paid: {amount}"
- **parity-gap: iOS's may-have-been-sent result lists history candidates, possible txids and the reviewed amount; Android's doesn't**
  - "Appears to have gone through — check History"
  - "No matching payment is visible in History yet."
  - "Noah can’t find this transaction right now. Its confirmation may no longer be trackable in-app. Check a block explorer."
  - "Possible related payment in History"
  - "This differs from the {nativeValue1} sats you reviewed."
  - "This transaction may be your payment: {nativeValue1}"
- **parity-gap: iOS's stale warning can't give bark's last-checked block, which Android's names (prototype)**
  - "This exit is behind the current chain height. Use Check the chain to check it; this can also send exit transactions."
- **parity-gap: only iOS explains the Lightning fee limits on the list**
  - "Lightning fee limits use Ark’s published fees, up to 2% or 200 sats, whichever is higher. Payments above the limit need manual approval."
- **parity-gap: only iOS fetches a fresh estimate when the hold-to-confirm review expires**
  - "The estimate expired, so Noah fetched a new one. Check it and hold again."
- **parity-gap: only iOS names a failed wallet cleanup on the launch gate**
  - "Wallet cleanup didn't finish"
- **parity-gap: only iOS names the unlock block once no blocks are left**
  - "Unlocks at block {height}"
- **parity-gap: only iOS names this phone as a claimed exit's destination**
  - "This phone"
- **parity-gap: only iOS offers the wallet's reserved name as a chip when editing the Lightning address**
  - "Use {name}"
- **parity-gap: only iOS says a failed refresh was because you're offline**
  - "Couldn't check the latest status — you're offline. Showing the last known status."
- **parity-gap: only iOS says when an on-chain status check failed and the last known status is shown**
  - "Couldn't check network status. Showing the last known status."
- **parity-gap: only iOS stops a scan while a payment is open (the deep-link work adds the Android side)**
  - "Finish the current payment before scanning another request."
  - "Payment in progress"
- **parity-gap: only iOS toasts after Check the chain (prototype has it)**
  - "Checked the chain"
- **parity-gap: only iOS's Receive, Home and QR hub QR has a fallback when the code can't be drawn**
  - "QR code unavailable. Use payment details to copy your request."
- **parity-gap: only iOS's recurring editor has an intro line**
  - "Noah sends this payment automatically from your Ark balance. Only this recipient and amount are ever allowed, and you can pause or cancel at any time."
- **parity-gap: only iOS's review notes the signed transaction's two-minute expiry**
  - "This signed transaction expires after two minutes. Review again for a fresh fee rate."
- **spec (exit.md, Platform choices): iOS shows its own last Check the chain tip as "Last checked" and the claim transaction**
  - "Claim transaction"
  - "Last checked"

### Android (163)

- **platform: Android battery optimisation**
  - "Battery"
  - "Couldn't open battery settings"
  - "Let Noah run in the background"
  - "Open battery settings"
  - "Optimized"
  - "Otherwise Android may pause Noah, and payments could stop arriving."
  - "Please open your device settings and allow Noah to run in the background, then continue."
  - "Unrestricted"
- **platform: Android can ask for the camera permission again in-app**
  - "Allow camera"
- **platform: Android intents can be refused with a SecurityException, have a chooser title and can find no app to share with**
  - "No app is available to share this place."
  - "Share place"
  - "This link could not be opened."
- **platform: Android notification channels and the foreground-service notification**
  - "Default"
  - "Noah notifications"
  - "Updating Noah wallet"
- **platform: Android's fallback when an exception carries no message; Swift errors always describe themselves**
  - "Endpoint not changed"
- **platform: Android's share intent takes a subject and chooser title; iOS's share sheet has neither**
  - "Export transactions"
  - "Noah Wallet Transaction Export"
- **platform: Settings naming and wording for the location permission**
  - "You can still browse the full map. To see nearby places, allow location in Android Settings."
- **platform: UnifiedPush and distributors replace Google Play's push where it's missing**
  - "Check again"
  - "Copy Endpoint"
  - "Couldn't register with UnifiedPush. Please try again."
  - "Current Endpoint:"
  - "Deliver with"
  - "Endpoint"
  - "Google Play Services is not available. To receive notifications, please use a UnifiedPush distributor (like ntfy)."
  - "Install a distributor app like "ntfy" from F-Droid to enable push notifications."
  - "Install ntfy from F-Droid, then come back."
  - "Loading distributors…"
  - "No Google Play here, so another app delivers notifications."
  - "No distributor found"
  - "No distributors detected. Install ntfy or another UnifiedPush distributor."
  - "Not set up"
  - "Register"
  - "Register again"
  - "Register with UnifiedPush"
  - "Registering..."
  - "Select Distributor"
  - "UnifiedPush"
  - "UnifiedPush Setup"
  - "UnifiedPush endpoint"
- **platform: an Android web link can fail when no browser is installed; iOS always opens Safari**
  - "No browser available to open the explorer."
- **platform: fallback for a Kotlin exception without a message; Swift errors always describe themselves**
  - "Couldn't save this payment. Try again."
  - "Couldn't update recurring payments. Try again."
  - "Recurring sending is stopped."
- **platform: iOS uses the system search field, which has its own Cancel**
  - "Close search"
- **platform: iOS's DateFormatter writes relative dates ("Today at 9:41") itself; Android composes them**
  - "{day} at {time}"
- **dead code: VtxoSelectionRow (onchain/VtxoSelectionRow.kt) has no callers**
  - "VTXO {nativeValue1}…, {nativeValue2}, {nativeValue3}, {nativeValue4}"
  - "{nativeValue1}… · {nativeValue2}"
- **diagnostic (can reach the recurring screens, which show e.message); iOS reports these cases with other errors**
  - "Native settings write failed"
  - "Saved settings could not be read. Keep Noah installed and contact support."
  - "Secure storage unavailable"
  - "Wallet not open"
- **parity-gap (accessibility): Android labels the Show button; iOS labels the hidden phrase grid instead**
  - "Show recovery phrase"
- **parity-gap (accessibility): Android labels the exit picture (prototype aria-label); iOS hides it from VoiceOver**
  - "Your VTXO’s path to the chain"
- **parity-gap (accessibility): only Android names the version row's tap action**
  - "Version"
- **parity-gap (layout): Android joins these parts; iOS writes the count line as one template**
  - "No exits in progress"
  - "{n} in progress"
  - "{n} ready to claim"
- **parity-gap (layout): Android labels busy buttons; iOS shows a spinner**
  - "Checking…"
  - "Creating invoice…"
  - "Preparing…"
- **parity-gap (layout): Android relabels Run due payments now while it runs; iOS shows a spinner**
  - "Sending…"
- **parity-gap (layout): Android relabels the backup Retry button while it works; iOS disables it**
  - "Working"
- **parity-gap (layout): Android shows 'Loading…' text on several screens where iOS shows a spinner**
  - "Loading…"
- **parity-gap (layout): Android shows an Existing fee row; iOS states the existing payment's fee as a sentence**
  - "Existing fee"
- **parity-gap (layout): Android shows recovery failures in a retry dialog; iOS's launch gate asks you to restart**
  - "Couldn't finish wallet recovery. Your wallet files have been kept. Please try again."
  - "Please try again"
- **parity-gap (layout): Android shows this exit's share in the Claim fee row; iOS explains a batched claim in a sentence**
  - "{amount} · share of {total} for {n} exits"
- **parity-gap (layout): Android's custom interval is a stepper that starts at every day**
  - "Every day"
- **parity-gap (layout): Android's launch-gate delete is a typed-confirmation dialog; iOS uses its delete sheet**
  - "This removes your wallet and recovery phrase from this device. Make sure you have saved your recovery phrase. Type delete to confirm."
- **parity-gap (layout): Android's logs page has a refresh button; iOS's pulls to refresh**
  - "Refresh app logs"
- **parity-gap (layout): Android's method step has a subtitle; iOS shows the recipient there**
  - "Choose payment method"
- **parity-gap (layout): Android's recurring detail page has a title; iOS's has none**
  - "Recurring payment"
- **parity-gap (layout): Android's start summary says "about a day"; iOS's review shows when it can be claimed**
  - "about a day"
- **parity-gap (layout): Android's wide progress page has a second line under the status (prototype `sub`); iOS's doesn't**
  - "Claimable in about {time}"
  - "Claimable since block {height}"
  - "Fees rose, so Noah paid more"
  - "Locked until block {height}"
  - "Sent to {address}"
  - "Waiting for a block with room"
  - "Your VTXO stays in Ark"
- **parity-gap (layout): the BTC Map screens were built differently and have no shared design spec; Android filters by category, so its empty line names categories**
  - "No matching places · try another search or category"
- **parity-gap (layout): the BTC Map screens were built differently and have no shared design spec; Android shows a status line where iOS offers Retry buttons**
  - "Offline places · live updates unavailable"
  - "Street map unavailable · places and city search still work"
- **parity-gap (layout): the BTC Map screens were built differently and have no shared design spec; Android's load error has no Retry, so it asks you to reopen the map**
  - "The bundled places could not be read. Reopen the map to try again."
- **parity-gap (layout, not localized): Android prefixes opening hours with "Hours:" by interpolation; iOS shows a clock icon**
  - "Hours: {nativeValue1}"
- **parity-gap (not localized): iOS localizes this with L10n, as RN did; Android builds it by interpolation in the model and always writes English (CSV date column; only iOS also shows it as a Home row's time)**
  - "Confirmed at block {nativeValue1}"
- **parity-gap (not localized): iOS localizes this with L10n, as RN did; Android builds it by interpolation in the model and always writes English (CSV price header)**
  - "BTC Price ({nativeValue1})"
- **parity-gap (not localized): iOS localizes this with L10n, as RN did; Android builds it by interpolation in the model and always writes English (CSV type column; Android also uses it as a canceled Home row's title)**
  - "Canceled {nativeValue1}"
- **parity-gap (not localized): iOS shows the same unit label from a SettingsRow value, verbatim**
  - "sats"
- **parity-gap (shown): Android replaces a failed status check with this; iOS shows the underlying error**
  - "Unable to check this payment. Sending remains stopped."
- **parity-gap (shown): Android reports a background run that ran out of time; iOS stops silently**
  - "Recurring background work stopped."
- **parity-gap (shown): Android reports a failed verify request this way; iOS treats every failure as a wrong code**
  - "Invalid or expired verification code. Please try again."
- **parity-gap (shown): Android shows one combined message; iOS names the invalid field**
  - "Enter an Ark address, Lightning address or BOLT12 offer and a valid interval."
- **parity-gap (shown): Android's admission check has one message for a suspended or replaced wallet; iOS reports these cases through other errors**
  - "Wallet suspended or replacement pending. Sending is stopped."
- **parity-gap (shown): only Android journals the claim-to-phone decision before sending and reports when it can't**
  - "Couldn't record that these coins stay on-chain, so nothing was sent. Try again."
- **parity-gap: Android asks 'Have you checked?' before the could-pay-twice warning; iOS goes straight to the warning**
  - "Check your history and confirm with the recipient. This payment may already have been sent."
  - "I checked history"
- **parity-gap: Android copies a support message; iOS puts support codes in the launch gate text**
  - "Copy support message"
- **parity-gap: Android falls back to this when the exit delta is unknown; iOS assumes 144 blocks**
  - "Your bitcoin is on-chain and locked for a while. The lock gives Ark time to stop a VTXO that was already spent."
- **parity-gap: Android names the bumped fee rate (prototype); iOS can't**
  - "Fees rose. Paying more"
  - "Paying {rate} sat/vB · waiting for block {height}"
- **parity-gap: Android's amount follows the BIP-177 unit; iOS's always says sats**
  - "each time"
- **parity-gap: Android's exit Details list each transaction with its name, block and fee rate (prototype); iOS's don't**
  - "After the timelock"
  - "Blocks {from}–{to}"
  - "In a block"
  - "Tree branch"
  - "Tree root"
  - "Your VTXO"
  - "{rate} sat/vB, raised when rates rise"
- **parity-gap: Android's feedback screenshot crop dialog, which the settings spec keeps Android-only**
  - "Bottom"
  - "Crop Screenshot"
  - "Right"
  - "Top"
- **parity-gap: Android's stale warning names bark's last-checked block (prototype); iOS's can't**
  - "This exit is behind the current chain height: last checked at block {height}, {n} blocks ago. Checking the chain can also send exit transactions."
- **parity-gap: Android's tappable exit-deposit QR has its own fallback (a tap copies the address)**
  - "QR unavailable. Tap to copy payment details."
- **parity-gap: iOS formats the round interval with the system duration formatter ("1 hour", as the prototype); Android shows seconds**
  - "{n} seconds"
- **parity-gap: iOS shows no toast after a renewal (prototype: "Joined the next round")**
  - "Joined the next round"
- **parity-gap: iOS shows no toast after turning debug off or resuming the wallet**
  - "Debug off"
  - "Wallet resumed"
- **parity-gap: iOS shows nothing when a VTXO filter matches none**
  - "No VTXOs in this view."
- **parity-gap: iOS's Ark server page has no network chip (prototype has one)**
  - "Mainnet"
  - "Regtest"
  - "Signet"
- **parity-gap: iOS's first-payment card doesn't show who paid (spec: "from {name}")**
  - "from {name}"
- **parity-gap: iOS's on-chain screens don't explain a suspended wallet**
  - "Wallet suspended. Resume your wallet in Settings."
- **parity-gap: only Android badges a shared transaction in the picture**
  - "shared"
- **parity-gap: only Android can hide the places list**
  - "Hide places"
- **parity-gap: only Android checks a place's link scheme before opening it**
  - "This place provided an invalid link."
- **parity-gap: only Android distinguishes a location timeout**
  - "Location is unavailable. Check that location is enabled, or search for a city."
- **parity-gap: only Android has a fallback for an unexpected debug failure; iOS shows the error itself**
  - "Action failed. Check your input and connection before trying again."
- **parity-gap: only Android names the tap action on the Home and QR hub Lightning address QR; iOS reads the card's content**
  - "Copy Lightning address"
- **parity-gap: only Android names these two restore open failures; iOS falls back to its general restore error**
  - "Noah couldn't open the restored wallet safely. Close and restart Noah to continue."
  - "Wallet data already exists on this device. Noah won't replace it. Return to your wallet or restart Noah to recover safely."
- **parity-gap: only Android offers Retry when live place details fail**
  - "Retry details"
- **parity-gap: only Android offers a manual refresh on a sent payment with a txid**
  - "Refresh payment status"
- **parity-gap: only Android reports a failed finish step in onboarding**
  - "Couldn't finish setup. Please try again."
- **parity-gap: only Android reports a feedback screenshot it couldn't read**
  - "Couldn't read the screenshot. Please try again."
- **parity-gap: only Android saves the map position and reports when it can't**
  - "The map position could not be saved."
- **parity-gap: only Android says so when a review expires; iOS just closes it**
  - "This review expired. Review the payment again."
- **parity-gap: only Android says when an exits filter matches none**
  - "No exits in this view."
- **parity-gap: only Android shows a sent receipt's chain status row**
  - "Conflicted"
  - "Unable to locate"
- **parity-gap: only Android's On-chain page says how many confirmations a board needs**
  - "It needs {n} confirmations to arrive."
- **parity-gap: only Android's VTXO detail tells you to renew an expiring VTXO**
  - "Renew this VTXO to keep it available."
- **parity-gap: only Android's claim page says when nothing is ready**
  - "No exits are ready to claim."
- **parity-gap: only Android's emergency exit page has an All exits row and these choose and deposit states**
  - "All exits"
  - "Loading address…"
  - "No eligible spendable VTXOs."
  - "The amount required may vary."
- **parity-gap: only Android's exits list warns about stale exits**
  - "{n} exits are behind the current chain height. Use Check the chain on any of them; this can also broadcast exit transactions."
- **parity-gap: only Android's restore screen offers Resume recovery for a suspended wallet**
  - "Couldn't resume recovery. Restart Noah and try again."
  - "Resume Recovery"
  - "Your wallet is suspended. Resume recovery to restore its backup, or delete this wallet from your device."
- **parity-gap: only Android's review notes that an existing Lightning payment will be checked**
  - "An existing Lightning payment will be checked. No new payment will be created."
- **parity-gap: only Android's scanner reports a camera that fails to start, and shows an aiming hint**
  - "Camera unavailable. Close the scanner and try again."
  - "Point your camera at a payment QR code."
- **shared: iOS shows the same text from WalletLifecycle.Failure.errorDescription, which the extractor doesn't scan**
  - "Noah couldn't remove temporary wallet files. Restart Noah and try again."
  - "Noah is still finishing a backup. Try again in a moment."
  - "Restored wallet settings can't be saved yet. Try Resume again when storage is available. Recurring payments remain stopped."
  - "Waiting for secure storage. Unlock your device and try again."
- **spec (exit.md, Platform choices): Android labels bark's height "Recorded at"**
  - "Recorded at"

## Parity gaps

Features or details only one app has (the `parity-gap` entries above). Nothing was built.

**Only iOS has:**

- Android's Edit link beside the address has no extra label (accessibility, 1 string)
- Android's deposit QR is labelled with its tap action ("Show payment details") instead (accessibility, 1 string)
- Android's map view has no accessibility label (accessibility, 1 string)
- Android's on-chain progress bar has no label (accessibility, 1 string)
- iOS labels onboarding's email field "Email Address"; Android labels it with its prompt ("Add your email") (accessibility, 1 string)
- iOS labels the hidden phrase grid; Android labels its Show button instead (accessibility, 1 string)
- iOS's end date picker has a label; Android's date row doesn't (accessibility, 1 string)
- only iOS gives the hold-to-start pill a VoiceOver action with the amount (accessibility, 1 string)
- only iOS labels the shared-transactions picture; Android's exit picture labels itself instead (accessibility, 1 string)
- Android shows the minimum as a Smallest move row instead (layout, 1 string)
- iOS explains a batched claim in a sentence; Android shows this exit's share in the Claim fee row (layout, 1 string)
- iOS folds an override's audit text into a disclosure; Android lists it (layout, 1 string)
- iOS relabels Debug's Run button while it runs; Android shows a spinner (layout, 1 string)
- iOS states the existing payment's fee as a sentence; Android shows an Existing fee row (layout, 1 string)
- iOS takes a custom interval and a payment count in text fields; Android uses steppers (layout, 4 strings)
- iOS titles a sent result without a receipt "Payment sent"; Android's says "Sent {amount}" with the reviewed amount (layout, 1 string)
- iOS titles its other Receive errors; Android titles only a failed Lightning receive (layout, 1 string)
- iOS writes the count line as one template; Android joins its parts (layout, 1 string)
- iOS's QR hub scan mode has a title, a line and a labelled paste button; Android's opens the camera (layout, 4 strings)
- iOS's result line for a send whose amount bark hasn't recorded (a MAX offboard); Android shows "Actual fee pending" in the Fee row instead (layout, 1 string)
- iOS's start review shows when the exit can be claimed; Android's summary says "about a day" (layout, 1 string)
- iOS's status word for the sending phases; Android shows "Sending {n} of {total}" there (layout, 1 string)
- iOS's ⋯ menu groups the filters under a Filter heading; Android uses a chip (layout, 1 string)
- the BTC Map screens were built differently and have no shared design spec; iOS groups search results into cities and places and has its own empty states (layout, 4 strings)
- the BTC Map screens were built differently and have no shared design spec; iOS labels its loading and error states (layout, 4 strings)
- the BTC Map screens were built differently and have no shared design spec; iOS offers Retry buttons where Android shows a status line (layout, 2 strings)
- the BTC Map screens were built differently and have no shared design spec; iOS's load error has Retry, so it doesn't ask you to reopen the map (layout, 1 string)
- iOS localizes this with L10n, as RN did; Android builds it by interpolation in the model and always writes English (CSV date column; only iOS also shows it as a Home row's time) (not localized, 1 string)
- iOS localizes this with L10n, as RN did; Android builds it by interpolation in the model and always writes English (CSV price header) (not localized, 1 string)
- iOS localizes this with L10n, as RN did; Android builds it by interpolation in the model and always writes English (CSV type column; Android also uses it as a canceled Home row's title) (not localized, 1 string)
- iOS names the invalid field; Android shows one combined message (shown, 4 strings)
- iOS's Settings Email page reports a failed server registration on its own; Android's registers inside Send code and reports a failed send (shown, 1 string)
- only iOS checks recurring consumption evidence with its own message (shown, 1 string)
- only iOS guards saving an identity while registration is running (shown, 1 string)
- only iOS names incomplete wallet data when creating; Android shows "Creation failed" (shown, 1 string)
- only iOS reads the recurring wallet identity with its own message (shown, 1 string)
- Android hides the fiat line when there's no rate (1 string)
- Android shows no line for a paid Ark send with a fee (1 string)
- Android shows no message when Send can't read balances (1 string)
- Android writes "{n} blocks" even for one block (1 string)
- Android's Email page shows no current-email placeholder or New email label (prototype has both) (2 strings)
- Android's To results don't show an invoice's amount (1 string)
- Android's VTXO select mode lacks Select visible, Select expiring and Clear selection (the settings notes keep them on both) (3 strings)
- Android's board amount field accepts digits only, so it never needs this (1 string)
- Android's coin rows have no accessibility hint (prototype: "Opens coin details") (1 string)
- Android's debug push action shows the raw registration outcome instead (debug-only, shown verbatim) (2 strings)
- Android's debug-mode switch has no save-failure message (1 string)
- Android's fiat/sats toggle has no accessibility label (3 strings)
- Android's method title shows the amount even for Max (1 string)
- Android's scanner shows nothing while it asks for camera access (1 string)
- Android's status-unavailable result has no explanation line (1 string)
- Android's story player slider has no accessibility label (1 string)
- iOS doesn't know the bumped fee rate, so it can't say "Paying {rate} sat/vB" (prototype) (1 string)
- iOS gives a not-sent result a fallback line; Android shows only the failure message (2 strings)
- iOS names the invalid-destination and sub-satoshi cases; Android shows its generic recipient error (2 strings)
- iOS's already-paid result explains it; Android's shows only the title (2 strings)
- iOS's may-have-been-sent result lists history candidates, possible txids and the reviewed amount; Android's doesn't (6 strings)
- iOS's stale warning can't give bark's last-checked block, which Android's names (prototype) (1 string)
- only iOS explains the Lightning fee limits on the list (1 string)
- only iOS fetches a fresh estimate when the hold-to-confirm review expires (1 string)
- only iOS names a failed wallet cleanup on the launch gate (1 string)
- only iOS names the unlock block once no blocks are left (1 string)
- only iOS names this phone as a claimed exit's destination (1 string)
- only iOS offers the wallet's reserved name as a chip when editing the Lightning address (1 string)
- only iOS says a failed refresh was because you're offline (1 string)
- only iOS says when an on-chain status check failed and the last known status is shown (1 string)
- only iOS stops a scan while a payment is open (the deep-link work adds the Android side) (2 strings)
- only iOS toasts after Check the chain (prototype has it) (1 string)
- only iOS's Receive, Home and QR hub QR has a fallback when the code can't be drawn (1 string)
- only iOS's recurring editor has an intro line (1 string)
- only iOS's review notes the signed transaction's two-minute expiry (1 string)

**Only Android has:**

- Android labels the Show button; iOS labels the hidden phrase grid instead (accessibility, 1 string)
- Android labels the exit picture (prototype aria-label); iOS hides it from VoiceOver (accessibility, 1 string)
- only Android names the version row's tap action (accessibility, 1 string)
- Android joins these parts; iOS writes the count line as one template (layout, 3 strings)
- Android labels busy buttons; iOS shows a spinner (layout, 3 strings)
- Android relabels Run due payments now while it runs; iOS shows a spinner (layout, 1 string)
- Android relabels the backup Retry button while it works; iOS disables it (layout, 1 string)
- Android shows 'Loading…' text on several screens where iOS shows a spinner (layout, 1 string)
- Android shows an Existing fee row; iOS states the existing payment's fee as a sentence (layout, 1 string)
- Android shows recovery failures in a retry dialog; iOS's launch gate asks you to restart (layout, 2 strings)
- Android shows this exit's share in the Claim fee row; iOS explains a batched claim in a sentence (layout, 1 string)
- Android's custom interval is a stepper that starts at every day (layout, 1 string)
- Android's launch-gate delete is a typed-confirmation dialog; iOS uses its delete sheet (layout, 1 string)
- Android's logs page has a refresh button; iOS's pulls to refresh (layout, 1 string)
- Android's method step has a subtitle; iOS shows the recipient there (layout, 1 string)
- Android's recurring detail page has a title; iOS's has none (layout, 1 string)
- Android's start summary says "about a day"; iOS's review shows when it can be claimed (layout, 1 string)
- Android's wide progress page has a second line under the status (prototype `sub`); iOS's doesn't (layout, 7 strings)
- the BTC Map screens were built differently and have no shared design spec; Android filters by category, so its empty line names categories (layout, 1 string)
- the BTC Map screens were built differently and have no shared design spec; Android shows a status line where iOS offers Retry buttons (layout, 2 strings)
- the BTC Map screens were built differently and have no shared design spec; Android's load error has no Retry, so it asks you to reopen the map (layout, 1 string)
- Android prefixes opening hours with "Hours:" by interpolation; iOS shows a clock icon (layout, not localized, 1 string)
- iOS localizes this with L10n, as RN did; Android builds it by interpolation in the model and always writes English (CSV date column; only iOS also shows it as a Home row's time) (not localized, 1 string)
- iOS localizes this with L10n, as RN did; Android builds it by interpolation in the model and always writes English (CSV price header) (not localized, 1 string)
- iOS localizes this with L10n, as RN did; Android builds it by interpolation in the model and always writes English (CSV type column; Android also uses it as a canceled Home row's title) (not localized, 1 string)
- iOS shows the same unit label from a SettingsRow value, verbatim (not localized, 1 string)
- Android replaces a failed status check with this; iOS shows the underlying error (shown, 1 string)
- Android reports a background run that ran out of time; iOS stops silently (shown, 1 string)
- Android reports a failed verify request this way; iOS treats every failure as a wrong code (shown, 1 string)
- Android shows one combined message; iOS names the invalid field (shown, 1 string)
- Android's admission check has one message for a suspended or replaced wallet; iOS reports these cases through other errors (shown, 1 string)
- only Android journals the claim-to-phone decision before sending and reports when it can't (shown, 1 string)
- Android asks 'Have you checked?' before the could-pay-twice warning; iOS goes straight to the warning (2 strings)
- Android copies a support message; iOS puts support codes in the launch gate text (1 string)
- Android falls back to this when the exit delta is unknown; iOS assumes 144 blocks (1 string)
- Android names the bumped fee rate (prototype); iOS can't (2 strings)
- Android's amount follows the BIP-177 unit; iOS's always says sats (1 string)
- Android's exit Details list each transaction with its name, block and fee rate (prototype); iOS's don't (7 strings)
- Android's feedback screenshot crop dialog, which the settings spec keeps Android-only (4 strings)
- Android's stale warning names bark's last-checked block (prototype); iOS's can't (1 string)
- Android's tappable exit-deposit QR has its own fallback (a tap copies the address) (1 string)
- iOS formats the round interval with the system duration formatter ("1 hour", as the prototype); Android shows seconds (1 string)
- iOS shows no toast after a renewal (prototype: "Joined the next round") (1 string)
- iOS shows no toast after turning debug off or resuming the wallet (2 strings)
- iOS shows nothing when a VTXO filter matches none (1 string)
- iOS's Ark server page has no network chip (prototype has one) (3 strings)
- iOS's first-payment card doesn't show who paid (spec: "from {name}") (1 string)
- iOS's on-chain screens don't explain a suspended wallet (1 string)
- only Android badges a shared transaction in the picture (1 string)
- only Android can hide the places list (1 string)
- only Android checks a place's link scheme before opening it (1 string)
- only Android distinguishes a location timeout (1 string)
- only Android has a fallback for an unexpected debug failure; iOS shows the error itself (1 string)
- only Android names the tap action on the Home and QR hub Lightning address QR; iOS reads the card's content (1 string)
- only Android names these two restore open failures; iOS falls back to its general restore error (2 strings)
- only Android offers Retry when live place details fail (1 string)
- only Android offers a manual refresh on a sent payment with a txid (1 string)
- only Android reports a failed finish step in onboarding (1 string)
- only Android reports a feedback screenshot it couldn't read (1 string)
- only Android saves the map position and reports when it can't (1 string)
- only Android says so when a review expires; iOS just closes it (1 string)
- only Android says when an exits filter matches none (1 string)
- only Android shows a sent receipt's chain status row (2 strings)
- only Android's On-chain page says how many confirmations a board needs (1 string)
- only Android's VTXO detail tells you to renew an expiring VTXO (1 string)
- only Android's claim page says when nothing is ready (1 string)
- only Android's emergency exit page has an All exits row and these choose and deposit states (4 strings)
- only Android's exits list warns about stale exits (1 string)
- only Android's restore screen offers Resume recovery for a suspended wallet (3 strings)
- only Android's review notes that an existing Lightning payment will be checked (1 string)
- only Android's scanner reports a camera that fails to start, and shows an aiming hint (2 strings)


## Wording decisions

Reviewed on 2026-10-24. Accepted as chosen:

1. **Send, failed title:** "Payment not sent" (was iOS's "Payment did not complete").
2. **Send, override:** "Start a new payment after checking history" and "Keep checking". iOS still goes straight to the could-pay-twice alert; Android asks "Have you checked?" first.
3. **Send, not-sent result:** "Done" (was iOS's "Start a new payment").
4. **Send, rail lines:** "Seconds · {fee} fee" without "estimated", as the spec words them; the review still labels an estimate "Estimated fee".
5. **Send, wallet reservation failure:** "Unable to reserve the wallet. Check payment status before sending again." on both.
7. **Home, suspended wallet:** one line, "All wallet operations are disabled. Resume your wallet in Settings."
8. **Backup export toast:** "Exported".
10. **Recurring, resume after checking:** iOS's "Check Activity for this payment's last attempt and ask the recipient whether it arrived." and "I checked"; Android's Send keeps its own "Check your history…" and "I checked history".
12. **VTXOs, renew sheet line:** "Joins the next Ark round, so it stays spendable." / "…so they stay spendable."
13. **Debug confirm:** the prototype's "{action}?" with "This changes your wallet and can lose funds."
14. **Delete wallet fallback:** "Couldn't delete wallet. Restart Noah and try again."
15. **Shorter errors:** "Couldn't save your name." and "Couldn't save your preference."

Changed after review:

6. **Home, automatic boarding:** both platforms toast iOS's "{amount} available in Ark". Android formats the amount as Home does, so a hidden balance reads "••••"; its "Boarding completed" title stays only as the toast's key. Hiding amounts while the toast shows masks it at once on both platforms and leaves its end time unchanged (Android re-texts the active toast in place; iOS recomputes it on every render).
9. **Activity, exit status:** "Exiting" on both. The spec's status list says "Exiting", and where the spec and the prototype differ, the spec wins.
11. **Receive, Lightning failure:** Android now shows iOS's "Lightning Receive Canceled" or "Lightning Receive Failed" title above the shared message, so both say which it was in shared, translated strings.

Left for later:

16. **The launch gate** stays as it is, flagged and allowlisted. Unifying its safety copy, and moving Android's English-only `values/native.xml` launch-gate copy into the catalogs, are separate TODO items.

**Typed "delete" confirmations.** Every typed-delete check (the Android launch gate, Settings → Delete wallet and Debug on both platforms) now accepts one word, `SettingsRules.deleteWord` (Android) / `DeleteChecklist.word` (iOS): "delete", trimmed, any case. Android's launch gate shows that word as its field label instead of a translatable string, and Android's Debug check now trims like the others. Every translation of "Type delete to confirm" and of the launch gate's prompt keeps the literal `delete`, as the Settings spec says; `TranslationsTest` (Android), `TranslationsTests` (iOS) and the catalog tool's tests fail if a locale ever asks for another word.

## Translations checked for overclaiming

A string one platform switched onto keeps its existing translations, so each was checked for a translation that claims more than the English (sent vs submitted, paid vs pending, received vs arriving, claimed vs claimable).

- **Payment submitted** (`gt_006fbb3905701e15`): used for a broadcast that's still retrying, a MAX offboard whose amount bark hasn't recorded yet, and Android's legacy result for an unconfirmed txid. All mean "submitted, not confirmed sent", so it stays one string. es "Pago enviado" (word for word the same as "Payment sent") → "Pago iniciado"; ja "支払いを送信しました" ("sent the payment") → "支払いを受け付けました"; sv "Betalning skickad" → "Betalning inskickad"; ko "결제 요청 제출됨" was already right. The catalog tool's tests guard this (see the older strings below).
- **Boarding submitted, but delivery is unconfirmed. …** (`gt_21bea5494ecbdd9d`, which iOS moved onto): es "Depósito enviado" → "Depósito iniciado", sv "Insättning skickad" → "Insättning inskickad", ja "入金リクエストを送信しましたが" → "入金を受け付けましたが"; ko already said "submitted".
- **Checked and unchanged:** "Payment not sent", "This payment may have been sent. …", "Unable to reserve the wallet. Check payment status before sending again.", "Recurring payment stopped before submission. No payment was sent.", "{amount} is back in your wallet. …", "Refunded · back in your wallet", "The Lightning payment could not be received. …", "The recorded transaction was found. Review its network status.", "Backup pending", "{amount} available in Ark", "Joins the next Ark round, so it stays spendable." (and "they"), "Claimable in {n} blocks · {time}", "Waiting · {n} blocks · {time}", "A payment of {amount} sats for this invoice is already in progress. …", "A reminder comes the day before. …", "Check status", "Refresh network status", "Done", "Exported", "Registered again" and "Start a new payment after checking history". Each hedges or matches the English.
- **Older "submitted" strings (translations only; the English is unchanged).** Every catalog key whose English describes a state that isn't final (submitted, pending, arriving, broadcast, waiting, in progress, claiming, exiting and similar, 135 keys) was checked. These eight said "sent" in es, ja or sv and now use the same words as "Payment submitted"; ko already said "제출" ("submitted") in all of them:
  - `gt_64900440a8c9a5ed` "Submitted": es "Enviado" → "Iniciado", ja "送信済み" → "受付済み".
  - `gt_8b60ea61b803ead4` "Submitted. Refresh to check network status. …": es "Enviado." → "Iniciado.", ja "送信済みです。" → "受け付けました。", sv "Skickad." → "Inskickad.".
  - `gt_b50137cc6d52c59c` "Submitted, awaiting broadcast. …": es "Enviado, pendiente de difusión." → "Iniciado, pendiente de difusión.", ja "送信済みで、" → "受付済みで、", sv "Skickad, väntar på sändning." → "Inskickad, väntar på sändning.".
  - `gt_01c437c9f900b81f` "Boarding submitted. Funds become spendable after the board completes.": es "Depósito enviado." → "Depósito iniciado.", ja "Arkへの入金リクエストを送信しました。" → "Arkへの入金を受け付けました。", sv "Insättning skickad." → "Insättning inskickad.".
  - `gt_aca0fb7b6af91d5e` "Boarding submitted but unconfirmed. …": es "Depósito enviado, pero sin confirmar." → "Depósito iniciado, pero sin confirmar.", ja "Arkへの入金を送信しましたが" → "Arkへの入金を受け付けましたが", sv "Insättningen har skickats men är inte bekräftad." → "Insättningen är inskickad men inte bekräftad.".
  - `gt_aa87e940cf06cc86` "Claim submitted. Refresh to check confirmation.": es "Reclamación enviada." → "Reclamación iniciada.", ja "受け取り申請を送信しました。" → "受け取り申請を受け付けました。", sv "Inlösen skickad." → "Inlösen inskickad.".
  - `gt_dea03b1eaff62e66` "Refresh submitted to delegated round {nativeValue1}.": es "Renovación enviada a la ronda delegada" → "Renovación iniciada en la ronda delegada", ja "に更新を送信しました。" → "への更新を受け付けました。", sv "Förnyelse skickad till" → "Förnyelse inskickad till".
  - `gt_bc71b743861782dc` "The operation may have been submitted. …": es "se haya enviado" → "se haya iniciado", ja "送信済みの可能性があります" → "受け付けられた可能性があります", sv "kan ha skickats" → "kan ha skickats in".
- **Guard:** `apps/tools/translations.test.mjs` takes every English string that says "submitted" (except "not submitted", "Nothing was submitted" and "No payment was submitted"). In every locale, a string with a "sent" twin in the catalog ("Payment submitted" / "Payment sent", "Submitted" / "Sent") must not translate the same as the twin, and each string must use the shared word: es "iniciad-", ja "受け付け" or "受付", ko "제출", sv "inskickad" or "skickats in".
- **Checked and left as is:** the negated strings ("No payment was submitted.", "Nothing was submitted. …", "Boarding was not submitted. …") say "not sent", which claims less. Strings where "submit" is the user's action ("Submitting again may spend additional funds …", "… before submitting again.", "Checking status will never submit another payment.", "Failed to submit feedback. …") read as "send again", which claims no state. The rest of the 135 already claim no more than their English: "in progress", "waiting" and "arriving" translate as such ("en curso", "待機中", "på väg" and so on). In es, "Submitted" ("Iniciado") now reads the same as the exit state "Started"; both are non-final.

## Other findings, not changed

- **Interpolated copy never localizes.** Swift `Text("… \(x) …")` and Kotlin `tr("… $x …")` (or English built in a model) look up a key that doesn't exist, so they always show English, yet the extractor puts them in the catalogs as `{nativeValueN}`. The remaining ones are allowlisted ("not localized" or iOS's may-have-been-sent lines in `SendOutcomeView`); the rest were converted above.
- **Android's recurring screens show raw `e.message`.** Any exception's text can reach the editor, the list or Run due payments now. The storage and version errors were reworded above; the rest are allowlisted as `diagnostic`.
- **Android's legacy screens** (fixed 2026-10-25): the recurring manual review now uses Send's redesigned review, Sending and result (`SendApproval`, Close on every unresolved result as iOS's `onLeave`), and Home's email nudge opens Settings → Email. The pre-redesign `SendConfirmation`, `SendOutcome` and `EmergencyEmailScreen` are deleted; their 20 Android-only strings left the allowlist, and 5 strings they shared with iOS are now listed for iOS.
- **Dead code:** `onchain/VtxoSelectionRow.kt` (Android) and `PendingFeatureView` in `Features/Onboarding/OnboardingFlow.swift` (iOS) have no callers. Their strings are allowlisted as `dead code`.
- **Rounding:** iOS truncates "Expires in {n} hours" (`blocks / 6`); Android rounds. Both use the same strings, so a VTXO near a boundary can read one hour apart.
- **No singular for blocks:** "{n} blocks" isn't a plural on either platform, so both can say "1 blocks". Making it a plural means converting about 15 call sites to `trPlural` / `L10n.plural` and adding one-forms in four languages.
- **Extractor blind spot:** iOS's `WalletLifecycle.Failure.errorDescription` strings are shown (localized through Android's catalog entries) but the extractor doesn't scan that switch, so they look Android-only; they're allowlisted as `shared`.
- **Stale Maestro flow:** `apps/maestro/subflows/send-funded-ark.yml` still expects the pre-redesign Send ("sats available in Ark", `send-confirm`, "Payment sent").
- **Locale catalogs:** rewriting `apps/i18n/{es,ja,ko,sv}/native.json` to add two keys also dropped an exact duplicate key (`gt_d4ea61c79c9b77d6`, same value twice).

## Verification

- **iOS:** `NoahTests` (scheme `Noah-Regtest`, `-only-testing:NoahTests`, `-parallel-testing-enabled NO`) on an iPhone SE (3rd generation) simulator, iOS 27.0: 1,178 tests in 233 suites passed (after the review changes).
- **Android:** `./gradlew testRegtestDebugUnitTest assembleRegtestDebugAndroidTest`: 4,221 JVM tests in 198 suites, 0 failures; the instrumented test APK builds. Of the instrumented suites, only the screenshot galleries below and `HomeToastTest` (the masked boarding toast keeps its schedule) were run.
- **Tool:** `bun test apps/tools/translations.test.mjs` passes (19 tests, including the gate, typed-delete and submitted-vs-sent tests), and `bun apps/tools/translations.mjs --check` is clean with the gate on: 0 untranslated in es, ja, ko and sv.
- **Tests that asserted old text** were updated: `SendOfferTests`, `ProfileNameDraftTests`, `SettingsSafetyTests`, the Home and Onboarding shot fixtures (iOS) and `ExitDeviceTest` (Android). No test tag or accessibility identifier changed.

### Length on the smallest phones

Before/after screenshots from the apps' fixture galleries, on an iPhone SE (3rd generation) and a 360 dp-wide Android phone (Nexus 5 profile, API 35), are in `/private/tmp/noah-dev/copy-shots/{ios,android}-{before,after}/`:

- iOS: Home with a balance error, exit Choose, VTXOs, recurring payments, Send's not-sent and may-have-been-sent results.
- Android: Receive's Ways to pay you sheet, Send's method and on-chain review, and the exit's Choose, waiting, claimed and list pages.

Everything fits. The only new wrap is iOS Home's "Couldn't load your balance. Pull down to try again.", now two lines on the SE. Android's longer "From any bitcoin wallet, about 20 min" and the on-chain review's three-line fee note fit at 360 dp.
