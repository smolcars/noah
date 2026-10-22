import Foundation
import Observation

/// Which of Home's layouts the wallet gets (docs/native-rewrite/design/home.md, "States").
enum HomeLayoutState: Equatable, Sendable {
    /// The balance or the first history read hasn't arrived yet.
    case loading
    /// Zero balance and an empty, loaded history: the get-paid card is the empty state.
    case fresh
    case everyday
    case suspended

    static func of(suspended: Bool, balance: WalletBalance?, historyLoaded: Bool, historyFailed: Bool,
                   transactions: [HistoryTransaction]) -> Self {
        if suspended { return .suspended }
        guard let balance, historyLoaded else { return .loading }
        return balance.totalSats == 0 && transactions.isEmpty && !historyFailed ? .fresh : .everyday
    }
}

/// Refreshable VTXOs inside the maintenance window that automatic renewal hasn't reached yet.
struct HomeRenewal: Equatable, Sendable {
    struct Coin: Equatable, Sendable {
        var id: String
        var sats: UInt64
        var expiryHeight: UInt32
        var spendable: Bool
    }

    let ids: [String]
    let sats: UInt64
    /// Blocks until the soonest of them expires.
    let blocksLeft: UInt32

    /// Expired and locked VTXOs are left out: they can't be refreshed from here.
    static func select(_ expiring: [Coin], tip: UInt32) -> Self? {
        let coins = expiring.filter { $0.spendable && $0.expiryHeight > tip }
        guard let soonest = coins.map(\.expiryHeight).min() else { return nil }
        return Self(ids: coins.map(\.id), sats: coins.reduce(0) { $0 + $1.sats }, blocksLeft: soonest - tip)
    }

    /// Whole hours left at ten minutes a block.
    var hoursLeft: Int { Int(blocksLeft) * 10 / 60 }

    func expiryText(locale: String = LegacyLocale.resolved()) -> String {
        hoursLeft < 1 ? L10n.text("Expires in under an hour", locale: locale)
            : L10n.plural("Expires in {n} hours", count: hoursLeft, locale: locale)
    }
}

/// One nudge card at a time, in the spec's order.
enum HomeNudge: Equatable, Identifiable {
    case notice(String)
    case renew(HomeRenewal)
    case backupFailed(message: String)
    case backUp
    case email
    case update

    var id: String {
        switch self {
        case .notice(let text): "notice:\(text)"
        case .renew: "renew"
        case .backupFailed: "backup-failed"
        case .backUp: "back-up"
        case .email: "email"
        case .update: "update"
        }
    }

    struct Inputs {
        var upgradeNotices: [String] = []
        var renewal: HomeRenewal?
        var backup: HomeBanner?
        var needsRecoveryPhraseBackup = false
        var isEmailVerified: Bool?
        var isEmailPromptDismissed: Bool?
        var updateRequired = false
        /// A suspended wallet shows only its suspended state.
        var suspended = false
    }

    static func current(_ inputs: Inputs) -> Self? {
        if inputs.suspended { return nil }
        if let notice = inputs.upgradeNotices.first { return .notice(notice) }
        if let renewal = inputs.renewal { return .renew(renewal) }
        if let backup = inputs.backup, backup.retry { return .backupFailed(message: backup.message) }
        if inputs.needsRecoveryPhraseBackup { return .backUp }
        if inputs.isEmailVerified != true && inputs.isEmailPromptDismissed != true { return .email }
        if inputs.updateRequired { return .update }
        return nil
    }
}

/// At most one line under the fiat value, in the spec's order.
enum HomeStatusLine: Equatable {
    case error(String)
    case arriving(UInt64)
    case pendingRound(UInt64)
    case rounds(Int)
    case backingUp
    case background(String)

    var opensRounds: Bool { if case .rounds = self { true } else { false } }

    /// `incomingOnchain` comes from `incomingOnchain(_:)`: only money coming in counts as arriving.
    static func current(error: String?, balance: WalletBalance?, incomingOnchain: UInt64, activeRounds: Int,
                        backingUp: Bool, backgroundMessage: String?) -> Self? {
        if let error { return .error(error) }
        // Never sends, offboards, exits or boards: those are your own money leaving or moving.
        // The records' incoming amount can't exceed what the wallet itself counts as pending on-chain.
        let arriving = (balance?.ark.claimableLightningReceive ?? 0) + min(incomingOnchain, balance?.onchain.pending ?? 0)
        if arriving > 0 { return .arriving(arriving) }
        if let balance, balance.ark.pendingInRound > 0 { return .pendingRound(balance.ark.pendingInRound) }
        if activeRounds > 0 { return .rounds(activeRounds) }
        if backingUp { return .backingUp }
        if let backgroundMessage { return .background(backgroundMessage) }
        return nil
    }

