import BarkBoltFFI
import Foundation
@testable import Noah
import SwiftUI
import Testing
import UIKit

/// Receive shows exactly the chosen format, and its arrivals follow real events.
@MainActor
struct ReceiveFormatTests {
    static let address = "maya@noahwallet.io"
    static let plain = ReceiveRequest(arkAddress: SendReceiveShotTests.ark, onchainAddress: SendReceiveShotTests.bitcoin)
    static var priced: ReceiveRequest {
        var request = plain
        request.amountSat = 1_500
        request.lightningInvoice = SendReceiveShotTests.invoice
        request.paymentHash = PaymentEncoding.invoice(SendReceiveShotTests.invoice)?.hash
        return request
    }

    @Test func eachFormatCarriesExactlyItsPayload() throws {
        #expect(ReceiveFormat.lightningAddress.payload(request: nil, address: Self.address) == Self.address)
        #expect(ReceiveFormat.lightningAddress.payload(request: Self.plain, address: nil) == nil)
        #expect(ReceiveFormat.request.payload(request: Self.plain, address: Self.address) == (try Self.plain.uri()))
        #expect(ReceiveFormat.request.payload(request: Self.priced, address: Self.address) == (try Self.priced.uri()))
        #expect(try Self.priced.uri().contains("lightning="))
        #expect(ReceiveFormat.invoice.payload(request: Self.plain, address: Self.address) == nil)
        #expect(ReceiveFormat.invoice.payload(request: Self.priced, address: Self.address) == SendReceiveShotTests.invoice)
        #expect(ReceiveFormat.ark.payload(request: Self.priced, address: Self.address) == SendReceiveShotTests.ark)
        #expect(ReceiveFormat.bitcoin.payload(request: Self.priced, address: Self.address) == SendReceiveShotTests.bitcoin)
        #expect(ReceiveFormat.allCases.count == 5)
        // An amount shows the unified request; removing it returns to the Lightning address from any format.
        #expect(ReceiveFormat.after(amountChange: true) == .request)
        #expect(ReceiveFormat.after(amountChange: false) == .lightningAddress)
        // A fresh request keeps only what it can show: no invoice without an amount.
        for format in ReceiveFormat.allCases {
            let kept = ReceiveFormat.reconciled(format, hasAmount: false)
            #expect(kept.payload(request: Self.plain, address: Self.address) != nil, "\(format)")
        }
        #expect(ReceiveFormat.reconciled(.ark, hasAmount: false) == .ark && ReceiveFormat.reconciled(.bitcoin, hasAmount: false) == .bitcoin)
        #expect(ReceiveFormat.reconciled(.invoice, hasAmount: true) == .invoice)
        #expect(ReceiveFormat.request.showsBand(hasAmount: true) && !ReceiveFormat.ark.showsBand(hasAmount: true))
        #expect(QRMatrix.correction(for: try Self.priced.uri()) == "M" && QRMatrix.correction(for: Self.address) == "H")
    }

    /// The card as drawn decodes to exactly the shown format, with the logo: every format once, and the
    /// densest (the priced request) in the dark theme too.
    @Test func theShownQRDecodesToTheChosenFormat() throws {
        struct Card { let format: ReceiveFormat; let request: ReceiveRequest; let scheme: ColorScheme }
        let cards = ReceiveFormat.allCases.map { Card(format: $0, request: Self.priced, scheme: .light) }
            + [Card(format: .request, request: Self.plain, scheme: .light), Card(format: .request, request: Self.priced, scheme: .dark)]
        for card in cards {
            let format = card.format, request = card.request, scheme = card.scheme
            let payload = try #require(format.payload(request: request, address: Self.address))
            let card = ReceiveCard(payload: payload, title: format.name, caption: format.caption(hasAmount: request.amountSat != nil),
                                   band: format.showsBand(hasAmount: request.amountSat != nil) ? ("₿ 1,500", nil) : nil, qrSide: 226,
                                   reveal: false)
                .frame(width: 300)
                .padding(24)
                .background(NoahTokens.Colors.background)
                .environment(\.colorScheme, scheme)
            #expect(try StyledQRCodeTests.decode(card) == [payload], "\(format) in \(scheme)")
        }
    }

