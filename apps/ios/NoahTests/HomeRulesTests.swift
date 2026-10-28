import Foundation
@testable import Noah
import Testing

/// Home's layout, nudge, status and row rules (docs/native-rewrite/design/home.md).
@MainActor
struct HomeRulesTests {
    static func balance(ark: UInt64 = 0, onchain: UInt64 = 0, onchainPending: UInt64 = 0, inRound: UInt64 = 0,
                        lightningSend: UInt64 = 0, claimable: UInt64 = 0, arkoorSend: UInt64 = 0, offboard: UInt64 = 0,
                        exit: UInt64 = 0, board: UInt64 = 0) -> WalletBalance {
        WalletBalance(
            ark: .init(spendable: ark, needsRefresh: 0, pendingLightningSend: lightningSend, claimableLightningReceive: claimable,
                       pendingInRound: inRound, pendingExit: exit, pendingBoard: board, pendingArkoorSend: arkoorSend,
                       pendingOffboard: offboard),
            onchain: .init(confirmed: onchain, pending: onchainPending, total: onchain + onchainPending))
    }

    static func row(_ id: String = "movement-1", type: String = "Arkoor", amount: Int64 = 2_100, direction: String = "incoming",
                    status: String? = "successful", kind: String? = nil, destination: String? = nil,
                    date: String = "2026-10-22T09:12:00Z", source: String = "ark", effective: Int64? = nil,
                    offchainFee: Int64? = nil, onchainFee: Int64? = nil, balanceChange: Int64? = nil) -> HistoryTransaction {
        HistoryTransaction(id: id, type: type, amount: amount, date: date, direction: direction, source: source,
                           destination: destination, movementStatus: status, movementKind: kind,
                           effectiveBalanceSat: effective ?? (direction == "incoming" ? amount : -amount),
                           offchainFeeSat: offchainFee, onchainFeeSat: onchainFee, balanceChangeSat: balanceChange)
    }

    static func deposit(_ id: String, _ sats: Int64, confirmed: Bool) -> HistoryTransaction {
        var deposit = row(id, type: "Onchain", amount: abs(sats), direction: sats < 0 ? "outgoing" : "incoming", status: nil,
                          source: "onchain-wallet", balanceChange: sats)
        deposit.effectiveBalanceSat = nil
        deposit.hasConfirmation = confirmed
        return deposit
    }

    private func storage() throws -> (SettingsStorage, URL) {
        let root = FileManager.default.temporaryDirectory.appending(path: "home-rules-\(UUID())")
        return (try SettingsStorage(rootPath: root.appending(path: "mmkv").path), root)
    }

    // MARK: Existing and migrated wallets

    @Test func reactNativeMigratedWalletWithHistoryOpensEverydayWithTheBackupNudge() throws {
        let (settings, root) = try storage()
        defer { try? FileManager.default.removeItem(at: root) }
        settings.set(RecoveryPhraseBackupTests.rnWallet, forKey: PersistedWalletState.key)
        let wallet = try #require(settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key))
        #expect(!AppModel.resumesOnboarding(wallet))
        let history = [Self.row(), Self.row("movement-2", amount: 500, direction: "outgoing")]
        let state = HomeLayoutState.of(suspended: wallet.isWalletSuspended == true, balance: Self.balance(ark: 1_600),
                                       historyLoaded: true, historyFailed: false, transactions: history)
        #expect(state == .everyday)
        let nudge = HomeNudge.current(.init(needsRecoveryPhraseBackup: PersistedRecoveryPhraseBackup.read(from: settings).needsBackup,
                                            isEmailVerified: nil, isEmailPromptDismissed: nil))
        #expect(nudge == .backUp)
        #expect(settings.string(forKey: PersistedWalletState.key) == RecoveryPhraseBackupTests.rnWallet)
    }

