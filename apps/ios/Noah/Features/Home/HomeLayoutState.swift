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

    /// The one eligibility rule for the nudge, the review and its confirmation: reported
    /// by `getExpiringVtxos`, spendable (not locked) and not yet expired.
    static func isEligible(_ coin: Coin, tip: UInt32) -> Bool { coin.spendable && coin.expiryHeight > tip }

    /// Expired and locked VTXOs are left out: they can't be refreshed from here.
    static func select(_ expiring: [Coin], tip: UInt32) -> Self? {
        let coins = expiring.filter { isEligible($0, tip: tip) }
        guard let soonest = coins.map(\.expiryHeight).min() else { return nil }
        return Self(ids: coins.map(\.id), sats: coins.reduce(0) { $0 + $1.sats }, blocksLeft: soonest - tip)
    }

    /// The requested coins that are still eligible in a fresh read; a failed read (nil) has none.
    static func eligible(_ requested: [String], in current: Self?) -> [String] {
        requested.filter { current?.ids.contains($0) == true }
    }

    /// Confirmation goes ahead only if every reviewed coin is still eligible.
    static func allEligible(_ reviewed: [String], in current: Self?) -> Bool {
        !reviewed.isEmpty && eligible(reviewed, in: current).count == reviewed.count
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
        // swiftlint:disable discouraged_optional_boolean - mirrors the server state: unset is not false / nil until the first snapshot
        var isEmailVerified: Bool?
        var isEmailPromptDismissed: Bool?
        // swiftlint:enable discouraged_optional_boolean
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

/// At most one line under the fiat value, in the spec's order: money and rounds only. Background
/// tasks and backups show as the top bar's activity glyph instead.
enum HomeStatusLine: Equatable {
    case error(String)
    case arriving(UInt64)
    case pendingRound(UInt64)
    case rounds(Int)

    enum Tap { case balance, rounds }

    /// What a tap opens: only a place that explains the line. Funds in a round open the round (its funding
    /// transaction) while one is active; an error already says what to do, so it's plain text.
    func tap(roundsActive: Bool) -> Tap? {
        switch self {
        case .arriving: .balance
        case .pendingRound: roundsActive ? .rounds : .balance
        case .rounds: .rounds
        case .error: nil
        }
    }

    /// `incomingOnchain` comes from `incomingOnchain(_:)`: only money coming in counts as arriving.
    static func current(error: String?, balance: WalletBalance?, incomingOnchain: UInt64, activeRounds: Int) -> Self? {
        if let error { return .error(error) }
        let arriving = arriving(balance, incomingOnchain: incomingOnchain)
        if arriving > 0 { return .arriving(arriving) }
        if let balance, balance.ark.pendingInRound > 0 { return .pendingRound(balance.ark.pendingInRound) }
        if activeRounds > 0 { return .rounds(activeRounds) }
        return nil
    }

    /// Money coming in: claimable Lightning receives and incoming unconfirmed on-chain (the widgets show it too).
    /// Never sends, offboards, exits or boards: those are your own money leaving or moving.
    /// The records' incoming amount can't exceed what the wallet itself counts as pending on-chain.
    static func arriving(_ balance: WalletBalance?, incomingOnchain: UInt64) -> UInt64 {
        (balance?.ark.claimableLightningReceive ?? 0) + min(incomingOnchain, balance?.onchain.pending ?? 0)
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

/// How a history record reads on Home and Activity (prototype `rowHtml` / `avatar`).
/// Only data the record already holds: no invented names.
struct HomeActivityRow: Equatable {
    enum Avatar: Equatable {
        /// People and addresses: the lowercase initial.
        case initial(String)
        case lightning, chain, recurring, renewal, incoming, outgoing
        /// An exit's fee: the emergency exit's glyph.
        case exit
    }

    /// How it moved; localized by the row view.
    enum Kind: Equatable { case noahUser, lightning, onchain, ark, recurring }

    enum Status: Equatable {
        case done
        case sending, confirming
        /// An Ark-funded on-chain send waiting for its round, and an exit in progress.
        case inRound, exiting
        case failed(nothingLeft: Bool)
        /// A failed Lightning send whose coins came back.
        case refunded
        case canceled(nothingLeft: Bool)

        var isPending: Bool { [.sending, .confirming, .inRound, .exiting].contains(self) }
        var isUnsuccessful: Bool {
            switch self {
            case .failed, .refunded, .canceled: true
            default: false
            }
        }
        /// The red badge: failed and refunded, not canceled.
        var isFailure: Bool {
            switch self {
            case .failed, .refunded: true
            default: false
            }
        }
    }

    /// Shown as is (an address, a schedule's name or a stored description), otherwise a source string to localize.
    let title: String
    let titleIsVerbatim: Bool
    /// Nil when the title already says it.
    let kind: Kind?
    let avatar: Avatar
    let status: Status
    /// Nil when the record can't prove it (`HistoryTransaction.shownAmount`).
    let sats: Int64?
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
        if let recurring = transaction.recurring {
            title = recurring.name
            titleIsVerbatim = true
            kind = .recurring
            avatar = .recurring
        } else if let address, let parts {
            title = noahUser ? parts[0] : address
            titleIsVerbatim = true
            kind = kindLabel
            avatar = .initial(String(parts[0].prefix(1)).lowercased())
        } else if transaction.isRenewal {
            title = transaction.baseLabel
            titleIsVerbatim = false
            kind = .ark
            avatar = .renewal
        } else if transaction.type == "Bolt11" {
            // Invoices and offers carry their own description (activity.md, Data 4).
            title = transaction.paymentDescription ?? (outgoing ? "Sent" : "Received")
            titleIsVerbatim = transaction.paymentDescription != nil
            kind = .lightning
            avatar = .lightning
        } else {
            title = transaction.baseLabel
            titleIsVerbatim = false
            // Board, Offboard, Ark Exit and Exit fee name the operation, so the subtitle says how it moved.
            kind = ["onboard", "offboard", "exit"].contains(transaction.movementKind) || transaction.exitFeeFor != nil ? kindLabel : nil
            avatar = transaction.exitFeeFor != nil ? .exit : lightning ? .lightning : onchain ? .chain : outgoing ? .outgoing : .incoming
        }

        let nothingLeft = Self.leftNothing(transaction)
        switch transaction.movementStatus {
        case "failed": status = transaction.isRefunded ? .refunded : .failed(nothingLeft: nothingLeft)
        case "canceled": status = .canceled(nothingLeft: nothingLeft)
        case "pending":
            if transaction.movementKind == "exit" { status = .exiting }
            else if ["offboard", "send-onchain"].contains(transaction.movementKind) {
                status = transaction.broadcastTxid == nil ? .inRound : .confirming
            } else { status = outgoing ? .sending : .confirming }
        default:
            status = transaction.source == "onchain-wallet" && transaction.hasConfirmation != true
                ? (outgoing ? .sending : .confirming) : .done
        }
        sats = transaction.shownAmount
        sign = transaction.isInternalTransfer ? "" : outgoing ? "−" : "+"
    }

    /// "Nothing left your wallet" needs explicit zeros for every delta the record can
    /// carry: the wallet's balance change and the Ark fee and, for on-chain operations,
    /// the on-chain change and fee. Other records may carry no on-chain values, but any
    /// they carry must be zero. Anything unknown means we can't say.
    /// The signed amount, or dots while amounts are hidden (Home's persisted flag).
    func amount(unit: BitcoinAmountUnit, hidden: Bool, locale: Locale) -> String {
        guard let sats else { return "" }
        return hidden ? "••••" : sign + BitcoinAmount.format(sats: sats, unit: unit, locale: locale)
    }

    /// A failed movement that produced and exited no coins charged nothing: bark 0.8 zeroes
    /// a Lightning send's balance change when it fails before locking coins, but keeps
    /// the fee it planned. Coins sent to an exit left the spendable balance.
    static func leftNothing(_ transaction: HistoryTransaction) -> Bool {
        let unspent = transaction.isFailed && transaction.offchainFeeSat != nil
            && transaction.outputVtxos?.isEmpty == true && transaction.exitedVtxos?.isEmpty == true
        guard transaction.effectiveBalanceSat == 0, transaction.offchainFeeSat == 0 || unspent,
              transaction.exitedVtxos?.isEmpty != false else { return false }
        let onchain = transaction.type == "Onchain"
            || ["onboard", "offboard", "send-onchain", "exit"].contains(transaction.movementKind)
        return onchain
            ? transaction.balanceChangeSat == 0 && transaction.onchainFeeSat == 0
            : (transaction.balanceChangeSat ?? 0) == 0 && (transaction.onchainFeeSat ?? 0) == 0
    }

    /// Incoming money, not a transfer between your own balances, not a failure and not empty.
    static func isNewEligible(_ transaction: HistoryTransaction) -> Bool {
        transaction.direction == "incoming" && !transaction.isInternalTransfer
            && !["failed", "canceled"].contains(transaction.movementStatus) && transaction.amount > 0
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
    // swiftlint:disable:next discouraged_optional_collection - mirrors the server state: unset is not false / nil until the first snapshot
    private var baseline: Set<String>?
    private var visible = false
    private var walletKey: String?
    private var state = HomeLayoutState.loading
    private var armedIDs: Set<String> = []
    private var armedSats: UInt64 = 0
    private var visitRevision: Int?

    /// `summaryRevision` is the app's balance refresh count: a visit arms once it has
    /// moved on from where the visit started and history isn't loading. Nil (fixtures)
    /// has nothing to wait for. `caughtUp` holds it until the foreground catch-up and the
    /// history reload after it have landed: a balance refresh alone can't arm a visit
    /// while a slow catch-up is still importing what arrived while you were away.
    func update(state: HomeLayoutState, transactions: [HistoryTransaction], balanceSats: UInt64,
                visible: Bool, walletKey: String?, summaryRevision: Int? = nil, historyBusy: Bool = false,
                caughtUp: Bool = true, now: Date = Date()) {
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
        if visible && !armed && caughtUp && !historyBusy && state != .loading
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

    /// Activity's dots: the same "new" set, kept while Home is away (activity.md, "New dot").
    func unseen(_ transactions: [HistoryTransaction]) -> Set<String> {
        guard let baseline else { return [] }
        return Set(transactions.filter { !baseline.contains($0.id) && HomeActivityRow.isNewEligible($0) }.map(\.id))
    }

    /// A row Activity showed for a moment counts as seen on Home too.
    func markSeen(_ id: String) {
        baseline?.insert(id)
        newIDs.remove(id)
    }

    /// The card has folded away; Recent takes its place.
    func finishCelebration(_ id: UUID) {
        guard celebration?.id == id else { return }
        celebration = nil
        shown = state
    }
}