    /// Fiat amounts stay canonical whatever the locale's separator, so the keypad's "." never
    /// makes "2,80." (not an amount, which used to read as zero and remove the amount).
    @Test func receiveAmountsStayCanonicalAndOnlyAnEmptyAmountClears() {
        var amount = FiatCurrency.satsToFiat(2_800, btcPrice: 100_000, currency: "EUR")
        #expect(amount == "2.80")
        SendKeypad.apply(".", to: &amount)
        SendKeypad.apply("5", to: &amount)
        #expect(amount == "2.805")
        #expect(ReceiveAmountSheet.sats(amount, fiat: true, btcPrice: 100_000) == 2_805)
        #expect(ReceiveAmountSheet.sats("2,80.", fiat: true, btcPrice: 100_000) == nil)
        #expect(ReceiveAmountSheet.sats("1.5", fiat: true, btcPrice: nil) == nil)
        #expect(ReceiveAmountSheet.sats("", fiat: true, btcPrice: 100_000) == 0)
        #expect(ReceiveAmountSheet.sats("1500", fiat: false, btcPrice: nil) == 1_500)
    }

    /// Switching currency keeps a zero an invalid zero: only an emptied amount removes it.
    @Test func switchingCurrencyNeverTurnsAZeroIntoAClear() {
        for zero in ["0", "0.0001", "0.00"] {
            let switched = ReceiveAmountSheet.switched(zero, fromFiat: true, btcPrice: 100_000, currency: "USD")
            #expect(switched == "0" && ReceiveAmountSheet.sats(switched, fiat: false, btcPrice: nil) == 0, "\(zero)")
        }
        #expect(ReceiveAmountSheet.switched("0", fromFiat: false, btcPrice: 100_000, currency: "USD") == "0")
        #expect(ReceiveAmountSheet.switched("1", fromFiat: false, btcPrice: 100_000, currency: "USD") == "0.00")
        #expect(ReceiveAmountSheet.switched("", fromFiat: true, btcPrice: 100_000, currency: "USD").isEmpty)
        #expect(ReceiveAmountSheet.switched("2.80", fromFiat: true, btcPrice: 100_000, currency: "EUR") == "2800")
        #expect(ReceiveAmountSheet.switched("2800", fromFiat: false, btcPrice: 100_000, currency: "EUR") == "2.80")
    }

    @Test func onchainArrivalsAreEveryNewDepositThisRequestsAddressFirst() {
        let snapshot = ReceiveChainSnapshot(tip: 100, incoming: [
            .init(txid: "old", toAddress: 5, received: 5, confirmedHeight: nil),
            .init(txid: "other", toAddress: 0, received: 9, confirmedHeight: nil),
            .init(txid: "mine", toAddress: 7, received: 7, confirmedHeight: nil),
            .init(txid: "mined", toAddress: 8, received: 8, confirmedHeight: 99),
        ])
        // Later checks: every new unconfirmed deposit, the address's first; never an already-confirmed one.
        let later = snapshot.look(known: ["old"], announced: [])
        #expect(later.arrivals.map(\.txid) == ["mine", "other"])
        #expect(later.known == ["old", "other", "mine", "mined"])
        #expect(snapshot.look(known: ["old"], announced: ["mine"]).arrivals.map(\.txid) == ["other"])
        // The first check: a payment to this address that landed while it synced is an
        // arrival; what was already there (unrelated or confirmed) isn't.
        #expect(snapshot.look(known: nil, announced: []).arrivals.map(\.txid) == ["old", "mine"])
        #expect(snapshot.look(known: nil, announced: ["old"]).arrivals.map(\.txid) == ["mine"])
        // With what the wallet knew before the first sync, a deposit that synced in meanwhile to
        // an address you shared earlier is news too; one it already had isn't.
        let synced = ReceiveChainSnapshot(tip: 100, incoming: snapshot.incoming, before: ["other", "mined", "mine"])
        #expect(synced.look(known: nil, announced: []).arrivals.map(\.txid) == ["old", "mine"])
        let older = ReceiveChainSnapshot(tip: 100, incoming: snapshot.incoming, before: ["old", "mined"])
        #expect(older.look(known: nil, announced: []).arrivals.map(\.txid) == ["old", "mine", "other"])
        // After the first arrival, a second deposit still arrives.
        let next = ReceiveChainSnapshot(tip: 100, incoming: snapshot.incoming + [.init(txid: "again", toAddress: 4, received: 4, confirmedHeight: nil)])
        #expect(next.look(known: later.known, announced: ["mine", "other"]).arrivals.map(\.txid) == ["again"])
    }

