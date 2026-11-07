import Foundation
@testable import Noah
import Synchronization
import Testing

/// Siri's index (voice-assistants.md, "Siri index (iOS)"): what's indexed, when it changes, and that the switch and a
/// deleted wallet leave nothing behind.
struct SiriIndexTests {
    static let now = HistoryRules.parseDate("2026-11-06T12:00:00.000Z") ?? Date()
    static let ark = "tark1pwh9vsmezqqpjy9akejayl2vvcse6he97rn40g84xrlvrlnhayuuyefrp9nse2yspqqkm2rpd"

    static func row(_ id: String, direction: String = "incoming", amount: Int64 = 5000, date: String = "2026-11-05T10:00:00.000Z",
                    kind: String? = nil, status: String? = "successful", destination: String? = nil,
                    comment: String? = nil, description: String? = nil) -> HistoryTransaction {
        var row = HistoryTransaction(id: id, type: "Arkoor", amount: amount, date: date, direction: direction, source: "ark", destination: destination,
                                     movementStatus: status, movementKind: kind)
        row.paymentDescription = description
        if let comment, let destination {
            row.repeatPayment = RepeatPayment(destination: destination, comment: comment, amountMode: "SATS", amountInput: String(amount))
        }
        return row
    }

    static let context = SiriIndex.Context(locale: "en", ownDomain: "noahwallet.com", now: now, fiat: { $0 == 694_375 ? "$412.50" : nil },
                                           recurring: [(label: "Rent", destination: "landlord@example.com"), (label: "Sam", destination: "SAM@noahwallet.com")])

    static let history = [
        row("received", date: "2026-11-06T10:00:00.000Z", description: "Coffee"),
        row("sent-sam", direction: "outgoing", amount: 21000, date: "2026-11-05T09:00:00.000Z", destination: "sam@noahwallet.com", comment: "Lunch"),
        row("sent-sam-old", direction: "outgoing", amount: 42000, date: "2026-10-01T09:00:00.000Z", destination: "Lightning:SAM@noahwallet.com"),
        row("sent-ark", direction: "outgoing", amount: 1000, date: "2026-11-04T09:00:00.000Z", destination: ark),
        row("long-ago", direction: "outgoing", amount: 7, date: "2025-06-01T09:00:00.000Z", destination: "sam@noahwallet.com"),
        // Not payments: a renewal, a move to Ark, an exit, and pending, failed and canceled rows.
        row("renewal", direction: "outgoing", date: "2026-11-06T11:00:00.000Z", kind: "refresh"),
        row("board", date: "2026-11-06T11:10:00.000Z", kind: "onboard"),
        row("exit", direction: "outgoing", date: "2026-11-06T11:20:00.000Z", kind: "exit"),
        row("pending", date: "2026-11-06T11:30:00.000Z", status: "pending"),
        row("failed", direction: "outgoing", date: "2026-11-06T11:40:00.000Z", status: "failed", destination: "bob@example.com"),
        row("canceled", direction: "outgoing", date: "2026-11-06T11:50:00.000Z", status: "canceled"),
    ]

    @Test func balanceIsSirisReply() {
        let balance = SiriIndex.balance(694_375, context: Self.context)
        #expect(balance.sats == 694_375)
        #expect(balance.summary == "You have 694,375 sats, about $412.50.")
        #expect(SiriIndex.balance(1, context: Self.context).summary == "You have 1 sat.")
        #expect(!balance.title.isEmpty)
    }

