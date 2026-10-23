import Foundation
import Testing
@testable import Noah

/// activity.md "Detail": the status line, the rows that apply and the Share receipt.
@MainActor
struct ActivityDetailTests {
    static let own = "noahwallet.io"
    static let preimage = String(repeating: "ab", count: 32)
    static let txid = String(repeating: "cd", count: 32)

    static func lightningSend(status: String = "successful", to: String = "lucas@ln.tips", fee: Int64 = 9) -> HistoryTransaction {
        var row = HomeRulesTests.row("movement-2", type: "Lnurl", amount: 4_842, direction: "outgoing", status: status,
                                     destination: to, effective: -(4_842 + fee), offchainFee: fee)
        row.subsystemName = "bark.lightning_send"
        row.sentTo = [.init(destination: to, amount_sat: 4_842, paymentMethod: "lightning-address")]
        row.outputVtxos = []
        row.exitedVtxos = []
        if status == "successful" { row.preimage = preimage }
        return row
    }

    static func lightningReceive() -> HistoryTransaction {
        var row = HomeRulesTests.row("movement-3", type: "Bolt11", amount: 2_100, kind: "lightning-receive")
        row.receivedOn = [.init(destination: ActivityMappingTests.blankInvoice, amount_sat: 2_100, paymentMethod: "invoice")]
        row.preimage = preimage
        row.paymentHash = ActivityMappingTests.blankHash
        return row
    }

    static func onchainSend(status: String = "successful", broadcast: Bool = true) -> HistoryTransaction {
        var row = HomeRulesTests.row("movement-8", type: "Onchain", amount: 53_000, direction: "outgoing", status: status,
                                     kind: "send-onchain", destination: "bcrt1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
                                     effective: -55_550, offchainFee: 2_550)
        row.sentTo = [.init(destination: row.destination!, amount_sat: 53_000, paymentMethod: "bitcoin")]
        row.txid = txid
        row.metadataJson = broadcast ? #"{"offboard_txid":"\#(txid)"}"# : "{}"
        return row
    }

    @Test func statusLinesFollowTheRecord() {
        func status(_ row: HistoryTransaction) -> String { ActivityDetail(row, ownDomain: Self.own).status }
        #expect(status(Self.lightningSend()) == "Sent")
        #expect(status(Self.lightningReceive()) == "Received")
        #expect(status(Self.lightningSend(status: "pending")) == "Sending · still finding a route")
        #expect(status(Self.onchainSend(status: "pending")) == "Sending · waiting for confirmations")
        #expect(status(Self.onchainSend(status: "pending", broadcast: false)) == "Sending · waiting for an Ark round")
        #expect(status(HomeRulesTests.row(type: "Onchain", kind: "onboard")) == "Moved to Ark")
        #expect(status(HomeRulesTests.row(type: "Onchain", direction: "outgoing", status: "pending", kind: "exit")) == "Exiting")
        #expect(status(HomeRulesTests.deposit("onchain-wallet-a", 100_000, confirmed: false)) == "Arriving")
        // Unknown status keeps today's "Recorded".
        #expect(status(HomeRulesTests.row(status: nil)) == "Recorded")

        var failed = Self.lightningSend(status: "failed")
        failed.effectiveBalanceSat = 0
        let early = ActivityDetail(failed, ownDomain: Self.own)
        #expect(early.status == "Failed · nothing left your wallet" && early.tone == .failure && early.unsuccessful)
        failed.outputVtxos = ["revoked:0"]
        #expect(status(failed) == "Refunded · back in your wallet")
        var canceled = Self.lightningSend(status: "canceled", fee: 0)
        canceled.effectiveBalanceSat = 0
        #expect(status(canceled) == "Canceled · nothing left your wallet")
        #expect(ActivityDetail(canceled, ownDomain: Self.own).tone == .muted)

        var renewal = HomeRulesTests.row(type: "Arkoor", amount: 45, direction: "outgoing", kind: "refresh", effective: -45, offchainFee: 45)
        renewal.inputVtxos = ["a:0", "b:1", "c:2"]
        let renewed = ActivityDetail(renewal, ownDomain: Self.own)
        #expect(renewed.status == "Renewed {n} coins" && renewed.renewedCoins == 3 && renewed.rail == .arkRound)
        #expect(renewed.fee == .amount(45) && renewed.total == nil)
    }