    /// Two arrivals in one check: the second plays as soon as the first has flipped home,
    /// with nothing else changing; an on-chain arrival then stays for its block row.
    @Test func simultaneousArrivalsPlayInTurn() {
        let start = Date()
        var queue = ReceiveArrivalQueue()
        let deposit = ReceiveArrival(rail: .onchain, sats: 100_000, start: start, txid: "a")
        let lightning = ReceiveArrival(rail: .lightning, sats: 1_500, start: start)
        queue.announce(deposit, at: start)
        queue.announce(lightning, at: start)
        #expect(queue.current?.id == deposit.id && queue.waiting.map(\.id) == [lightning.id])
        let home = start.addingTimeInterval(deposit.settledAt)
        let next = queue.finish(at: home)
        #expect(next)
        #expect(queue.current?.id == lightning.id && queue.current?.start == home && queue.waiting.isEmpty)
        let after = queue.finish(at: home.addingTimeInterval(lightning.settledAt))
        #expect(!after && queue.current == nil)
        // A settled on-chain arrival gives way at once, and stays when nothing follows it.
        queue.announce(deposit, at: start)
        let stays = !queue.finish(at: home)
        #expect(stays && queue.current?.id == deposit.id)
        let second = ReceiveArrival(rail: .onchain, sats: 5_000, start: start, txid: "b")
        queue.announce(second, at: home.addingTimeInterval(1))
        #expect(queue.current?.id == second.id && queue.waiting.isEmpty)
    }

    /// The deposit being followed is replaced (RBF) and the replacement confirms before the
    /// next check: the block row confirms with it instead of waiting for the old one.
    @Test func aConfirmedReplacementConfirmsTheFollowedDeposit() {
        let spent: Set<SendTransaction.Input> = [.init(txid: "funding", vout: 1)]
        var queue = ReceiveArrivalQueue()
        queue.announce(ReceiveArrival(rail: .onchain, sats: 100_000, start: Date(), txid: "a", inputs: spent))
        let replaced = ReceiveChainSnapshot(tip: 101, incoming: [
            .init(txid: "b", toAddress: 100_000, received: 100_000, confirmedHeight: 101, inputs: spent.union([.init(txid: "more", vout: 0)])),
        ])
        let followed = queue.follow(replaced)
        #expect(followed.confirmed && followed.txids == ["b"])
        #expect(queue.current?.txid == "b" && queue.current?.confirmedHeight == 101)
        // Announced as followed, the replacement isn't news either.
        #expect(replaced.look(known: ["a"], announced: followed.txids).arrivals.isEmpty)
        // An unrelated confirmed deposit is not a replacement.
        var other = ReceiveArrivalQueue()
        other.announce(ReceiveArrival(rail: .onchain, sats: 100_000, start: Date(), txid: "a", inputs: spent))
        let unrelated = ReceiveChainSnapshot(tip: 101, incoming: [
            .init(txid: "a", toAddress: 100_000, received: 100_000, confirmedHeight: nil, inputs: spent),
            .init(txid: "c", toAddress: 0, received: 7, confirmedHeight: 101, inputs: [.init(txid: "x", vout: 0)]),
        ])
        let still = other.follow(unrelated)
        #expect(!still.confirmed && !still.ended && other.current?.txid == "a" && other.current?.confirmedHeight == nil)
    }