    @Test func restoredWalletOpensWithoutTheBackupNudge() throws {
        let (settings, root) = try storage()
        defer { try? FileManager.default.removeItem(at: root) }
        try AppModel.beginRestore(settings, typedPhrase: true)
        #expect(settings.completeRestore())
        let backup = PersistedRecoveryPhraseBackup.read(from: settings)
        let nudge = HomeNudge.current(.init(needsRecoveryPhraseBackup: backup.needsBackup, isEmailVerified: true))
        #expect(nudge == nil)
        #expect(HomeNudge.current(.init(needsRecoveryPhraseBackup: backup.needsBackup)) == .email)
    }

    @Test func newEmptyWalletOpensFresh() {
        #expect(HomeLayoutState.of(suspended: false, balance: Self.balance(), historyLoaded: true, historyFailed: false,
                                   transactions: []) == .fresh)
        // Not until both the balance and the first history read have arrived.
        #expect(HomeLayoutState.of(suspended: false, balance: nil, historyLoaded: true, historyFailed: false,
                                   transactions: []) == .loading)
        #expect(HomeLayoutState.of(suspended: false, balance: Self.balance(), historyLoaded: false, historyFailed: false,
                                   transactions: []) == .loading)
        // A failed read isn't an empty history.
        #expect(HomeLayoutState.of(suspended: false, balance: Self.balance(), historyLoaded: true, historyFailed: true,
                                   transactions: []) == .everyday)
        // Any balance (pending deposits included) or any record is Everyday.
        #expect(HomeLayoutState.of(suspended: false, balance: Self.balance(onchainPending: 1), historyLoaded: true,
                                   historyFailed: false, transactions: []) == .everyday)
        #expect(HomeLayoutState.of(suspended: false, balance: Self.balance(), historyLoaded: true, historyFailed: false,
                                   transactions: [Self.row(direction: "outgoing")]) == .everyday)
    }

    @Test func suspendedWalletShowsOnlyTheSuspendedState() throws {
        let (settings, root) = try storage()
        defer { try? FileManager.default.removeItem(at: root) }
        settings.set(RecoveryPhraseBackupTests.rnWallet.replacingOccurrences(of: #""isWalletSuspended":false"#,
                                                                             with: #""isWalletSuspended":true"#),
                     forKey: PersistedWalletState.key)
        let app = AppModel(settings: settings)
        #expect(app.isWalletSuspended)
        #expect(HomeLayoutState.of(suspended: app.isWalletSuspended, balance: Self.balance(ark: 5), historyLoaded: true,
                                   historyFailed: false, transactions: [Self.row()]) == .suspended)
        #expect(HomeNudge.current(.init(upgradeNotices: ["notice"], needsRecoveryPhraseBackup: true, updateRequired: true,
                                        suspended: true)) == nil)
    }

    // MARK: Nudges and status

    @Test func nudgesShowOneAtATimeInOrder() {
        let renewal = HomeRenewal(ids: ["a"], sats: 20_000, blocksLeft: 108)
        var failed = PersistedBackupState()
        failed.markFailed("Offline")
        var inputs = HomeNudge.Inputs(upgradeNotices: ["Settings were reset"], renewal: renewal,
                                      backup: HomeBanner.backup(failed, now: 0, locale: Locale(identifier: "en")),
                                      needsRecoveryPhraseBackup: true, updateRequired: true)
        var seen: [String] = []
        while let nudge = HomeNudge.current(inputs) {
            seen.append(nudge.id)
            switch nudge {
            case .notice: inputs.upgradeNotices.removeFirst()
            case .renew: inputs.renewal = nil
            case .backupFailed: inputs.backup = nil
            case .backUp: inputs.needsRecoveryPhraseBackup = false
            case .email: inputs.isEmailPromptDismissed = true
            case .update: inputs.updateRequired = false
            }
        }
        #expect(seen == ["notice:Settings were reset", "renew", "backup-failed", "back-up", "email", "update"])
        // A verified email never asks; an in-progress backup is a status line, not a nudge.
        var progress = PersistedBackupState()
        progress.markInProgress(now: 1)
        #expect(HomeNudge.current(.init(backup: HomeBanner.backup(progress, now: 1, locale: .current), isEmailVerified: true)) == nil)
    }

    @Test func renewalCountsOnlyRefreshableUnexpiredCoins() {
        let coins: [HomeRenewal.Coin] = [
            .init(id: "soon", sats: 15_000, expiryHeight: 1_108, spendable: true),
            .init(id: "later", sats: 5_000, expiryHeight: 1_140, spendable: true),
            .init(id: "locked", sats: 7_000, expiryHeight: 1_010, spendable: false),
            .init(id: "expired", sats: 9_000, expiryHeight: 1_000, spendable: true),
        ]
        let renewal = HomeRenewal.select(coins, tip: 1_000)
        #expect(renewal == HomeRenewal(ids: ["soon", "later"], sats: 20_000, blocksLeft: 108))
        #expect(renewal?.hoursLeft == 18)
        #expect(renewal?.expiryText(locale: "en") == "Expires in 18 hours")
        #expect(HomeRenewal(ids: ["a"], sats: 1, blocksLeft: 6).expiryText(locale: "en") == "Expires in 1 hour")
        #expect(HomeRenewal(ids: ["a"], sats: 1, blocksLeft: 5).expiryText(locale: "en") == "Expires in under an hour")
        #expect(HomeRenewal.select(coins.filter { !$0.spendable || $0.expiryHeight <= 1_000 }, tip: 1_000) == nil)
        #expect(HomeRenewal.select([], tip: 1_000) == nil)
    }

    @Test func reviewAndConfirmationUseTheNudgesRule() {
        let tip: UInt32 = 1_000
        let shown = HomeRenewal.select([.init(id: "a", sats: 1, expiryHeight: 1_050, spendable: true),
                                        .init(id: "b", sats: 1, expiryHeight: 1_060, spendable: true)], tip: tip)
        // By review time "b" has locked and "a" is still in the window.
        let later = HomeRenewal.select([.init(id: "a", sats: 1, expiryHeight: 1_050, spendable: true),
                                        .init(id: "b", sats: 1, expiryHeight: 1_060, spendable: false)], tip: tip)
        #expect(HomeRenewal.eligible(shown?.ids ?? [], in: later) == ["a"])
        #expect(HomeRenewal.allEligible(["a"], in: later))
        #expect(!HomeRenewal.allEligible(["a", "b"], in: later))
        // "a" expires before confirmation: nothing is submitted.
        let expired = HomeRenewal.select([.init(id: "a", sats: 1, expiryHeight: 1_050, spendable: true)], tip: 1_050)
        #expect(expired == nil && !HomeRenewal.allEligible(["a"], in: expired))
        // A failed wallet read counts as not eligible.
        #expect(HomeRenewal.eligible(["a"], in: nil).isEmpty && !HomeRenewal.allEligible(["a"], in: nil))
        #expect(!HomeRenewal.allEligible([], in: later))
    }

    @Test func statusLineShowsTheFirstThatApplies() {
        func line(_ balance: WalletBalance?, incoming: UInt64 = 0, error: String? = nil, rounds: Int = 0) -> HomeStatusLine? {
            HomeStatusLine.current(error: error, balance: balance, incomingOnchain: incoming, activeRounds: rounds)
        }
        let deposit = Self.balance(ark: 10, onchainPending: 100_000)
        #expect(line(deposit, incoming: 100_000, error: "Couldn't open your wallet.", rounds: 1) == .error("Couldn't open your wallet."))
        #expect(line(deposit, incoming: 100_000, rounds: 1) == .arriving(100_000))
        // Funds in a round get their own line, after anything that is arriving.
        #expect(line(Self.balance(inRound: 5, claimable: 7), rounds: 1) == .arriving(7))
        #expect(line(Self.balance(inRound: 5), rounds: 1) == .pendingRound(5))
        #expect(line(Self.balance(ark: 1), rounds: 2) == .rounds(2))
        // Backups and background tasks are the top bar's glyph, never a line.
        #expect(line(Self.balance(ark: 1)) == nil)
        #expect(line(nil) == nil)
    }

    @Test func onlyIncomingFundsAreArriving() {
        func line(_ balance: WalletBalance, _ transactions: [HistoryTransaction] = []) -> HomeStatusLine? {
            HomeStatusLine.current(error: nil, balance: balance, incomingOnchain: HomeStatusLine.incomingOnchain(transactions),
                                   activeRounds: 0)
        }
        // Your own money leaving or moving is never "arriving".
        #expect(line(Self.balance(ark: 20_000, lightningSend: 5_000)) == nil)
        #expect(line(Self.balance(ark: 20_000, arkoorSend: 1, offboard: 2, exit: 3, board: 4)) == nil)
        // An unconfirmed deposit is; so is a Lightning payment waiting to be claimed.
        #expect(line(Self.balance(ark: 20_000, onchainPending: 100_000), [Self.deposit("in", 100_000, confirmed: false)])
            == .arriving(100_000))
        #expect(line(Self.balance(ark: 20_000, claimable: 2_100)) == .arriving(2_100))
        // Unconfirmed change from your own send, and confirmed deposits, are not.
        #expect(line(Self.balance(ark: 20_000, onchainPending: 40_000), [Self.deposit("send", -10_000, confirmed: false),
                                                                         Self.deposit("old", 5_000, confirmed: true)]) == nil)
        // The records never claim more than the wallet's own on-chain pending total.
        #expect(line(Self.balance(ark: 20_000, onchainPending: 60_000), [Self.deposit("in", 100_000, confirmed: false)])
            == .arriving(60_000))
        #expect(line(Self.balance(ark: 20_000, claimable: 2_100), [Self.deposit("in", 100_000, confirmed: false)])
            == .arriving(2_100))
    }

    // MARK: Rows

    @Test func rowsNameOnlyWhatTheRecordHolds() {
        let own = "noahwallet.io"
        var noah = Self.row(type: "Lnurl", direction: "outgoing", destination: "Maya@NoahWallet.io")
        var row = HomeActivityRow(noah, ownDomain: own)
        #expect(row.title == "maya" && row.titleIsVerbatim && row.kind == .noahUser && row.avatar == .initial("m"))
        #expect(row.sign == "−" && row.status == .done)

        noah.destination = "lucas@ln.tips"
        row = HomeActivityRow(noah, ownDomain: own)
        #expect(row.title == "lucas@ln.tips" && row.kind == .lightning && row.avatar == .initial("l"))

        // An invoice or Ark address names nobody; incoming records hold our own receiving method.
        // Without a stored description an invoice row reads "Sent" (activity.md, Data 4).
        row = HomeActivityRow(Self.row(type: "Bolt11", direction: "outgoing", destination: "lnbcrt1invoice"), ownDomain: own)
        #expect(row.title == "Sent" && !row.titleIsVerbatim && row.kind == .lightning && row.avatar == .lightning)
        row = HomeActivityRow(Self.row(type: "Lnurl", destination: "maya@noahwallet.io"), ownDomain: own)
        #expect(row.title == "Lightning" && row.avatar == .lightning && row.sign == "+")
        row = HomeActivityRow(Self.row(), ownDomain: own)
        #expect(row.title == "Ark" && row.avatar == .incoming)
        row = HomeActivityRow(Self.row(direction: "outgoing", destination: "tark1address"), ownDomain: own)
        #expect(row.avatar == .outgoing && row.sign == "−")

        // On-chain movements get the link; a board is a transfer between your own balances.
        row = HomeActivityRow(Self.row(type: "Onchain", kind: "onboard"), ownDomain: own)
        #expect(row.title == "Board" && row.kind == .onchain && row.avatar == .chain && row.sign.isEmpty)
        var deposit = Self.row("onchain-wallet-tx", type: "Onchain", status: nil, source: "onchain-wallet")
        deposit.hasConfirmation = false
        row = HomeActivityRow(deposit, ownDomain: own)
        #expect(row.title == "Onchain" && row.kind == nil && row.avatar == .chain && row.status == .confirming)
        deposit.hasConfirmation = true
        #expect(HomeActivityRow(deposit, ownDomain: own).status == .done)
    }

    @Test func pendingFailedAndCanceledRows() {
        let own = "noahwallet.io"
        var row = HomeActivityRow(Self.row(type: "Lnurl", direction: "outgoing", status: "pending",
                                           destination: "bob@getalby.com"), ownDomain: own)
        #expect(row.status == .sending && row.status.isPending)
        row = HomeActivityRow(Self.row(type: "Onchain", status: "pending", kind: "onboard"), ownDomain: own)
        #expect(row.status == .confirming)
        row = HomeActivityRow(Self.row(type: "Onchain", direction: "outgoing", status: "failed", kind: "offboard", effective: 0,
                                       offchainFee: 0, onchainFee: 0, balanceChange: 0), ownDomain: own)
        #expect(row.status == .failed(nothingLeft: true) && row.status.isUnsuccessful && row.title == "Offboard")
        row = HomeActivityRow(Self.row(type: "Bolt11", direction: "outgoing", status: "failed", effective: -4_200), ownDomain: own)
        #expect(row.status == .failed(nothingLeft: false))
        // Canceled keeps the title: the subtitle carries the status.
        row = HomeActivityRow(Self.row(type: "Bolt11", direction: "outgoing", status: "canceled", effective: 0, offchainFee: 0),
                              ownDomain: own)
        #expect(row.status == .canceled(nothingLeft: true) && row.title == "Sent")
    }

    @Test func nothingLeftNeedsAZeroForEveryDelta() {
        func nothingLeft(_ row: HistoryTransaction) -> Bool { HomeActivityRow.leftNothing(row) }
        let failed = Self.row(type: "Bolt11", direction: "outgoing", status: "failed", effective: 0, offchainFee: 0)
        #expect(nothingLeft(failed))
        // One zero isn't proof: an on-chain change, a fee or an unknown fee leaves it unsaid.
        var row = failed
        row.balanceChangeSat = -4_200
        #expect(!nothingLeft(row))
        row = failed
        row.offchainFeeSat = 12
        #expect(!nothingLeft(row))
        row = failed
        row.offchainFeeSat = nil
        #expect(!nothingLeft(row))
        // A failed movement that produced and exited no coins charged nothing, whatever fee it planned.
        row = failed
        row.offchainFeeSat = 12
        row.outputVtxos = []
        row.exitedVtxos = []
        #expect(nothingLeft(row))
        row.outputVtxos = ["change:0"]
        #expect(!nothingLeft(row))
        row = failed
        row.effectiveBalanceSat = nil
        #expect(!nothingLeft(row))
        #expect(HomeActivityRow(Self.row(type: "Bolt11", direction: "outgoing", status: "failed", effective: 0, offchainFee: 0,
                                         balanceChange: -4_200), ownDomain: "x").status == .failed(nothingLeft: false))
        // On-chain operations also need their on-chain change and fee known to be zero.
        let offboard = Self.row(type: "Onchain", direction: "outgoing", status: "failed", kind: "offboard", effective: 0,
                                offchainFee: 0)
        #expect(!nothingLeft(offboard))
        row = offboard
        row.onchainFeeSat = 0
        #expect(!nothingLeft(row), "the on-chain change is unknown")
        row.balanceChangeSat = 0
        #expect(nothingLeft(row))
        row.onchainFeeSat = 300
        #expect(!nothingLeft(row))
    }

    @Test func onlyMoneyThatCameInIsEligible() {
        #expect(HomeActivityRow.isNewEligible(Self.row()))
        #expect(!HomeActivityRow.isNewEligible(Self.row(status: "canceled")))
        #expect(!HomeActivityRow.isNewEligible(Self.row(status: "failed")))
        #expect(!HomeActivityRow.isNewEligible(Self.row(direction: "outgoing")))
        #expect(!HomeActivityRow.isNewEligible(Self.row(type: "Onchain", kind: "onboard")))
        #expect(!HomeActivityRow.isNewEligible(Self.row(amount: 0)))
    }

    @Test func activityGroupsByDay() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try #require(TimeZone(identifier: "UTC"))
        let now = try #require(HistoryRules.parseDate("2026-10-22T12:00:00Z"))
        var unconfirmed = Self.row("chain", type: "Onchain", status: nil, date: "1970-01-01T00:00:00Z", source: "onchain-wallet")
        unconfirmed.dateLabel = "Unconfirmed"
        let rows = [unconfirmed, Self.row("a", date: "2026-10-22T09:12:00Z"), Self.row("b", date: "2026-10-21T18:05:00Z"),
                    Self.row("c", date: "2026-10-21T08:40:00Z"), Self.row("d", date: "2026-10-12T10:00:00Z")]
        let days = HomeActivityDay.group(rows, now: now, calendar: calendar)
        #expect(days.map(\.title) == [.today, .yesterday, .date(try #require(HistoryRules.parseDate("2026-10-12T00:00:00Z")))])
        #expect(days.map { $0.transactions.map(\.id) } == [["chain", "a"], ["b", "c"], ["d"]])
    }

    // MARK: New dot and first payment

    @Test func newDotMarksArrivalsSinceHomeWasLastShown() {
        let tracker = HomeActivityTracker()
        let first = [Self.row("old")]
        tracker.update(state: .everyday, transactions: first, balanceSats: 2_100, visible: true, walletKey: "w")
        #expect(tracker.newIDs.isEmpty) // History at launch is never "new".
        let arrived = [Self.row("in"), Self.row("out", direction: "outgoing"), Self.row("board", type: "Onchain", kind: "onboard")] + first
        tracker.update(state: .everyday, transactions: arrived, balanceSats: 5_000, visible: true, walletKey: "w")
        #expect(tracker.newIDs == ["in"])
        // Leaving Home clears the dots; what arrives while away is new on return.
        tracker.update(state: .everyday, transactions: arrived, balanceSats: 5_000, visible: false, walletKey: "w")
        #expect(tracker.newIDs.isEmpty)
        let later = [Self.row("away")] + arrived
        tracker.update(state: .everyday, transactions: later, balanceSats: 7_000, visible: false, walletKey: "w")
        tracker.update(state: .everyday, transactions: later, balanceSats: 7_000, visible: true, walletKey: "w")
        #expect(tracker.newIDs == ["away"])
        // Another wallet starts over.
        tracker.update(state: .everyday, transactions: later, balanceSats: 7_000, visible: true, walletKey: "other")
        #expect(tracker.newIDs.isEmpty)
    }

    @Test func firstPaymentPlaysOnlyWhileHomeIsOnScreen() throws {
        let tracker = HomeActivityTracker()
        tracker.update(state: .fresh, transactions: [], balanceSats: 0, visible: true, walletKey: "w")
        #expect(tracker.shown == .fresh)
        // The balance can land before the record.
        tracker.update(state: .everyday, transactions: [], balanceSats: 21_000, visible: true, walletKey: "w")
        let celebration = try #require(tracker.celebration)
        #expect(celebration.sats == 21_000 && tracker.shown == .fresh)
        tracker.update(state: .everyday, transactions: [Self.row("first", amount: 21_000)], balanceSats: 21_000,
                       visible: true, walletKey: "w")
        #expect(tracker.celebration == celebration && tracker.newIDs == ["first"])
        tracker.finishCelebration(UUID())
        #expect(tracker.celebration != nil)
        tracker.finishCelebration(celebration.id)
        #expect(tracker.celebration == nil && tracker.shown == .everyday && tracker.newIDs == ["first"])

        let away = HomeActivityTracker()
        away.update(state: .fresh, transactions: [], balanceSats: 0, visible: true, walletKey: "w")
        away.update(state: .fresh, transactions: [], balanceSats: 0, visible: false, walletKey: "w")
        away.update(state: .everyday, transactions: [Self.row("first")], balanceSats: 2_100, visible: false, walletKey: "w")
        away.update(state: .everyday, transactions: [Self.row("first")], balanceSats: 2_100, visible: true, walletKey: "w")
        #expect(away.celebration == nil && away.shown == .everyday && away.newIDs == ["first"])
    }

    @Test func leavingHomeEndsARunningFirstPayment() {
        let tracker = HomeActivityTracker()
        tracker.update(state: .fresh, transactions: [], balanceSats: 0, visible: true, walletKey: "w")
        tracker.update(state: .everyday, transactions: [Self.row("first")], balanceSats: 2_100, visible: true, walletKey: "w")
        #expect(tracker.celebration != nil)
        tracker.update(state: .everyday, transactions: [Self.row("first")], balanceSats: 2_100, visible: false, walletKey: "w")
        #expect(tracker.celebration == nil && tracker.shown == .everyday)
        tracker.update(state: .everyday, transactions: [Self.row("first")], balanceSats: 2_100, visible: true, walletKey: "w")
        #expect(tracker.celebration == nil && tracker.newIDs.isEmpty)
    }
    @Test func catchUpPaymentsDoNotPlayAsLive() {
        // Back on a Fresh Home: the payment that landed while away arrives with this visit's refresh.
        let tracker = HomeActivityTracker()
        tracker.update(state: .fresh, transactions: [], balanceSats: 0, visible: true, walletKey: "w", summaryRevision: 4)
        #expect(!tracker.armed)
        tracker.update(state: .everyday, transactions: [Self.row("away")], balanceSats: 2_100, visible: true, walletKey: "w",
                       summaryRevision: 4, historyBusy: true)
        tracker.update(state: .everyday, transactions: [Self.row("away")], balanceSats: 2_100, visible: true, walletKey: "w",
                       summaryRevision: 5)
        #expect(tracker.armed && tracker.celebration == nil && tracker.shown == .everyday)
        // History still loading holds the arming, even after the refresh.
        let loading = HomeActivityTracker()
        loading.update(state: .fresh, transactions: [], balanceSats: 0, visible: true, walletKey: "w", summaryRevision: 1)
        loading.update(state: .fresh, transactions: [], balanceSats: 0, visible: true, walletKey: "w", summaryRevision: 2,
                       historyBusy: true)
        #expect(!loading.armed)
        loading.update(state: .everyday, transactions: [Self.row("away")], balanceSats: 2_100, visible: true, walletKey: "w",
                       summaryRevision: 2)
        #expect(loading.armed && loading.celebration == nil)
    }

    @Test func backgroundThenForegroundStillPlaysTheFirstPayment() throws {
        let tracker = HomeActivityTracker()
        tracker.update(state: .fresh, transactions: [], balanceSats: 0, visible: true, walletKey: "w", summaryRevision: 1)
        tracker.update(state: .fresh, transactions: [], balanceSats: 0, visible: true, walletKey: "w", summaryRevision: 2)
        #expect(tracker.armed)
        // Backgrounding disarms; the foreground refresh (or a retried one) arms the new visit.
        tracker.update(state: .fresh, transactions: [], balanceSats: 0, visible: false, walletKey: "w", summaryRevision: 2)
        tracker.update(state: .fresh, transactions: [], balanceSats: 0, visible: true, walletKey: "w", summaryRevision: 2)
        #expect(!tracker.armed)
        tracker.update(state: .fresh, transactions: [], balanceSats: 0, visible: true, walletKey: "w", summaryRevision: 3)
        #expect(tracker.armed)
        tracker.update(state: .everyday, transactions: [Self.row("live", amount: 5_000)], balanceSats: 0, visible: true,
                       walletKey: "w", summaryRevision: 3)
        #expect(try #require(tracker.celebration).sats == 5_000)
    }

    /// Home on a Fresh wallet, armed, then backgrounded and brought back while the
    /// foreground catch-up is slower than the 3 s fallback refresh.
    @MainActor final class SlowReturn {
        let tracker = HomeActivityTracker()
        var catchUp = AppModel.CatchUp()
        var summaryRevision = 3
        var reloaded = 0
        var rows: [HistoryTransaction] = []
        var sats: UInt64 = 0

        init() {
            show(true)
            summaryRevision += 1 // This visit's first balance read.
            show(true)
            #expect(tracker.armed)
            show(false)
            catchUp.started += 1 // Leaving the foreground.
            show(true)
        }

        func show(_ visible: Bool, historyBusy: Bool = false) {
            tracker.update(state: rows.isEmpty && sats == 0 ? .fresh : .everyday, transactions: rows, balanceSats: sats,
                           visible: visible, walletKey: "w", summaryRevision: summaryRevision, historyBusy: historyBusy,
                           caughtUp: catchUp.isCaughtUp(summaryRevision: summaryRevision, historyReloadedFor: reloaded))
        }

        /// The fallback refresh at 3 s reads the cached balance before the sync has run.
        func fallbackRefresh() { summaryRevision += 1; show(true) }

        /// The catch-up's balance read after its sync (failing leaves the revision alone),
        /// then Home's history reload for it.
        func finishCatchUp(readSucceeds: Bool = true) {
            let synced = summaryRevision
            if readSucceeds { summaryRevision += 1 }
            catchUp.finish(catchUp.started, summaryRevision: synced)
            show(true)
            #expect(!tracker.armed) // Not before the history reload.
            reloaded = catchUp.finished
            show(true)
        }
    }

    @Test func aSlowCatchUpDoesNotPlayWhatItImportsAsLive() {
        let home = SlowReturn()
        home.fallbackRefresh()
        #expect(!home.tracker.armed)
        // The sync imports the payment that landed while away.
        home.rows = [Self.row("away", amount: 2_100)]
        home.show(true, historyBusy: true)
        home.sats = 2_100
        home.show(true)
        #expect(home.tracker.celebration == nil && home.tracker.shown == .everyday)
        home.finishCatchUp()
        #expect(home.tracker.armed && home.tracker.celebration == nil)
    }

    @Test func aPaymentAfterASlowCatchUpStillPlays() throws {
        let home = SlowReturn()
        home.fallbackRefresh()
        #expect(!home.tracker.armed)
        home.finishCatchUp()
        #expect(home.tracker.armed && home.tracker.shown == .fresh)
        home.rows = [Self.row("live", amount: 5_000)]
        home.sats = 5_000
        home.show(true)
        #expect(try #require(home.tracker.celebration).sats == 5_000)
    }

    @Test func aCatchUpNeedsItsOwnBalanceReadAndTheLatestReturn() {
        // The catch-up's balance read failed: a retried read after it is still needed.
        let home = SlowReturn()
        home.finishCatchUp(readSucceeds: false)
        #expect(!home.tracker.armed)
        home.fallbackRefresh()
        #expect(home.tracker.armed)
        // An older return's catch-up finishing late leaves the newer one pending.
        var catchUp = AppModel.CatchUp()
        catchUp.started = 2
        catchUp.finish(1, summaryRevision: 7)
        #expect(catchUp.pending && !catchUp.isCaughtUp(summaryRevision: 9, historyReloadedFor: 1))
        catchUp.finish(2, summaryRevision: 8)
        catchUp.finish(1, summaryRevision: 3)
        #expect(!catchUp.pending && catchUp.summaryRevision == 8)
        #expect(catchUp.isCaughtUp(summaryRevision: 9, historyReloadedFor: 2))
        #expect(!catchUp.isCaughtUp(summaryRevision: 8, historyReloadedFor: 2))
    }

    @Test func aFailedHistoryReadIsNotAPayment() {
        let tracker = HomeActivityTracker()
        tracker.update(state: .fresh, transactions: [], balanceSats: 0, visible: true, walletKey: "w")
        #expect(tracker.armed)
        // Fresh turns Everyday on a history error, with no record and no balance.
        tracker.update(state: .everyday, transactions: [], balanceSats: 0, visible: true, walletKey: "w")
        #expect(tracker.celebration == nil && tracker.shown == .everyday)
        // Outgoing or internal records aren't a first payment either.
        let other = HomeActivityTracker()
        other.update(state: .fresh, transactions: [], balanceSats: 0, visible: true, walletKey: "w")
        other.update(state: .everyday, transactions: [Self.row("board", type: "Onchain", kind: "onboard")], balanceSats: 0,
                     visible: true, walletKey: "w")
        #expect(other.celebration == nil)
    }
}