    @Test func paymentsAreLastYearsSettledPaymentsNewestFirst() throws {
        let payments = SiriIndex.payments(Self.history, context: Self.context)
        #expect(payments.map(\.id) == ["received", "sent-sam", "sent-ark", "sent-sam-old"])
        #expect(payments[0] == .init(id: "received", incoming: true, sats: 5000, counterparty: nil, note: "Coffee",
                                     date: try #require(HistoryRules.parseDate("2026-11-06T10:00:00.000Z")), title: "Received 5,000 sats"))
        #expect(payments[1].title == "Sent 21,000 sats to sam@noahwallet.com")
        #expect(payments[1].note == "Lunch")
        #expect(payments[2].title == "Sent 1,000 sats") // An Ark address names nobody.
        #expect(payments[3].counterparty == "sam@noahwallet.com")
        // The newest 200, however many there are.
        let many = (0..<250).map { Self.row("p\($0)", date: String(format: "2026-10-%02dT10:00:00.000Z", 1 + $0 % 28)) }
        #expect(SiriIndex.payments(many, context: Self.context).count == SiriIndex.paymentLimit)
    }

    @Test func recipientsAreSendsRecentsAndRecurringPayments() {
        let recipients = SiriIndex.recipients(Self.history, context: Self.context)
        #expect(recipients.map(\.destination) == ["sam@noahwallet.com", Self.ark, "landlord@example.com"])
        let sam = recipients[0]
        #expect(sam.name == "sam") // A Noah user, as Send's To screen names them.
        #expect(sam.synonyms == ["sam@noahwallet.com", "Sam"])
        // Every settled payment, the year-old one too; the failed one never counts.
        #expect(sam.payments == 3 && sam.sentSats == 63007)
        #expect(sam.lastPaid == HistoryRules.parseDate("2026-11-05T09:00:00.000Z"))
        #expect(sam.summary?.hasPrefix("Sent 63,007 sats in all, last on ") == true)
        let rent = recipients[2]
        #expect(rent.name == "landlord@example.com" && rent.synonyms == ["landlord", "Rent"] && rent.payments == 0 && rent.summary == nil)
        #expect(!recipients.contains { $0.destination == "bob@example.com" })
    }

    @Test func recipientsMatchWhatWasSaid() {
        let recipients = SiriIndex.recipients(Self.history, context: Self.context)
        #expect(SiriIndex.matching(recipients, query: "Sam").map(\.destination) == ["sam@noahwallet.com"])
        #expect(SiriIndex.matching(recipients, query: "rent").map(\.destination) == ["landlord@example.com"])
        #expect(SiriIndex.matching(recipients, query: "  ").isEmpty)
        #expect(SiriIndex.reusable("Lightning:SAM@noahwallet.com") == "sam@noahwallet.com")
        #expect(SiriIndex.reusable(Self.ark) == Self.ark)
        #expect(SiriIndex.reusable("Sam") == nil)
        #expect(SiriIndex.reusable("lnbcrt1") == nil)
    }

    @Test func indexesOnceAndOnlyWhatChanged() async {
        let index = Recording()
        let indexer = SiriIndexer(dependencies: index.dependencies())
        await indexer.update(balance: 694_375).value
        #expect(index.upserts == [.init(balance: SiriIndex.balance(694_375, context: Self.context))])
        await indexer.update(history: Self.history).value
        // The first rows in a process replace whatever an earlier one left.
        #expect(index.removedAll == [[.payments, .recipients]])
        #expect(index.upserts.last?.payments.count == 4 && index.upserts.last?.recipients.count == 3)
        #expect(index.recipientChanges == 1)

        // The same reads again change nothing.
        let calls = index.upserts.count
        await indexer.update(balance: 694_375, history: Self.history).value
        #expect(index.upserts.count == calls && index.removed.isEmpty && index.recipientChanges == 1)

        // A new payment: only it is indexed, and Sam's totals.
        let newer = [Self.row("sent-sam-2", direction: "outgoing", amount: 1, date: "2026-11-06T11:59:00.000Z", destination: "sam@noahwallet.com")]
        await indexer.update(history: Self.history + newer).value
        #expect(index.upserts.last?.payments.map(\.id) == ["sent-sam-2"])
        #expect(index.upserts.last?.recipients.map(\.destination) == ["sam@noahwallet.com"])
        #expect(index.upserts.last?.balance == nil)
        // A row that's gone is removed.
        await indexer.update(history: Array(Self.history.dropFirst())).value
        #expect(index.removed.last?.payments == ["sent-sam-2", "received"])
        #expect(await indexer.published.payments.map(\.id) == ["sent-sam", "sent-ark", "sent-sam-old"])
    }