    /// Deposits A and B arrive and the card moves on to B; then A is fee-bumped. A's
    /// replacement spends A's inputs, so it's not announced as another deposit.
    @Test func aReplacedEarlierDepositIsNotAnnouncedAgain() {
        let start = Date()
        let a: Set<SendTransaction.Input> = [.init(txid: "fundA", vout: 0)], b: Set<SendTransaction.Input> = [.init(txid: "fundB", vout: 1)]
        let first = ReceiveChainSnapshot(tip: 100, incoming: [
            .init(txid: "A", toAddress: 5_000, received: 5_000, confirmedHeight: nil, inputs: a),
            .init(txid: "B", toAddress: 7_000, received: 7_000, confirmedHeight: nil, inputs: b),
        ])
        var queue = ReceiveArrivalQueue(), announced: Set<String> = [], spent: Set<SendTransaction.Input> = []
        let look = first.look(known: [], announced: announced, spent: spent)
        #expect(look.arrivals.map(\.txid) == ["A", "B"])
        for transaction in look.arrivals {
            announced.insert(transaction.txid)
            spent.formUnion(transaction.inputs)
            queue.announce(ReceiveArrival(rail: .onchain, sats: transaction.arrivingSats, start: start, txid: transaction.txid,
                                          inputs: transaction.inputs), at: start)
        }
        queue.finish(at: start.addingTimeInterval(10))
        #expect(queue.current?.txid == "B" && queue.waiting.isEmpty)
        let bumped = ReceiveChainSnapshot(tip: 100, incoming: [
            .init(txid: "A2", toAddress: 5_000, received: 5_000, confirmedHeight: nil, inputs: a.union([.init(txid: "fee", vout: 0)])),
            .init(txid: "B", toAddress: 7_000, received: 7_000, confirmedHeight: nil, inputs: b),
        ])
        let followed = queue.follow(bumped)
        announced.formUnion(followed.txids)
        #expect(!followed.ended && queue.current?.txid == "B")
        #expect(bumped.look(known: look.known, announced: announced, spent: spent).arrivals.isEmpty)
        // Tracking only the current and waiting arrivals, A2 would have arrived again.
        #expect(bumped.look(known: look.known, announced: announced).arrivals.map(\.txid) == ["A2"])
        // A deposit spending other coins still arrives.
        let other = ReceiveChainSnapshot(
            tip: 100,
            incoming: bumped.incoming + [.init(txid: "C", toAddress: 1, received: 1, confirmedHeight: nil, inputs: [.init(txid: "fundC", vout: 0)])])
        #expect(other.look(known: look.known, announced: announced, spent: spent).arrivals.map(\.txid) == ["C"])
    }