    @Test func rowsApplyOnlyWhenTheyMeanSomething() {
        let sent = ActivityDetail(Self.lightningSend(), ownDomain: Self.own)
        #expect(sent.to == "lucas@ln.tips" && !sent.toIsAddress && sent.rail == .lightning)
        #expect(sent.fee == .amount(9) && sent.total == 4_851)
        #expect(sent.proof == Self.preimage && sent.chainTxid == nil && sent.blockTxid == nil)

        // A free Ark send says "Free" and has no Total.
        var ark = HomeRulesTests.row(type: "Arkoor", amount: 500, direction: "outgoing", destination: "tark1recipient", offchainFee: 0)
        ark.sentTo = [.init(destination: "tark1recipient", amount_sat: 500, paymentMethod: "ark")]
        let free = ActivityDetail(ark, ownDomain: Self.own)
        #expect(free.fee == .free && free.total == nil && free.to == "tark1recipient" && free.toIsAddress && free.rail == .ark)

        // Pending Lightning: "Up to"; failed: "None" and no proof.
        #expect(ActivityDetail(Self.lightningSend(status: "pending"), ownDomain: Self.own).fee == .upTo(9))
        let failed = ActivityDetail(Self.lightningSend(status: "failed"), ownDomain: Self.own)
        #expect(failed.fee == .nothing && failed.proof == nil && failed.total == nil)

        // An invoice names nobody; its description isn't repeated as a note.
        var invoice = Self.lightningSend(to: ActivityMappingTests.coffeeInvoice)
        invoice.type = "Bolt11"
        invoice.sentTo = [.init(destination: ActivityMappingTests.coffeeInvoice, amount_sat: 4_842, paymentMethod: "invoice")]
        invoice.paymentDescription = "Coffee Collective"
        let titled = ActivityDetail(invoice, ownDomain: Self.own)
        #expect(titled.to == nil && titled.note == nil)
        invoice.sentTo = [.init(destination: "lno1offer", amount_sat: 4_842, paymentMethod: "offer")]
        #expect(ActivityDetail(invoice, ownDomain: Self.own).rail == .lightningOffer)

        // A receive's preimage is no proof of payment.
        #expect(ActivityDetail(Self.lightningReceive(), ownDomain: Self.own).proof == nil)

        // On-chain: the transaction ID with the explorer, the block row while it waits.
        let chain = ActivityDetail(Self.onchainSend(status: "pending"), ownDomain: Self.own)
        #expect(chain.chainTxid == Self.txid && chain.blockTxid == Self.txid && chain.rail == .onchain)
        #expect(chain.fee == .amount(2_550) && chain.total == 55_550)
        #expect(ActivityDetail(Self.onchainSend(), ownDomain: Self.own).blockTxid == nil)
        #expect(ActivityDetail(Self.onchainSend(status: "pending", broadcast: false), ownDomain: Self.own).blockTxid == nil)
        // BDK's balance change already includes its fee: no Total.
        var walletSend = HomeRulesTests.deposit("onchain-wallet-b", -18_760, confirmed: false)
        walletSend.txid = Self.txid
        walletSend.hasOnchainFee = true
        walletSend.onchainFeeSat = 280
        let wallet = ActivityDetail(walletSend, ownDomain: Self.own)
        #expect(wallet.fee == .network(280) && wallet.total == nil && wallet.blockTxid == Self.txid)
        #expect(ActivityDetail.requiredConfirmations(walletSend) == 1)
        #expect(ActivityDetail.requiredConfirmations(Self.onchainSend(), variant: .mainnet) == 2)
    }

    @Test func shareReceiptsCarryOnlyTheReceiptFields() {
        let english = Locale(identifier: "en")
        let send = ActivityDetail.receipt(Self.lightningSend(), ownDomain: Self.own, unit: .bip177, locale: english, date: "Yesterday at 6:05 PM")
        #expect(send.contains("Sent −") && send.contains("4,842") && send.contains("Yesterday at 6:05 PM"))
        #expect(send.contains("To: lucas@ln.tips") && send.contains("Via: Lightning") && send.contains("Proof of payment: \(Self.preimage)"))

        let receive = ActivityDetail.receipt(Self.lightningReceive(), ownDomain: Self.own, unit: .bip177, locale: english, date: "Today")
        #expect(receive.contains("Received +") && receive.contains("Via: Lightning"))
        #expect(!receive.contains(Self.preimage), "never a receive's preimage")
        #expect(!receive.contains(ActivityMappingTests.blankInvoice) && !receive.contains(ActivityMappingTests.blankHash))

        var chain = Self.onchainSend()
        chain.txHex = "0200000000010" + String(repeating: "f", count: 40)
        let onchain = ActivityDetail.receipt(chain, ownDomain: Self.own, unit: .bip177, locale: english, date: "Oct 16")
        #expect(onchain.contains("Transaction ID: \(Self.txid)") && onchain.contains("Via: On-chain")
                && onchain.contains("To: bcrt1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"))
        #expect(!onchain.contains("0200000000010"), "never the raw transaction")
        #expect(!onchain.contains("movement-8") && !onchain.contains("{"), "never Details or metadata")

        let failed = ActivityDetail.receipt(Self.lightningSend(status: "failed"), ownDomain: Self.own, unit: .bip177, locale: english, date: "Today")
        #expect(!failed.contains("Proof of payment"))
    }
}
