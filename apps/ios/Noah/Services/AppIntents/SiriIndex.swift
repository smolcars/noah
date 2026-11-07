import Foundation
import Synchronization

/// What Noah adds to the on-device index so Siri and Spotlight can answer "How much bitcoin do I have", "When did I
/// last pay Sam" or "How much did I send Sam" (docs/native-rewrite/design/voice-assistants.md, "Siri index"): the
/// balance, settled payments and the people you pay. Built only from what the app already read; never a secret.
enum SiriIndex {
    /// Payments indexed: the newest settled ones from the last year, at most this many.
    static let paymentLimit = 200
    static let paymentWindow: TimeInterval = 365 * 24 * 60 * 60
    /// Send's recents rule, as its To screen's search uses it, plus recurring payment recipients.
    static let recentLimit = 12

    struct Balance: Equatable, Sendable {
        var sats: UInt64
        var title: String
        /// Siri's balance reply: "You have 694,375 sats, about $412.50."
        var summary: String
    }

    struct Payment: Equatable, Sendable, Identifiable {
        var id: String
        var incoming: Bool
        var sats: Int64
        /// The Lightning address an outgoing payment went to, as Last payment names it.
        var counterparty: String?
        var note: String?
        var date: Date
        /// "Sent 21,000 sats to sam@noahwallet.com", "Received 5,000 sats".
        var title: String
    }

    /// Someone you can pay again: a Lightning, Ark or bitcoin address. Its ID is the address itself, so Pay finds it
    /// again in any process.
    struct Recipient: Equatable, Sendable, Identifiable {
        var id: String { destination }
        var destination: String
        /// As Send's To screen names it: a Noah user's name, another Lightning address, or an address's ends.
        var name: String
        /// Other ways to say it: the whole address, a Lightning address's name, recurring payments' names.
        var synonyms: [String] = []
        /// Settled payments to it, in all.
        var payments = 0
        var sentSats: Int64 = 0
        var lastPaid: Date?
        /// "Sent 63,000 sats in all, last on 3 Nov 2026."; nil while there's no payment to it.
        var summary: String?
    }

    struct Snapshot: Equatable, Sendable {
        var balance: Balance?
        var payments: [Payment] = []
        var recipients: [Recipient] = []

        static let empty = Self()
    }

    /// What indexing reads besides the wallet: the app's language, its Lightning domain, the fiat Siri may add and
    /// the recurring payments' recipients.
    struct Context: Sendable {
        var locale: String
        var ownDomain: String
        var now: Date
        var fiat: @Sendable (UInt64) -> String? = { _ in nil }
        var recurring: [(label: String, destination: String)] = []
    }

    static func balance(_ sats: UInt64, context: Context) -> Balance {
        Balance(sats: sats, title: L10n.text("Noah Balance", locale: context.locale),
                summary: AssistantReply.balance(sats: sats, fiat: context.fiat(sats), locale: context.locale))
    }

    /// Activity's settled payments (Last payment's rule), newest first, from the last year.
    static func payments(_ history: [HistoryTransaction], context: Context) -> [Payment] {
        let since = context.now.addingTimeInterval(-paymentWindow)
        let payments = settled(history).compactMap { row -> Payment? in
            guard let sats = row.shownAmount, let date = row.timestamp, date >= since else { return nil }
            let incoming = row.direction == "incoming"
            let counterparty = incoming ? nil : counterparty(row)
            let amount = BitcoinAmount.satsLine(sats, locale: Locale(identifier: context.locale))
            let title = incoming ? L10n.text("Received {amount}", amount, locale: context.locale)
                : counterparty.map { L10n.text("Sent {amount} to {recipient}", amount, $0, locale: context.locale) }
                ?? L10n.text("Sent {amount}", amount, locale: context.locale)
            return Payment(id: row.id, incoming: incoming, sats: sats, counterparty: counterparty, note: note(row), date: date, title: title)
        }
        return Array(payments.prefix(paymentLimit))
    }