    /// Unconfirmed on-chain transactions that add to the wallet, by their net change.
    /// The SDK's on-chain pending balance is BDK's trusted plus untrusted pending in
    /// one number, so it can't tell others' payments from your own unconfirmed change;
    /// the records can: a send's change only shrinks its outgoing net amount. An
    /// unconfirmed transaction that pays this wallet from its own funds with a net
    /// gain would still count.
    static func incomingOnchain(_ transactions: [HistoryTransaction]) -> UInt64 {
        transactions.reduce(0) { sum, transaction in
            guard transaction.source == "onchain-wallet", transaction.hasConfirmation != true,
                  let change = transaction.balanceChangeSat, change > 0 else { return sum }
            return sum + UInt64(change)
        }
    }
}

/// How a history record reads on Home (prototype `rowHtml` / `avatar`). Only
/// data the record already holds: no invented names.
struct HomeActivityRow: Equatable {
    enum Avatar: Equatable {
        /// People and addresses: the lowercase initial.
        case initial(String)
        case lightning, chain, incoming, outgoing
    }

    /// How it moved; localized by the row view.
    enum Kind: Equatable { case noahUser, lightning, onchain, ark }

    enum Status: Equatable {
        case done
        case sending, confirming
        case failed(nothingLeft: Bool)
        case canceled(nothingLeft: Bool)

        var isPending: Bool { self == .sending || self == .confirming }
        var isUnsuccessful: Bool {
            switch self {
            case .failed, .canceled: true
            default: false
            }
        }
    }

    /// Verbatim when it's an address, otherwise a source string to localize.
    let title: String
    let titleIsAddress: Bool
    /// Nil when the title already says it.
    let kind: Kind?
    let avatar: Avatar
    let status: Status
    let sats: Int64
    /// "+", "−", or nothing for internal transfers.
    let sign: String

    init(_ transaction: HistoryTransaction, ownDomain: String) {
        let outgoing = transaction.direction == "outgoing"
        let onchain = transaction.type == "Onchain" || transaction.source == "onchain-wallet"
            || ["onboard", "offboard", "send-onchain", "exit"].contains(transaction.movementKind)
        let lightning = ["Bolt11", "Lnurl"].contains(transaction.type)
        // Only a payment's destination names someone; an incoming record holds our own receiving method.
        let address = outgoing ? (transaction.repeatPayment?.destination ?? transaction.destination)
            .flatMap(RepeatPayment.normalizeLightningAddress) : nil
        let parts = address?.split(separator: "@").map(String.init)
        let noahUser = parts.map { $0[1] == ownDomain.lowercased() } ?? false

        let kindLabel: Kind? = noahUser ? .noahUser : lightning ? .lightning : onchain ? .onchain
            : transaction.type == "Arkoor" ? .ark : nil
        if let address, let parts {
            title = noahUser ? parts[0] : address
            titleIsAddress = true
            kind = kindLabel
            avatar = .initial(String(parts[0].prefix(1)).lowercased())
        } else {
            title = transaction.baseLabel
            titleIsAddress = false
            // Board, Offboard and Ark Exit name the operation, so the subtitle says how it moved.
            kind = ["onboard", "offboard", "exit"].contains(transaction.movementKind) ? kindLabel : nil
            avatar = lightning ? .lightning : onchain ? .chain : outgoing ? .outgoing : .incoming
        }

        let nothingLeft = Self.leftNothing(transaction)
        switch transaction.movementStatus {
        case "failed": status = .failed(nothingLeft: nothingLeft)
        case "canceled": status = .canceled(nothingLeft: nothingLeft)
        case "pending": status = outgoing ? .sending : .confirming
        default:
            status = transaction.source == "onchain-wallet" && transaction.hasConfirmation != true
                ? (outgoing ? .sending : .confirming) : .done
        }
        sats = transaction.amount
        sign = transaction.isInternalTransfer ? "" : outgoing ? "−" : "+"
    }

    /// "Nothing left your wallet" needs explicit zeros for every delta the record can
    /// carry: the wallet's balance change, the Ark fee, any on-chain change and, for
    /// on-chain operations, the on-chain fee. Anything unknown means we can't say.
    static func leftNothing(_ transaction: HistoryTransaction) -> Bool {
        guard transaction.effectiveBalanceSat == 0, transaction.offchainFeeSat == 0 else { return false }
        if let change = transaction.balanceChangeSat, change != 0 { return false }
        let onchain = transaction.type == "Onchain"
            || ["onboard", "offboard", "send-onchain", "exit"].contains(transaction.movementKind)
        return onchain ? transaction.onchainFeeSat == 0 : (transaction.onchainFeeSat ?? 0) == 0
    }

    /// Incoming money, not a transfer between your own balances and not a failure.
    static func isNewEligible(_ transaction: HistoryTransaction) -> Bool {
        transaction.direction == "incoming" && !transaction.isInternalTransfer
            && !["failed", "canceled"].contains(transaction.movementStatus)
    }
}