    @Test func olderReadsNeverReplaceNewerOnes() async {
        let index = Recording()
        let indexer = SiriIndexer(dependencies: index.dependencies())
        let first = indexer.update(balance: 1)
        let second = indexer.update(balance: 2)
        await second.value
        await first.value
        #expect(await indexer.published.balance?.sats == 2)
    }

    @Test func switchingOffRemovesEverythingAndStopsIndexing() async throws {
        let index = Recording()
        let indexer = SiriIndexer(dependencies: index.dependencies())
        await indexer.update(balance: 694_375, history: Self.history).value
        #expect(indexer.enabled)

        try await indexer.setEnabled(false)
        #expect(!indexer.enabled && index.setting == "off")
        #expect(index.removedAll.last == Set(SiriIndexStore.Kind.allCases))
        #expect(await indexer.published == .empty)
        let calls = index.upserts.count
        await indexer.update(balance: 1, history: Self.history).value
        let hidden = await indexer.published
        #expect(index.upserts.count == calls && hidden == .empty)
        #expect(await indexer.current() == .empty) // Siri's queries see nothing.
        #expect(index.readCount == 0)

        // On again: everything comes back, from what the app last read.
        try await indexer.setEnabled(true)
        let snapshot = await indexer.published
        #expect(index.setting == "on" && snapshot.balance?.sats == 1 && snapshot.payments.count == 4 && snapshot.recipients.count == 3)
        #expect(index.upserts.last == snapshot)
    }

    @Test func aDeletedWalletLeavesNothing() async {
        let index = Recording()
        let indexer = SiriIndexer(dependencies: index.dependencies())
        await indexer.update(balance: 694_375, history: Self.history).value
        index.present.withLock { $0 = false }
        await indexer.walletDeleted()
        #expect(index.removedAll.last == Set(SiriIndexStore.Kind.allCases))
        #expect(await indexer.published == .empty)
        // Reads still under way, and anything after while no wallet is there, index nothing.
        let calls = index.upserts.count
        await indexer.update(balance: 5, history: Self.history).value
        let published = await indexer.published
        #expect(index.upserts.count == calls && published == .empty)

        // A removal that failed during deletion is tried again by the next update (onboarding sends one).
        let failed = Recording()
        let other = SiriIndexer(dependencies: failed.dependencies())
        await other.update(balance: 694_375, history: Self.history).value
        failed.present.withLock { $0 = false }
        failed.failingRemovals.withLock { $0 = 1 }
        let removals = failed.removedAll.count
        await other.walletDeleted()
        #expect(failed.removedAll.count == removals)
        await other.update().value
        #expect(failed.removedAll.count == removals + 1 && failed.removedAll.last == Set(SiriIndexStore.Kind.allCases))
    }

    @Test func switchStaysOnWhenTheIndexCantBeRemoved() async throws {
        let index = Recording()
        let indexer = SiriIndexer(dependencies: index.dependencies())
        await indexer.update(balance: 694_375, history: Self.history).value
        index.failingRemovals.withLock { $0 = 1 }
        await #expect(throws: SiriIndexer.RemovalFailed.self) { try await indexer.setEnabled(false) }
        // Never off over indexed data: the switch is back on and the index whole again.
        #expect(indexer.enabled && index.setting == "on")
        #expect(await indexer.published.payments.count == 4)
    }