    /// Send's recents, then recurring payments' recipients, each with what you've sent it in all.
    static func recipients(_ history: [HistoryTransaction], context: Context) -> [Recipient] {
        let rows = normalized(history)
        var recipients = SendRecent.from(rows, ownDomain: context.ownDomain, limit: recentLimit)
            .map { recipient($0.destination, ownDomain: context.ownDomain) }
        for schedule in context.recurring {
            guard let destination = reusable(schedule.destination) else { continue }
            let label = schedule.label.trimmingCharacters(in: .whitespacesAndNewlines)
            if let index = recipients.firstIndex(where: { $0.destination.lowercased() == destination.lowercased() }) {
                if !label.isEmpty, !recipients[index].synonyms.contains(label) { recipients[index].synonyms.append(label) }
            } else {
                var entry = recipient(destination, ownDomain: context.ownDomain)
                if !label.isEmpty, label != entry.name { entry.synonyms.append(label) }
                recipients.append(entry)
            }
        }
        let paid = settled(history).filter { $0.direction == "outgoing" }
        return recipients.map { entry in
            var entry = entry
            let rows = paid.filter { SendRecent.reusable($0)?.lowercased() == entry.destination.lowercased() }
            entry.payments = rows.count
            entry.sentSats = rows.reduce(0) { $0 + ($1.shownAmount ?? 0) }
            entry.lastPaid = rows.compactMap(\.timestamp).max()
            entry.summary = entry.lastPaid.map {
                L10n.text("Sent {amount} in all, last on {date}.", BitcoinAmount.satsLine(entry.sentSats, locale: Locale(identifier: context.locale)),
                          $0.formatted(Date.FormatStyle(date: .abbreviated, time: .omitted).locale(Locale(identifier: context.locale))),
                          locale: context.locale)
            }
            return entry
        }
    }

    /// A recipient for an address alone, as Pay gets it back by its ID; nil for anything Send couldn't pay again.
    static func recipient(_ destination: String, ownDomain: String) -> Recipient {
        let label = SendRecipientLabel.of(destination: destination, ownDomain: ownDomain)
        var synonyms: [String] = []
        if label.name != destination { synonyms.append(destination) }
        if let address = RepeatPayment.normalizeLightningAddress(destination), let local = address.split(separator: "@").first.map(String.init),
           local != label.name {
            synonyms.append(local)
        }
        return Recipient(destination: destination, name: label.name, synonyms: synonyms)
    }

    /// A Lightning, Ark or bitcoin address Send could pay again as it is; a Lightning address in lower case.
    static func reusable(_ value: String) -> String? {
        let cleaned = SendDestination.cleaned(value)
        if let address = RepeatPayment.normalizeLightningAddress(cleaned), SendDestination.isLightningAddress(address) { return address }
        let parsed = SendDestination.parse(cleaned)
        return parsed.error == nil && ["onchain", "ark"].contains(parsed.destinationType) ? cleaned : nil
    }

    /// The recipients whose name, address or other names hold what was said.
    static func matching(_ recipients: [Recipient], query: String) -> [Recipient] {
        let query = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return [] }
        return recipients.filter { ([$0.name, $0.destination] + $0.synonyms).contains { $0.localizedStandardContains(query) } }
    }

    /// Activity's rows in its order: on-chain rows merged into their Ark moves, as `HistoryModel` shows them.
    private static func normalized(_ history: [HistoryTransaction]) -> [HistoryTransaction] {
        HistoryRules.merge(history.filter { $0.source != "onchain-wallet" }, onchain: history.filter { $0.source == "onchain-wallet" })
            .sorted(by: HistoryRules.precedes)
    }

    private static func settled(_ history: [HistoryTransaction]) -> [HistoryTransaction] {
        normalized(history).filter(AssistantReply.isPayment)
    }

    private static func counterparty(_ row: HistoryTransaction) -> String? {
        (row.repeatPayment?.destination ?? row.destination).flatMap(RepeatPayment.normalizeLightningAddress)
    }

    /// The note Activity's detail shows: yours, else the invoice's description.
    private static func note(_ row: HistoryTransaction) -> String? {
        let comment = row.repeatPayment?.comment.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !comment.isEmpty { return comment }
        let description = row.paymentDescription?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return description.isEmpty ? nil : description
    }
}

/// Where the index lives: Core Spotlight on a device, a recording in tests.
struct SiriIndexStore: Sendable {
    enum Kind: CaseIterable, Sendable { case balance, payments, recipients }

    /// Adds or replaces what the snapshot holds.
    var upsert: @Sendable (SiriIndex.Snapshot) async throws -> Void
    var remove: @Sendable (_ payments: [String], _ recipients: [String], _ balance: Bool) async throws -> Void
    var removeAll: @Sendable (Set<Kind>) async throws -> Void

    /// Before iOS 18.4, which indexes app entities.
    static let unavailable = Self(upsert: { _ in }, remove: { _, _, _ in }, removeAll: { _ in })
}

/// The "Siri & Search" switch: on unless turned off, kept in the app's settings and removed with the wallet.
struct SiriIndexSetting: Sendable {
    static let key = "native-siri-index"