/// The wide activity list's day sections.
struct HomeActivityDay: Identifiable, Equatable {
    enum Title: Equatable { case today, yesterday, date(Date) }
    let id: String
    let title: Title
    var transactions: [HistoryTransaction]

    /// Records without a real time (unconfirmed or awaiting a block time) stay with
    /// the section above them, or Today when they come first.
    static func group(_ transactions: [HistoryTransaction], now: Date, calendar: Calendar = .current) -> [Self] {
        let today = calendar.startOfDay(for: now)
        let yesterday = calendar.date(byAdding: .day, value: -1, to: today)
        var days: [Self] = []
        for transaction in transactions {
            let timed = transaction.dateLabel == nil ? transaction.timestamp.map { calendar.startOfDay(for: $0) } : nil
            if timed == nil, !days.isEmpty {
                days[days.count - 1].transactions.append(transaction)
                continue
            }
            let day = timed ?? today
            let id = "\(Int(day.timeIntervalSince1970))"
            if days.last?.id == id {
                days[days.count - 1].transactions.append(transaction)
            } else {
                let title: Title = day == today ? .today : day == yesterday ? .yesterday : .date(day)
                days.append(Self(id: id, title: title, transactions: [transaction]))
            }
        }
        return days
    }
}

/// In-memory Home moments: the orange "new" dot and the first payment. Nothing persists.
@MainActor
@Observable
final class HomeActivityTracker {
    struct Celebration: Equatable {
        let id = UUID()
        let sats: UInt64
        let start: Date
    }

    /// The layout on screen; holds Fresh while the first payment plays.
    private(set) var shown = HomeLayoutState.loading
    private(set) var celebration: Celebration?
    /// Incoming records that arrived since Home was last on screen.
    private(set) var newIDs: Set<String> = []
    /// This visit's initial refresh and history load have landed, so a payment from
    /// now on is live. Each visit (and each wallet) starts disarmed: whatever its
    /// first refresh brings in arrived while you were away.
    private(set) var armed = false
    private var baseline: Set<String>?
    private var visible = false
    private var walletKey: String?
    private var state = HomeLayoutState.loading
    private var armedIDs: Set<String> = []
    private var armedSats: UInt64 = 0
    private var visitRevision: Int?

    /// `summaryRevision` is the app's balance refresh count: a visit arms once it has
    /// moved on from where the visit started and history isn't loading. Nil (fixtures)
    /// has nothing to wait for.
    func update(state: HomeLayoutState, transactions: [HistoryTransaction], balanceSats: UInt64,
                visible: Bool, walletKey: String?, summaryRevision: Int? = nil, historyBusy: Bool = false,
                now: Date = Date()) {
        let walletChanged = walletKey != self.walletKey
        if walletChanged {
            baseline = nil
            newIDs = []
            celebration = nil
            shown = state
        }
        self.state = state
        let ids = Set(transactions.map(\.id))
        if baseline == nil && state != .loading { baseline = ids }
        if self.visible && !visible {
            // Leaving Home marks everything seen and ends a running first payment.
            baseline = ids
            newIDs = []
            celebration = nil
        }
        if self.visible != visible || walletChanged { armed = false }
        if visible && (!self.visible || walletChanged) { visitRevision = summaryRevision }
        self.walletKey = walletKey
        self.visible = visible
        if visible && !armed && !historyBusy && state != .loading
            && (summaryRevision == nil || summaryRevision.map { $0 > (visitRevision ?? $0) } == true) {
            // Whatever this visit's first refresh brought in arrived while you were away.
            armed = true
            armedIDs = ids
            armedSats = balanceSats
        }
        if visible, let baseline {
            newIDs = Set(transactions.filter { !baseline.contains($0.id) && HomeActivityRow.isNewEligible($0) }.map(\.id))
        }
        if celebration != nil {
            if state == .suspended || state == .loading { celebration = nil; shown = state }
            return
        }
        if armed && visible && shown == .fresh && state == .everyday,
           let sats = firstPayment(transactions, balanceSats: balanceSats) {
            celebration = Celebration(sats: sats, start: now)
            return
        }
        shown = state
    }

    /// A first payment is an eligible incoming record that is new since arming, or
    /// failing that a real balance increase. A failed history read is neither.
    private func firstPayment(_ transactions: [HistoryTransaction], balanceSats: UInt64) -> UInt64? {
        if let record = transactions.first(where: { !armedIDs.contains($0.id) && HomeActivityRow.isNewEligible($0) }),
           record.amount > 0 {
            return UInt64(record.amount)
        }
        return balanceSats > armedSats ? balanceSats - armedSats : nil
    }

    /// The card has folded away; Recent takes its place.
    func finishCelebration(_ id: UUID) {
        guard celebration?.id == id else { return }
        celebration = nil
        shown = state
    }
}
