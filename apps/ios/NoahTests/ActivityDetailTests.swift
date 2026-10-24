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

    @Test func anOffboardsTotalIsItsDebitNotTheFeeTwice() {
        // Offboarding 100,000 with a 1,000 fee: 99,000 reaches the address and bark records a 100,000 debit,
        // which is also the row's amount.
        var offboard = HomeRulesTests.row("movement-9", type: "Onchain", amount: 100_000, direction: "outgoing",
                                          kind: "offboard", effective: -100_000, offchainFee: 1_000)
        offboard.sentTo = [.init(destination: "bcrt1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh", amount_sat: 99_000, paymentMethod: "bitcoin")]
        let detail = ActivityDetail(offboard, ownDomain: Self.own)
        #expect(detail.fee == .amount(1_000))
        #expect(detail.total == nil, "the amount already is the debit")
        // A send-onchain records the destination's amount, so its total is the debit.
        var send = offboard
        send.movementKind = "send-onchain"
        send.amount = 99_000
        #expect(ActivityDetail(send, ownDomain: Self.own).total == 100_000)
        // No debit on record, no guessed total.
        send.effectiveBalanceSat = nil
        #expect(ActivityDetail(send, ownDomain: Self.own).total == nil)
    }

    @Test func settledLightningFeesComeFromTheBalanceChange() {
        // A retried fixed-amount invoice keeps its first attempt's fee field (50); the balance change says 12.
        var fixed = Self.lightningSend(to: ActivityMappingTests.coffeeInvoice, fee: 12)
        fixed.type = "Bolt11"
        fixed.sentTo = [.init(destination: ActivityMappingTests.coffeeInvoice, amount_sat: 4_200, paymentMethod: "invoice")]
        fixed.amount = 4_200
        fixed.effectiveBalanceSat = -4_212
        fixed.offchainFeeSat = 50
        var detail = ActivityDetail(fixed, ownDomain: Self.own)
        #expect(detail.fee == .amount(12) && detail.total == 4_212)
        // An amountless invoice's numbers prove nothing: no Fee and no Total, never "Free".
        var amountless = fixed
        amountless.sentTo = [.init(destination: ActivityMappingTests.amountlessInvoice, amount_sat: 4_200, paymentMethod: "invoice")]
        #expect(!SendReconciliation.fixesAmount(ActivityMappingTests.amountlessInvoice))
        detail = ActivityDetail(amountless, ownDomain: Self.own)
        #expect(detail.fee == nil && detail.total == nil)
        // A proven zero is free.
        var zero = Self.lightningSend(fee: 0)
        zero.offchainFeeSat = 7
        #expect(ActivityDetail(zero, ownDomain: Self.own).fee == .free)
    }

    @Test func anAmountlessRetryShowsAndSharesOnlyItsProvenDebit() {
        // Attempt 1 failed at 1,000; the retry paid 2,000 (+80). Bark kept attempt 1's destination amount.
        var paid = Self.lightningSend(to: ActivityMappingTests.amountlessInvoice, fee: 80)
        paid.type = "Bolt11"
        paid.sentTo = [.init(destination: ActivityMappingTests.amountlessInvoice, amount_sat: 1_000, paymentMethod: "invoice")]
        paid.amount = 1_000
        paid.effectiveBalanceSat = -2_080
        #expect(paid.paidAmountlessInvoice && paid.shownAmount == 2_080)
        let english = Locale(identifier: "en")
        let row = HomeActivityRow(paid, ownDomain: Self.own)
        #expect(row.amount(unit: .sats, hidden: false, locale: english) == "−2,080 sats")
        var receipt = ActivityDetail.receipt(paid, ownDomain: Self.own, unit: .sats, locale: english, date: "Today")
        #expect(receipt.hasPrefix("Sent −2,080 sats") && !receipt.contains("1,000"))
        #expect(ActivityDetail(paid, ownDomain: Self.own).fee == nil && ActivityDetail(paid, ownDomain: Self.own).total == nil)
        // No proven debit: "Sent" with no amount, never attempt 1's.
        paid.effectiveBalanceSat = nil
        #expect(paid.shownAmount == nil)
        #expect(HomeActivityRow(paid, ownDomain: Self.own).amount(unit: .sats, hidden: false, locale: english).isEmpty)
        receipt = ActivityDetail.receipt(paid, ownDomain: Self.own, unit: .sats, locale: english, date: "Today")
        #expect(receipt.hasPrefix("Sent\nToday") && !receipt.contains("1,000"))
        // A fixed-amount invoice keeps its own amount.
        #expect(Self.lightningSend().shownAmount == 4_842)
    }

    @Test func theFiatLinePricesTheShownAmount() throws {
        let english = Locale(identifier: "en_US")
        let now = try #require(HistoryRules.parseDate("2026-10-23T12:00:00Z"))
        var paid = Self.lightningSend(to: ActivityMappingTests.amountlessInvoice, fee: 80)
        paid.date = "2026-10-20T12:00:00.000Z"
        paid.sentTo = [.init(destination: ActivityMappingTests.amountlessInvoice, amount_sat: 1_000, paymentMethod: "invoice")]
        paid.amount = 1_000
        paid.effectiveBalanceSat = -2_080
        paid.btcPrice = 100_000
        // 2,080 sats at the payment's own price, never attempt 1's 1,000.
        #expect(ActivityDetail.fiat(paid, current: nil, currency: "USD", locale: english, now: now) == "$2.08 at the time")
        paid.effectiveBalanceSat = nil
        #expect(ActivityDetail.fiat(paid, current: 90_000, currency: "USD", locale: english, now: now) == nil)
        // Today's rows use the current price; failed ones show none.
        var today = Self.lightningSend()
        today.date = "2026-10-23T09:00:00.000Z"
        #expect(ActivityDetail.fiat(today, current: 100_000, currency: "USD", locale: english, now: now) == "$4.84")
        #expect(ActivityDetail.fiat(Self.lightningSend(status: "failed"), current: 100_000, currency: "USD", locale: english, now: now) == nil)
    }

    @Test func receivesShowARecordedFeeButNeverFree() {
        var receive = Self.lightningReceive()
        receive.offchainFeeSat = 104
        #expect(ActivityDetail(receive, ownDomain: Self.own).fee == .amount(104))
        receive.offchainFeeSat = 0
        #expect(ActivityDetail(receive, ownDomain: Self.own).fee == nil)
        #expect(ActivityDetail(receive, ownDomain: Self.own).total == nil)
    }

    @Test func aCoinIDIsNeverATransactionID() {
        // Before an offboard's transaction exists, today's txid falls back to an input coin's ID.
        var offboard = Self.onchainSend(status: "pending", broadcast: false)
        offboard.txid = String(repeating: "ef", count: 32) + ":1"
        offboard.inputVtxos = [offboard.txid!]
        #expect(offboard.chainTxid == nil)
        let detail = ActivityDetail(offboard, ownDomain: Self.own)
        #expect(detail.chainTxid == nil && detail.blockTxid == nil)
        let receipt = ActivityDetail.receipt(offboard, ownDomain: Self.own, unit: .bip177, locale: Locale(identifier: "en"), date: "Today")
        #expect(!receipt.contains("Transaction ID") && !receipt.contains("efef"))
        // A board names its transaction through its anchor; the wallet's rows through their own txid.
        var board = HomeRulesTests.row(type: "Onchain", kind: "onboard")
        board.chainAnchor = Self.txid + ":0"
        #expect(board.chainTxid == Self.txid)
        var wallet = HomeRulesTests.deposit("onchain-wallet-a", 1_000, confirmed: true)
        wallet.txid = Self.txid
        #expect(wallet.chainTxid == Self.txid)
    }

    @Test func invoiceBearingLightningAddressSendsKeepTheirRecipient() {
        // Send pays a Lightning address through an invoice and records the address in its repeat metadata.
        var send = Self.lightningSend(to: ActivityMappingTests.coffeeInvoice)
        send.sentTo = [.init(destination: ActivityMappingTests.coffeeInvoice, amount_sat: 4_842, paymentMethod: "invoice")]
        send.repeatPayment = RepeatPayment(destination: "lucas@ln.tips", comment: "", amountMode: "SATS", amountInput: "4842")
        send.paymentDescription = "Coffee Collective"
        #expect(ActivityDetail(send, ownDomain: Self.own).to == "lucas@ln.tips")
        let row = HomeActivityRow(send, ownDomain: Self.own)
        #expect(row.title == "lucas@ln.tips" && row.avatar == .initial("l"))
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

/// The detail's reads in a wide pane that moves between selections.
@MainActor
struct ActivityDetailReadsTests {
    /// A chain-status read that waits until the test releases it.
    final class Gate: @unchecked Sendable {
        let (stream, continuation) = AsyncStream<ChainTransactionStatus>.makeStream()
    }

    static func pending(_ id: String, txid: String) -> HistoryTransaction {
        var row = ActivityDetailTests.onchainSend(status: "pending")
        row.id = id
        row.txid = txid
        row.metadataJson = #"{"offboard_txid":"\#(txid)"}"#
        return row
    }

    @Test func aLateReadNeverLandsOnTheNextSelection() async throws {
        let gate = Gate()
        let slow = String(repeating: "aa", count: 32), fast = String(repeating: "bb", count: 32)
        let history = HistoryModel(dependencies: .init(
            load: { [] }, sync: {}, changes: { AsyncThrowingStream { $0.finish() } }, blockTime: { _ in nil },
            price: { _, _ in nil }, currentPrice: { _ in 100_000 }, chainTip: { 120 },
            chainStatus: { txid in
                guard txid == slow else { return .inMempool }
                for await status in gate.stream { return status }
                return nil
            }))
        let reads = ActivityDetailReads()
        let a = Self.pending("movement-a", txid: slow), b = Self.pending("movement-b", txid: fast)
        // A's read is still out when B is selected.
        let first = Task { await reads.load(a, blockTxid: slow, currency: "USD", history: history) }
        while reads.price(for: a.id) == nil { await Task.yield() }
        // Not cancelled: the record check alone keeps A's answer off B.
        await reads.load(b, blockTxid: fast, currency: "USD", history: history)
        #expect(reads.status(for: b.id) == .inMempool && reads.tip(for: b.id) == 120)
        // A finishes late: B keeps its own state, and nothing reads as A's.
        gate.continuation.yield(.confirmed(100))
        await first.value
        #expect(reads.status(for: b.id) == .inMempool)
        #expect(reads.status(for: a.id) == nil && reads.price(for: a.id) == nil)
        // Selecting another payment clears what was read for the last one.
        reads.select("movement-c")
        #expect(reads.status(for: b.id) == nil && reads.price(for: "movement-c") == nil)
    }

    /// A cancelled read writes nothing even when the selection didn't change.
    @Test func aCancelledReadWritesNothing() async throws {
        let gate = Gate()
        let slow = String(repeating: "cc", count: 32)
        let history = HistoryModel(dependencies: .init(
            load: { [] }, sync: {}, changes: { AsyncThrowingStream { $0.finish() } }, blockTime: { _ in nil },
            price: { _, _ in nil }, currentPrice: { _ in 100_000 }, chainTip: { 120 },
            chainStatus: { _ in for await status in gate.stream { return status }; return nil }))
        let reads = ActivityDetailReads()
        let a = Self.pending("movement-a", txid: slow)
        let task = Task { await reads.load(a, blockTxid: slow, currency: "USD", history: history) }
        while reads.price(for: a.id) == nil { await Task.yield() }
        task.cancel()
        gate.continuation.yield(.confirmed(100))
        await task.value
        #expect(reads.status(for: a.id) == nil)
    }
}