    @Test func anythingLeftIndexedGoesWhileOffOrWithoutAWallet() async {
        // An earlier process may have indexed before the switch went off or the wallet went.
        for (setting, present) in [("off", true), ("on", false)] {
            let index = Recording()
            index.stored.withLock { $0 = setting }
            index.present.withLock { $0 = present }
            let indexer = SiriIndexer(dependencies: index.dependencies())
            index.failingRemovals.withLock { $0 = 1 }
            await indexer.update(balance: 1).value // Fails: the next one tries again.
            await indexer.update(balance: 2).value
            await indexer.update(balance: 3).value // Removed already: nothing more to do.
            #expect(index.removedAll == [Set(SiriIndexStore.Kind.allCases)])
            #expect(await indexer.published == .empty)
        }
    }

    @Test(.timeLimit(.minutes(1)))
    func aQueryReadingTheWalletDoesntHoldUpIndexing() async {
        // A background pass holds the wallet while it indexes; a Siri query waiting for the wallet mustn't block it.
        let index = Recording(), gate = Gate()
        var dependencies = index.dependencies()
        let read = dependencies.read
        dependencies.read = { await gate.wait(); return try await read() }
        let indexer = SiriIndexer(dependencies: dependencies)
        let query = Task { await indexer.current() }
        while await gate.waiting == 0 { await Task.yield() }
        await indexer.update(balance: 1).value
        await gate.release()
        let snapshot = await query.value
        // The pass's newer balance wins over the query's read; the read's rows fill the rest.
        #expect(snapshot.balance?.sats == 1 && snapshot.payments.count == 4)
    }

    @Test func queriesReadTheWalletWhenNothingWasHandedOver() async {
        let index = Recording()
        let indexer = SiriIndexer(dependencies: index.dependencies())
        let snapshot = await indexer.current()
        #expect(index.readCount == 1 && snapshot.balance?.sats == 694_375 && snapshot.payments.count == 4)
        _ = await indexer.current()
        #expect(index.readCount == 1)
    }

    @available(iOS 18.4, *)
    @Test func recipientQuerySuggestsRecentsAndTakesAnyAddress() async throws {
        let index = Recording()
        let indexer = SiriIndexer(dependencies: index.dependencies())
        await indexer.update(history: Self.history).value
        try await SiriIndexer.$queried.withValue(indexer) {
            let query = NoahRecipientQuery()
            #expect(await query.suggestedEntities().map(\.id) == ["sam@noahwallet.com", Self.ark, "landlord@example.com"])
            #expect(await query.entities(matching: "sam").map(\.id) == ["sam@noahwallet.com"])
            #expect(await query.entities(matching: "Rent").map(\.id) == ["landlord@example.com"])
            // An address said or typed in full, even one never paid.
            #expect(await query.entities(matching: "Carol@Example.com").map(\.id) == ["carol@example.com"])
            #expect(await query.entities(matching: "nobody").isEmpty)
            // An ID is the address itself, so Pay gets it back in any process.
            let back = await query.entities(for: ["sam@noahwallet.com", "dave@example.com", "not an address"])
            #expect(back.map(\.id) == ["sam@noahwallet.com", "dave@example.com"])
            let sam = try #require(back.first)
            #expect(sam.name == "sam" && sam.spoken == "sam@noahwallet.com" && sam.payments == 3)
            #expect(NoahRecipientEntity(SiriIndex.recipient(Self.ark, ownDomain: "noahwallet.com")).spoken == "tark1p…2rpd")

            try await indexer.setEnabled(false)
            // Off: no suggestions; an address said in full still works.
            #expect(await query.suggestedEntities().isEmpty)
            #expect(await query.entities(matching: "sam@noahwallet.com").map(\.id) == ["sam@noahwallet.com"])
        }
    }