    /// B takes the card from A. A2 spends A's coin and one more and is held back as A's
    /// replacement; A3 then spends only that new coin. A2's inputs were kept, so A3 is a
    /// replacement too, not new money. A chain of three replacements stays quiet the same way.
    @Test func aReplacementOfAReplacementIsNotAnnouncedAgain() {
        typealias Input = SendTransaction.Input
        // swiftlint:disable:next discouraged_optional_collection - mirrors look(known:), where nil is Receive's first look
        var known: Set<String>? = [], announced: Set<String> = [], spent: Set<Input> = []
        // One check of Receive's watch, with nothing followed (the card has moved on).
        func check(_ incoming: [ReceiveChainSnapshot.Transaction]) -> [String] {
            let look = ReceiveChainSnapshot(tip: 100, incoming: incoming).look(known: known, announced: announced, spent: spent)
            known = look.known
            spent = look.spent
            announced.formUnion(look.arrivals.map(\.txid))
            return look.arrivals.map(\.txid)
        }
        func deposit(_ txid: String, _ inputs: Set<Input>) -> ReceiveChainSnapshot.Transaction {
            .init(txid: txid, toAddress: 5_000, received: 5_000, confirmedHeight: nil, inputs: inputs)
        }
        let x = Input(txid: "x", vout: 0), y = Input(txid: "y", vout: 0), b = deposit("B", [Input(txid: "fundB", vout: 0)])
        #expect(check([deposit("A", [x]), b]) == ["A", "B"])
        #expect(check([deposit("A2", [x, y]), b]).isEmpty)
        #expect(check([deposit("A3", [y]), b]).isEmpty)
        // A chain of three: D, then D2, D3 and D4, each spending only the coin the one before added.
        let d = (0..<4).map { Input(txid: "d\($0)", vout: 0) }
        #expect(check([deposit("D", [d[0]]), b]) == ["D"])
        #expect(check([deposit("D2", [d[0], d[1]]), b]).isEmpty)
        #expect(check([deposit("D3", [d[1], d[2]]), b]).isEmpty)
        #expect(check([deposit("D4", [d[2], d[3]]), b]).isEmpty)
        // A deposit spending other coins still arrives.
        #expect(check([deposit("D4", [d[2], d[3]]), b, deposit("C", [Input(txid: "fundC", vout: 0)])]) == ["C"])
    }

    /// The followed deposit disappears and a new one pays the same address without spending
    /// any of its inputs: no replacement is assumed. The old wait ends and the new deposit
    /// arrives as usual. Without recorded inputs nothing can replace it either.
    @Test func aNewDepositIsNeverAssumedToReplaceAGoneOne() {
        for spent in [Set([SendTransaction.Input(txid: "funding", vout: 1)]), []] {
            var queue = ReceiveArrivalQueue()
            queue.announce(ReceiveArrival(rail: .onchain, sats: 100_000, start: Date(), txid: "a", inputs: spent))
            let snapshot = ReceiveChainSnapshot(tip: 101, incoming: [
                .init(txid: "b", toAddress: 100_000, received: 100_000, confirmedHeight: nil, inputs: [.init(txid: "other", vout: 0)]),
            ])
            let followed = queue.follow(snapshot)
            #expect(followed.ended && !followed.confirmed && followed.txids.isEmpty && queue.current == nil)
            #expect(snapshot.look(known: ["a"], announced: Set(["a"]).union(followed.txids)).arrivals.map(\.txid) == ["b"])
        }
        // A waiting arrival that's gone leaves the queue; the next one plays.
        var queue = ReceiveArrivalQueue()
        let start = Date()
        queue.announce(ReceiveArrival(rail: .onchain, sats: 1, start: start, txid: "a"), at: start)
        queue.announce(ReceiveArrival(rail: .onchain, sats: 2, start: start, txid: "gone"), at: start)
        queue.announce(ReceiveArrival(rail: .onchain, sats: 3, start: start, txid: "c"), at: start)
        let both = ReceiveChainSnapshot(tip: 101, incoming: [
            .init(txid: "a", toAddress: 1, received: 1, confirmedHeight: nil), .init(txid: "c", toAddress: 3, received: 3, confirmedHeight: nil),
        ])
        let followed = queue.follow(both, at: start)
        #expect(!followed.ended && queue.current?.txid == "a" && queue.waiting.map(\.txid) == ["c"])
    }

