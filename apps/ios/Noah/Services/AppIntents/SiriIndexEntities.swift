import AppIntents // swiftlint:disable:this file_name - the index's entities, their queries and its Core Spotlight store
import CoreSpotlight
import Foundation
import SwiftUI

/// Noah's balance in the on-device index: Siri reads its summary for "How much bitcoin do I have".
@available(iOS 18.4, *)
struct NoahBalanceEntity: IndexedEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Balance"
    static let defaultQuery = NoahBalanceQuery()
    static let identifier = "balance"

    let id: String
    let title: String
    @Property(title: "Sats")
    var sats: Int
    @Property(title: "Summary", indexingKey: \.contentDescription)
    var summary: String

    init(_ balance: SiriIndex.Balance) {
        id = Self.identifier
        title = balance.title
        sats = Int(clamping: balance.sats)
        summary = balance.summary
    }

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(title)", subtitle: "\(summary)", image: .init(systemName: "bitcoinsign.circle"))
    }

    var attributeSet: CSSearchableItemAttributeSet {
        let attributes = defaultAttributeSet
        attributes.keywords = ["bitcoin", "sats", "Noah"]
        return attributes
    }
}

/// A settled payment in the on-device index: "When did I last pay Sam", "How much did I send Sam".
@available(iOS 18.4, *)
struct NoahPaymentEntity: IndexedEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Payment"
    static let defaultQuery = NoahPaymentQuery()

    let id: String
    let title: String
    @Property(title: "Sats")
    var sats: Int
    @Property(title: "Received")
    var received: Bool
    @Property(title: "Summary", indexingKey: \.contentDescription)
    var summary: String
    @Property(title: "Date", indexingKey: \.contentCreationDate)
    var date: Date
    @Property(title: "Paid To", indexingKey: \.recipientEmailAddresses)
    var paidTo: [String]
    @Property(title: "Note", indexingKey: \.comment)
    var note: String?

    init(_ payment: SiriIndex.Payment) {
        id = payment.id
        title = payment.title
        sats = Int(clamping: payment.sats)
        received = payment.incoming
        summary = [payment.title, payment.note].compactMap(\.self).joined(separator: " · ")
        date = payment.date
        paidTo = payment.counterparty.map { [$0] } ?? []
        note = payment.note
    }

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(title)", subtitle: "\(date.formatted(date: .abbreviated, time: .shortened))",
                              image: .init(systemName: received ? "arrow.down.left" : "arrow.up.right"))
    }

    var attributeSet: CSSearchableItemAttributeSet {
        let attributes = defaultAttributeSet
        attributes.keywords = ["bitcoin", "sats", "Noah"] + paidTo
        return attributes
    }
}

/// Someone you pay: indexed for Spotlight and the recipient Siri's Pay asks for.
@available(iOS 18.4, *)
struct NoahRecipientEntity: IndexedEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Recipient"
    static let defaultQuery = NoahRecipientQuery()

    /// The address itself.
    let id: String
    let synonyms: [String]
    @Property(title: "Name", indexingKey: \.displayName)
    var name: String
    @Property(title: "Address", indexingKey: \.recipientEmailAddresses)
    var addresses: [String]
    @Property(title: "Summary", indexingKey: \.contentDescription)
    var summary: String?
    @Property(title: "Last Paid", indexingKey: \.lastUsedDate)
    var lastPaid: Date?
    @Property(title: "Sats Sent")
    var sentSats: Int
    @Property(title: "Payments")
    var payments: Int

    init(_ recipient: SiriIndex.Recipient) {
        id = recipient.destination
        synonyms = recipient.synonyms
        name = recipient.name
        addresses = [recipient.destination]
        summary = recipient.summary
        lastPaid = recipient.lastPaid
        sentSats = Int(clamping: recipient.sentSats)
        payments = recipient.payments
    }

    /// What Siri reads back: a Lightning address whole, any other address by its ends.
    var spoken: String {
        RepeatPayment.normalizeLightningAddress(id) ?? SendRecipientLabel.short(id)
    }

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(name)", subtitle: name == id ? nil : "\(id)", synonyms: synonyms.map { "\($0)" })
    }
}

@available(iOS 18.4, *)
struct NoahBalanceQuery: EntityQuery {
    func entities(for identifiers: [String]) async -> [NoahBalanceEntity] {
        guard identifiers.contains(NoahBalanceEntity.identifier), let balance = await SiriIndexer.queried.current().balance else { return [] }
        return [NoahBalanceEntity(balance)]
    }