    @available(iOS 18.4, *)
    @Test func paymentAndBalanceQueriesServeTheIndex() async {
        let index = Recording()
        let indexer = SiriIndexer(dependencies: index.dependencies())
        await indexer.update(balance: 694_375, history: Self.history).value
        await SiriIndexer.$queried.withValue(indexer) {
            #expect(await NoahPaymentQuery().entities(for: ["sent-sam", "renewal"]).map(\.title) == ["Sent 21,000 sats to sam@noahwallet.com"])
            let payment = await NoahPaymentQuery().entities(for: ["sent-sam"]).first
            #expect(payment?.paidTo == ["sam@noahwallet.com"] && payment?.note == "Lunch" && payment?.sats == 21000 && payment?.received == false)
            #expect(await NoahBalanceQuery().entities(for: ["balance"]).first?.summary == "You have 694,375 sats, about $412.50.")
        }
    }

    @available(iOS 18.4, *)
    @Test func payHandsSendTheAddressAndAPositiveAmount() {
        let sam = NoahRecipientEntity(SiriIndex.recipient("sam@noahwallet.com", ownDomain: "noahwallet.com"))
        #expect(PayNoahRecipientIntent(recipient: sam, amountSats: 5000).request == .init(recipient: "sam@noahwallet.com", sats: 5000))
        #expect(PayNoahRecipientIntent(recipient: sam).request.sats == nil)
        #expect(PayNoahRecipientIntent(recipient: sam, amountSats: 0).request.sats == nil)
        #expect(AssistantReply.pay(recipient: sam.spoken, locale: "en") == "Opening Send to sam@noahwallet.com.")
    }
}

/// An index in memory, recording each change.
private final class Recording: Sendable {
    struct Removal: Equatable { var payments: [String]; var recipients: [String]; var balance: Bool }
    struct Log {
        var upserts: [SiriIndex.Snapshot] = []
        var removed: [Removal] = []
        var removedAll: [Set<SiriIndexStore.Kind>] = []
        var recipientChanges = 0
    }

    private let log = Mutex(Log())
    struct RemovalFailed: Error {}
    /// The next this many `removeAll`s fail, as Spotlight can.
    let failingRemovals = Mutex(0)
    let stored = Mutex<String?>(nil)
    let present = Mutex(true)
    let reads = Mutex(0)

    var setting: String? { stored.withLock { $0 } }
    var readCount: Int { reads.withLock { $0 } }
    var upserts: [SiriIndex.Snapshot] { log.withLock { $0.upserts } }
    var removed: [Removal] { log.withLock { $0.removed } }
    var removedAll: [Set<SiriIndexStore.Kind>] { log.withLock { $0.removedAll } }
    var recipientChanges: Int { log.withLock { $0.recipientChanges } }

    func dependencies() -> SiriIndexer.Dependencies {
        .init(store: .init(upsert: { snapshot in self.log.withLock { $0.upserts.append(snapshot) } },
                           remove: { payments, recipients, balance in
                               self.log.withLock { $0.removed.append(.init(payments: payments, recipients: recipients, balance: balance)) }
                           },
                           removeAll: { kinds in
                               if self.failingRemovals.withLock({ failing in defer { failing = max(0, failing - 1) }; return failing > 0 }) {
                                   throw RemovalFailed()
                               }
                               self.log.withLock { $0.removedAll.append(kinds) }
                           }),
              setting: .init(isOn: { self.stored.withLock { $0 } != "off" }, set: { on in self.stored.withLock { $0 = on ? "on" : "off" } }),
              context: { SiriIndexTests.context },
              walletPresent: { self.present.withLock { $0 } },
              read: {
                  self.reads.withLock { $0 += 1 }
                  return (694_375, SiriIndexTests.history)
              },
              recipientsChanged: { self.log.withLock { $0.recipientChanges += 1 } })
    }
}

/// Holds a read until released, as a wallet another pass holds would.
private actor Gate {
    private(set) var waiting = 0
    private var released = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func wait() async {
        guard !released else { return }
        waiting += 1
        await withCheckedContinuation { waiters.append($0) }
    }

    func release() {
        released = true
        waiters.forEach { $0.resume() }
        waiters = []
    }
}