    /// Receive's phase line redraws only at its boundaries, so every change falls on one.
    @Test func everyReceivePhaseChangeFallsOnABoundary() {
        let start = Date(), english = Locale(identifier: "en")
        for rail in [ReceiveArrival.Rail.ark, .lightning, .onchain] {
            var arrival = ReceiveArrival(rail: rail, sats: 1_500, start: start, txid: "tx")
            if rail == .onchain {
                arrival.confirmedHeight = 101
                arrival.confirmedAt = start.addingTimeInterval(12.3)
            }
            var last = arrival.phase(at: 0, tip: 100, locale: english).text
            for t in stride(from: 0.0, through: 30, by: 0.005) {
                let text = arrival.phase(at: t, tip: 100, locale: english).text
                guard text != last else { continue }
                last = text
                #expect(arrival.phaseBoundaries.contains { $0 <= t + 1e-9 && t - $0 < 0.006 }, "\(rail) changes at \(t)")
            }
        }
    }

    /// The block row's clock stops once nothing moves: after the drop under Reduce Motion,
    /// and after a confirmation either way. Only the waiting "next" block blinks.
    @Test func theBlockRowHoldsStillOnceSettled() throws {
        let start = Date()
        var arrival = ReceiveArrival(rail: .onchain, sats: 100_000, start: start, txid: "tx")
        #expect(ReceiveBlockRow.settleTime(arrival, reduceMotion: false) == nil)
        let waiting = try #require(ReceiveBlockRow.settleTime(arrival, reduceMotion: true))
        #expect(try Self.row(arrival, at: waiting, reduceMotion: true) == Self.row(arrival, at: waiting + 13.7, reduceMotion: true))
        #expect(try Self.row(arrival, at: 0.05, reduceMotion: true) != Self.row(arrival, at: waiting, reduceMotion: true))
        arrival.confirmedHeight = 101
        arrival.confirmedAt = start.addingTimeInterval(20)
        for reduceMotion in [false, true] {
            let end = try #require(ReceiveBlockRow.settleTime(arrival, reduceMotion: reduceMotion))
            #expect(try Self.row(arrival, at: end, reduceMotion: reduceMotion) == Self.row(arrival, at: end + 13.7, reduceMotion: reduceMotion))
        }
    }

    static func row(_ arrival: ReceiveArrival, at t: Double, reduceMotion: Bool) throws -> Data {
        let view = Canvas { context, size in
            ReceiveBlockRow.draw(MotionCanvas(context: context, size: size, viewBox: CGRect(x: 0, y: 0, width: 300, height: 58), colors: .theme(.light)),
                                 arrival: arrival, tip: 101, time: MotionTime(t: t, reduceMotion: reduceMotion),
                                 copy: (next: "next", locale: Locale(identifier: "en")))
        }
        .frame(width: 300, height: 58)
        return try #require(ImageRenderer(content: view).uiImage?.pngData())
    }

    @Test func arrivalsFlipOnlyAfterTheirPhasesAndPlayEachHapticOnce() {
        let start = Date()
        let lightning = ReceiveArrival(rail: .lightning, sats: 1_500, start: start)
        let english = Locale(identifier: "en")
        #expect(lightning.phase(at: 0.1, tip: nil, locale: english).text == "Incoming over Lightning")
        #expect(lightning.phase(at: 1.0, tip: nil, locale: english).text == "Ark holds it for you")
        #expect(lightning.phase(at: 2.0, tip: nil, locale: english).text == "Claiming with your key")
        #expect(lightning.phase(at: 2.6, tip: nil, locale: english) == ("Received", true))
        #expect(lightning.angle(at: 2.7) == 0 && lightning.angle(at: 3.5) > 90 && lightning.angle(at: 20) == 0)
        #expect(Set(lightning.haptics.map(\.key)).count == lightning.haptics.count)
        var onchain = ReceiveArrival(rail: .onchain, sats: 100_000, start: start, txid: "tx")
        #expect(onchain.phase(at: 2, tip: 100, locale: english).text == "Waiting for block 101")
        onchain.confirmedHeight = 101
        onchain.confirmedAt = start.addingTimeInterval(10)
        #expect(onchain.phase(at: 9, tip: 101, locale: english).text == "Waiting for block 102")
        #expect(onchain.phase(at: 11, tip: 101, locale: english) == ("Confirmed in block 101", true))
    }
}