    func suggestedEntities() async -> [NoahBalanceEntity] {
        await entities(for: [NoahBalanceEntity.identifier])
    }
}

@available(iOS 18.4, *)
struct NoahPaymentQuery: EntityQuery {
    func entities(for identifiers: [String]) async -> [NoahPaymentEntity] {
        let payments = await SiriIndexer.queried.current().payments
        return identifiers.compactMap { id in payments.first { $0.id == id }.map(NoahPaymentEntity.init) }
    }

    func suggestedEntities() async -> [NoahPaymentEntity] {
        await SiriIndexer.queried.current().payments.prefix(10).map(NoahPaymentEntity.init)
    }
}

/// Pay's recipients: Send's recents and recurring payments' recipients while "Siri & Search" is on, and any address
/// said or typed in full.
@available(iOS 18.4, *)
struct NoahRecipientQuery: EntityStringQuery {
    func entities(for identifiers: [String]) async -> [NoahRecipientEntity] {
        let known = await SiriIndexer.queried.current().recipients
        let ownDomain = SiriIndexer.ownDomain()
        return identifiers.compactMap { id in
            if let recipient = known.first(where: { $0.destination == id }) { return NoahRecipientEntity(recipient) }
            return SiriIndex.reusable(id) == id ? NoahRecipientEntity(SiriIndex.recipient(id, ownDomain: ownDomain)) : nil
        }
    }

    func entities(matching string: String) async -> [NoahRecipientEntity] {
        var matches = SiriIndex.matching(await SiriIndexer.queried.current().recipients, query: string)
        if let typed = SiriIndex.reusable(string), !matches.contains(where: { $0.destination.lowercased() == typed.lowercased() }) {
            matches.append(SiriIndex.recipient(typed, ownDomain: SiriIndexer.ownDomain()))
        }
        return matches.map(NoahRecipientEntity.init)
    }

    func suggestedEntities() async -> [NoahRecipientEntity] {
        await SiriIndexer.queried.current().recipients.map(NoahRecipientEntity.init)
    }
}

extension SiriIndexer {
    /// The indexer Siri's queries read; tests swap it.
    @TaskLocal static var queried = SiriIndexer.shared

    /// Noah's own Lightning domain, which Send's names leave off.
    nonisolated static func ownDomain(settings: SettingsStorage = .shared, variant: AppVariant = .current) -> String {
        settings.persistedState(PersistedServerState.self, key: PersistedServerState.key)?.lightningAddress?
            .split(separator: "@").last.map(String.init) ?? LightningIdentity.domain(for: variant)
    }
}

@available(iOS 18.4, *)
extension SiriIndexStore {
    /// Core Spotlight's default index, which Siri reads on device.
    static let spotlight = Self(
        upsert: { snapshot in
            let index = CSSearchableIndex.default()
            if let balance = snapshot.balance { try await index.indexAppEntities([NoahBalanceEntity(balance)]) }
            if !snapshot.payments.isEmpty { try await index.indexAppEntities(snapshot.payments.map(NoahPaymentEntity.init)) }
            if !snapshot.recipients.isEmpty { try await index.indexAppEntities(snapshot.recipients.map(NoahRecipientEntity.init)) }
        },
        remove: { payments, recipients, balance in
            let index = CSSearchableIndex.default()
            if !payments.isEmpty { try await index.deleteAppEntities(identifiedBy: payments, ofType: NoahPaymentEntity.self) }
            if !recipients.isEmpty { try await index.deleteAppEntities(identifiedBy: recipients, ofType: NoahRecipientEntity.self) }
            if balance { try await index.deleteAppEntities(identifiedBy: [NoahBalanceEntity.identifier], ofType: NoahBalanceEntity.self) }
        },
        removeAll: { kinds in
            let index = CSSearchableIndex.default()
            if kinds.contains(.balance) { try await index.deleteAppEntities(ofType: NoahBalanceEntity.self) }
            if kinds.contains(.payments) { try await index.deleteAppEntities(ofType: NoahPaymentEntity.self) }
            if kinds.contains(.recipients) { try await index.deleteAppEntities(ofType: NoahRecipientEntity.self) }
        })
}

/// Names the payment on screen for Siri, so "this payment" means it (the index's entity of the same ID).
struct SiriPaymentOnScreen: ViewModifier {
    let id: String

    func body(content: Content) -> some View {
        if #available(iOS 18.4, *) {
            content.appEntityIdentifier(EntityIdentifier(for: NoahPaymentEntity.self, identifier: id))
        } else {
            content
        }
    }
}