    var isOn: @Sendable () -> Bool
    var set: @Sendable (Bool) throws -> Void

    static func stored(in settings: SettingsStorage) -> Self {
        Self(isOn: { settings.string(forKey: key) != "off" }, set: { try settings.setDurably($0 ? "on" : "off", forKey: key) })
    }
}

/// Keeps the index in step with what the app last read: the balance after each sync or payment, Activity's rows as
/// they change, and both from background passes. One change at a time, newest reads winning; nothing while the
/// switch is off, and nothing once the wallet is gone.
actor SiriIndexer {
    struct Dependencies: Sendable {
        var store: SiriIndexStore
        var setting: SiriIndexSetting
        var context: @Sendable () -> SiriIndex.Context
        var walletPresent: @Sendable () -> Bool
        /// The wallet's balance and rows without a sync, for a query Siri runs before the app handed any over.
        var read: @Sendable () async throws -> (balance: UInt64, history: [HistoryTransaction])
        /// Pay's suggested recipients changed (`updateAppShortcutParameters`).
        var recipientsChanged: @Sendable () -> Void = {}
    }

    static let shared = SiriIndexer(dependencies: .live())

    private let dependencies: Dependencies
    private let log = AppLog("siri")
    private nonisolated let stamps = Mutex(0)
    private var balance: (sats: UInt64, stamp: Int)?
    private var history: (rows: [HistoryTransaction], stamp: Int)?
    /// Updates read before the wallet was deleted are dropped.
    private var cleared = 0
    /// What this process put in the index.
    private var indexed = SiriIndex.Snapshot.empty
    /// Kinds whose entries from an earlier process haven't been replaced yet.
    private var inherited = Set(SiriIndexStore.Kind.allCases)
    /// What queries see: the index's content, empty while the switch is off.
    private(set) var published = SiriIndex.Snapshot.empty
    private var tail: Task<Void, Never>?

    init(dependencies: Dependencies) { self.dependencies = dependencies }

    /// The "Siri & Search" switch.
    nonisolated var enabled: Bool { dependencies.setting.isOn() }

    /// A background pass's balance and Activity's rows, read from the session the pass holds (never a second open).
    nonisolated func refresh(balance: UInt64, session: WalletSession) async {
        guard enabled else { return }
        let history = try? await session.datedTransactionHistory()
        await update(balance: balance, history: history).value
    }

    /// Hands over what the app just read; nil leaves that part as it is. The task ends once it's indexed.
    @discardableResult // swiftlint:disable:next discouraged_optional_collection - nil: not read, unlike no rows
    nonisolated func update(balance: UInt64? = nil, history: [HistoryTransaction]? = nil) -> Task<Void, Never> {
        let stamp = stamps.withLock { $0 += 1; return $0 }
        return Task { await serial { indexer in
            guard stamp > indexer.cleared else { return }
            if let balance, stamp > indexer.balance?.stamp ?? 0 { indexer.balance = (balance, stamp) }
            if let history, stamp > indexer.history?.stamp ?? 0 { indexer.history = (history, stamp) }
            await indexer.publish()
        } }
    }

    /// The switch: off removes everything Noah indexed and stops indexing; on indexes again.
    func setEnabled(_ enabled: Bool) async throws {
        try dependencies.setting.set(enabled)
        await serial { indexer in
            if enabled {
                await indexer.readIfNeeded()
                indexer.inherited = Set(SiriIndexStore.Kind.allCases)
                await indexer.publish()
            } else {
                await indexer.removeAll()
            }
        }
    }

    /// The wallet was deleted: nothing it indexed stays, and reads still under way are dropped.
    nonisolated func walletDeleted() {
        let stamp = stamps.withLock { $0 += 1; return $0 }
        Task { await serial { indexer in
            indexer.cleared = stamp
            indexer.balance = nil
            indexer.history = nil
            await indexer.removeAll()
        } }
    }

    /// What Siri's queries see, read from the wallet when this process hasn't been handed anything yet.
    func current() async -> SiriIndex.Snapshot {
        await serial { indexer in
            guard indexer.history == nil, indexer.dependencies.setting.isOn() else { return }
            await indexer.readIfNeeded()
            await indexer.publish()
        }
        return published
    }

    /// Runs one change after the last, in call order.
    private func serial(_ work: @escaping @Sendable (isolated SiriIndexer) async -> Void) async {
        let previous = tail
        let task = Task { await previous?.value; await work(self) }
        tail = task
        await task.value
    }

    private func readIfNeeded() async {
        guard history == nil, dependencies.walletPresent() else { return }
        let stamp = stamps.withLock { $0 += 1; return $0 }
        do {
            let read = try await dependencies.read()
            guard stamp > cleared else { return }
            if stamp > balance?.stamp ?? 0 { balance = (read.balance, stamp) }
            if history == nil { history = (read.history, stamp) }
        } catch {
            log.w("Couldn't read the wallet for Siri")
        }
    }

    private func publish() async {
        guard dependencies.setting.isOn(), dependencies.walletPresent() else {
            published = .empty
            return
        }
        let context = dependencies.context()
        var target = indexed
        if let balance { target.balance = SiriIndex.balance(balance.sats, context: context) }
        if let history {
            target.payments = SiriIndex.payments(history.rows, context: context)
            target.recipients = SiriIndex.recipients(history.rows, context: context)
        }
        do {
            // A new process doesn't know what the last one indexed: replace each kind wholesale the first time.
            let replacing = inherited.intersection(history == nil ? [] : [.payments, .recipients])
            if !replacing.isEmpty {
                try await dependencies.store.removeAll(replacing)
                indexed.payments = []
                indexed.recipients = []
                inherited.subtract(replacing)
            }
            let kept = (payments: Set(target.payments.map(\.id)), recipients: Set(target.recipients.map(\.id)))
            let stale = (payments: indexed.payments.map(\.id).filter { !kept.payments.contains($0) },
                         recipients: indexed.recipients.map(\.id).filter { !kept.recipients.contains($0) })
            if !stale.payments.isEmpty || !stale.recipients.isEmpty || (indexed.balance != nil && target.balance == nil) {
                try await dependencies.store.remove(stale.payments, stale.recipients, indexed.balance != nil && target.balance == nil)
            }
            let before = (payments: Dictionary(indexed.payments.map { ($0.id, $0) }, uniquingKeysWith: { $1 }),
                          recipients: Dictionary(indexed.recipients.map { ($0.id, $0) }, uniquingKeysWith: { $1 }))
            let changed = SiriIndex.Snapshot(balance: target.balance == indexed.balance ? nil : target.balance,
                                             payments: target.payments.filter { before.payments[$0.id] != $0 },
                                             recipients: target.recipients.filter { before.recipients[$0.id] != $0 })
            if changed != .empty { try await dependencies.store.upsert(changed) }
            inherited.remove(.balance)
            let recipientsChanged = indexed.recipients != target.recipients
            indexed = target
            published = target
            if recipientsChanged { dependencies.recipientsChanged() }
        } catch {
            log.w("Couldn't update the Siri index")
        }
    }

    private func removeAll() async {
        do {
            try await dependencies.store.removeAll(Set(SiriIndexStore.Kind.allCases))
            inherited = []
        } catch {
            log.w("Couldn't remove Noah's Siri index")
            inherited = Set(SiriIndexStore.Kind.allCases)
        }
        let hadRecipients = !indexed.recipients.isEmpty
        indexed = .empty
        published = .empty
        if hadRecipients { dependencies.recipientsChanged() }
    }
}

extension SiriIndexer.Dependencies {
    static func live(services: AppServices = .shared, settings: SettingsStorage = .shared) -> Self {
        let location = WalletDataLocation(variant: services.variant)
        let store: SiriIndexStore = if #available(iOS 18.4, *) { .spotlight } else { .unavailable }
        return Self(
            store: store, setting: .stored(in: settings),
            context: {
                let locale = LegacyLocale.resolved()
                // A read of the recurring store alone: no policy ledger, so nothing is settled or written.
                let schedules = (try? RecurringStore(settings: settings).load().schedules.values.filter { !$0.unsupportedStorage }) ?? []
                return .init(locale: locale, ownDomain: SiriIndexer.ownDomain(settings: settings, variant: services.variant),
                             now: Date(), fiat: { NoahIntentWallet.current.fiat($0, locale) },
                             recurring: schedules.map { (label: $0.label, destination: $0.destination) })
            },
            walletPresent: {
                FileManager.default.fileExists(atPath: location.directory.path) && !location.restorePending
                    && !FileManager.default.fileExists(atPath: location.deletionMarker.path)
            },
            read: {
                try await services.withBackgroundWallet {
                    try await services.ensureWalletOpen()
                    guard let summary = try await services.session.summary() else { throw AppServices.SeedUnavailable() }
                    return (summary.balance.totalSats, try await services.session.datedTransactionHistory())
                }
            },
            recipientsChanged: {
                if #available(iOS 18.4, *) { NoahAppShortcuts.updateAppShortcutParameters() }
            })
    }
}
