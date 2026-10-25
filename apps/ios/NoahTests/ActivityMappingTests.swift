import BarkBoltFFI
import Foundation
import Testing
@testable import Noah

/// activity.md "Data": the shared mapping changes Home and Activity both read.
struct ActivityMappingTests {
    /// Real regtest vectors (LND `addinvoice`, CLN `offer`).
    static let coffeeInvoice = "lnbcrt42u1p4dh5n8pp5nduftq5kz8use6c96kg3cn6tptt5l0zuvvljzs3w62vllq89k8psdqugdhkven9v5syxmmvd3jkxarfwejscqzzsxqyz5vqsp59g6mjt0jy89fkm97uegqz97ufp3ljywtytkvazr7d8mc029jpf4q9qxpqysgqqdwrmnkfkczec43d4dykeszuhw86zk0dydlww5ertem9e3u7uml96dvzz9j4ujs65x8tz6w0qngk6fgnh5xufe9gh9jj8w8e49u9gecp237ty0"
    static let coffeeHash = "9b7895829611f90ceb05d5911c4f4b0ad74fbc5c633f21422ed299ff80e5b1c3"
    static let blankInvoice = "lnbcrt15u1p4dh5n4pp5hnz4qzuau25lzfqguj3pnj26tsx3vr2l70ezl8tl2mur9u7m9glsdqqcqzzsxqyz5vqsp52x9dh5jexyvvkvmvyla8uyk3nlmdp8lvs3xzyzg4t7ak25dkzq3q9qxpqysgqhnz7hmsp7wvretnmpedj59kp7curf4y4msy2lurk458m62smxmpjmgh7pn9gslyuqzs5mmfhqwpmzs0szyr4s948xrm4qsfer9jv7lcq8khane"
    static let blankHash = "bcc5500b9de2a9f12408e4a219c95a5c0d160d5ff3f22f9d7f56f832f3db2a3f"
    static let amountlessInvoice = "lnbcrt1p4dhmzdpp5w9va38sfcqxrfa2ttyfcwntlkeg6r7urjkux63064yt2wmtrleksdqqcqzzsxqyz5vqsp57culhw0h3twrhmqpnpsh7xjrxg7k57fnmcksaqrkpeqctr83kk9s9qxpqysgq5j5ng3pc7zpd48p2g8aja4z4yfrw7zm27qj0wcc0z95jqr2qjsf4rswuynluqg7xnqrwk8g8csrn27zsx9lt4z29al7exjc93jr3hasqxqqm2z"
    static let offer = "lno1qgsqvgnwgcg35z6ee2h3yczraddm72xrfua9uve2rlrm9deu7xyfzrcgqve94gq2pafkzar0wd5xjgznw3shg6t0dctzzqmlgvmm4ya4lfjfyga8uqffr66xe7z00w6d2yjca7pcx39fefwmms"

    static func movement(_ subsystem: String, _ kind: String, id: UInt32 = 7, status: String = "successful",
                         intended: Int64, effective: Int64, fee: UInt64 = 0, metadata: String = "{}",
                         sent: [(String, String, UInt64)] = [], received: [(String, String, UInt64)] = [],
                         inputs: [String] = [], outputs: [String] = [], exited: [String] = [],
                         paymentHash: String? = nil) -> Movement {
        func destination(_ value: (String, String, UInt64)) -> MovementDestination {
            .init(paymentMethod: .init(methodType: value.0, value: value.1), amountSats: value.2)
        }
        return Movement(id: id, status: status, subsystemName: subsystem, subsystemKind: kind, metadataJson: metadata,
                        intendedBalanceSats: intended, effectiveBalanceSats: effective, offchainFeeSats: fee,
                        sentToAddresses: sent.map(destination), receivedOnAddresses: received.map(destination),
                        inputVtxoIds: inputs, outputVtxoIds: outputs, exitedVtxoIds: exited,
                        createdAt: "2026-10-23T09:12:00Z", updatedAt: "2026-10-23T09:12:00Z", completedAt: nil,
                        paymentHash: paymentHash, lightningInvoice: nil, lightningOffer: nil)
    }

    static func lightningSend(status: String, effective: Int64, fee: UInt64, outputs: [String] = [], exited: [String] = []) -> Movement {
        movement("bark.lightning_send", "send", status: status, intended: -4_200, effective: effective, fee: fee,
                 sent: [("invoice", coffeeInvoice, 4_200)], inputs: ["in:0"], outputs: outputs, exited: exited)
    }

    @Test func failedAndRefundedSendsAreShownButNotExported() {
        // Bark 0.8 fails a send before locking coins: no change, the planned fee kept, no new coins.
        let early = Self.lightningSend(status: "failed", effective: 0, fee: 15)
        // Locked, then revoked: the server's signature gives the coins back as new ones.
        let refunded = Self.lightningSend(status: "failed", effective: 0, fee: 0, outputs: ["change:0", "revoked:0"])
        // Revocation failed and the coins went to an exit: not "back in your wallet".
        let exited = Self.lightningSend(status: "failed", effective: 0, fee: 0, outputs: ["change:0"], exited: ["htlc:0"])
        for movement in [early, refunded, exited] { #expect(HistoryMapping.include(movement)) }

        let rows = [early, refunded, exited].map { HistoryMapping.transaction($0) }
        #expect(rows.map(\.isRefunded) == [false, true, false])
        let statuses = rows.map { HomeActivityRow($0, ownDomain: "noahwallet.io").status }
        #expect(statuses == [.failed(nothingLeft: true), .refunded, .failed(nothingLeft: false)])
        #expect(statuses.allSatisfy { $0.isFailure && $0.isUnsuccessful && !$0.isPending })

        // A failed board stays exported, as today; failed sends never were.
        let board = HistoryMapping.transaction(Self.movement("bark.board", "board", status: "failed", intended: 50_000, effective: 0))
        let succeeded = HistoryMapping.transaction(Self.lightningSend(status: "successful", effective: -4_212, fee: 12))
        let csv = HistoryCSV(transactions: rows + [board, succeeded], currency: "USD").content
        #expect(csv.split(separator: "\n").count == 3)
        #expect(csv.contains("movement-7,") && csv.contains("Board,Failed"))
        #expect(!csv.contains("Lightning,Failed"))
    }

    @Test func feePayingRenewalsAreArkRenewedAndFreeOnesStayHidden() {
        let paid = Self.movement("bark.round", "refresh", intended: 0, effective: -45, fee: 45,
                                 inputs: ["a:0", "b:1", "c:2"], outputs: ["d:0"])
        let free = Self.movement("bark.round", "refresh", intended: 0, effective: 0, inputs: ["a:0"], outputs: ["d:0"])
        #expect(HistoryMapping.include(paid))
        #expect(!HistoryMapping.include(free))

        let row = HistoryMapping.transaction(paid)
        #expect(row.type == "Arkoor" && row.movementKind == "refresh" && row.isRenewal)
        #expect(row.amount == 45 && row.direction == "outgoing" && row.inputVtxos?.count == 3)
        #expect(HistoryFilter.ark.includes(row) && !HistoryFilter.onchain.includes(row))
        let shown = HomeActivityRow(row, ownDomain: "noahwallet.io")
        #expect(shown.title == "Renewed" && !shown.titleIsVerbatim && shown.kind == .ark && shown.avatar == .renewal)
        #expect(shown.sign == "−" && shown.status == .done)
    }

    @Test func recurringRowsJoinTheJournalAndAMissStaysAnOrdinarySend() {
        let schedule = HistoryTransaction.Recurring(scheduleID: "s1", name: "Savings", interval: .init(unit: "month", every: 1))
        let ark = HistoryMapping.transaction(Self.movement("bark.arkoor", "send", id: 3, intended: -10_000, effective: -10_000,
                                                           sent: [("ark", "tark1recipient", 10_000)]))
        let lightning = HistoryMapping.transaction(Self.movement("bark.lightning_send", "send", id: 4, intended: -2_000, effective: -2_000,
                                                                 sent: [("offer", Self.offer, 2_000)], paymentHash: "ab12"))
        let incoming = HistoryMapping.transaction(Self.movement("bark.arkoor", "receive", id: 3, intended: 500, effective: 500,
                                                                received: [("ark", "tark1me", 500)]))
        let links = [HistoryMapping.RecurringLink(schedule: schedule, movementID: 3, paymentHash: nil),
                     HistoryMapping.RecurringLink(schedule: schedule, movementID: nil, paymentHash: "ab12")]
        let joined = HistoryMapping.joinRecurring([ark, lightning, incoming], links: links)
        #expect(joined[0].recurring == schedule && joined[1].recurring == schedule)
        #expect(joined[2].recurring == nil, "only sends are recurring")

        let row = HomeActivityRow(joined[0], ownDomain: "noahwallet.io")
        #expect(row.title == "Savings" && row.titleIsVerbatim && row.kind == .recurring && row.avatar == .recurring)

        // A miss: another movement, another hash, or no journal at all.
        let missed = HistoryMapping.joinRecurring([ark, lightning], links: [.init(schedule: schedule, movementID: 99, paymentHash: "ff")])
        #expect(missed.allSatisfy { $0.recurring == nil })
        #expect(HistoryMapping.joinRecurring([ark], links: []) == [ark])
        #expect(HomeActivityRow(missed[0], ownDomain: "noahwallet.io").title == "Ark")
    }

    @Test func invoiceAndOfferDescriptionsTitleTheirRows() {
        let send = HistoryMapping.transaction(Self.lightningSend(status: "successful", effective: -4_212, fee: 12))
        #expect(send.paymentDescription == "Coffee Collective")
        var row = HomeActivityRow(send, ownDomain: "noahwallet.io")
        #expect(row.title == "Coffee Collective" && row.titleIsVerbatim && row.kind == .lightning && row.avatar == .lightning)

        let offer = HistoryMapping.transaction(Self.movement("bark.lightning_send", "send", intended: -3_300, effective: -3_310, fee: 10,
                                                             sent: [("offer", Self.offer, 3_300)]))
        #expect(offer.paymentDescription == "Satoshi Station")
        #expect(HomeActivityRow(offer, ownDomain: "x").title == "Satoshi Station")

        // No description: "Sent" or "Received", never empty.
        let receive = HistoryMapping.transaction(Self.movement("bark.lightning_receive", "receive", intended: 1_500, effective: 1_500,
                                                               received: [("invoice", Self.blankInvoice, 1_500)]))
        #expect(receive.paymentDescription == nil)
        row = HomeActivityRow(receive, ownDomain: "x")
        #expect(row.title == "Received" && !row.titleIsVerbatim && row.kind == .lightning)
        var blank = send
        blank.paymentDescription = nil
        #expect(HomeActivityRow(blank, ownDomain: "x").title == "Sent")
        // A Lightning address still names the recipient.
        var address = send
        address.type = "Lnurl"
        address.destination = "bob@getalby.com"
        #expect(HomeActivityRow(address, ownDomain: "x").title == "bob@getalby.com")
        #expect(PaymentEncoding.offerDescription("lno1qqqq") == nil && PaymentEncoding.offerDescription("lnbc1") == nil)
    }

    @Test func paymentHashesDecodeFromTheStoredInvoice() {
        #expect(HistoryMapping.transaction(Self.lightningSend(status: "successful", effective: -4_212, fee: 12)).paymentHash == Self.coffeeHash)
        let receive = Self.movement("bark.lightning_receive", "receive", intended: 1_500, effective: 1_500,
                                    received: [("invoice", Self.blankInvoice, 1_500)])
        #expect(HistoryMapping.transaction(receive).paymentHash == Self.blankHash)
        // The SDK's own hash wins (it reads the movement's metadata first).
        let recorded = Self.movement("bark.lightning_send", "send", intended: -4_200, effective: -4_212, fee: 12,
                                     sent: [("invoice", Self.coffeeInvoice, 4_200)], paymentHash: "00ff")
        #expect(HistoryMapping.transaction(recorded).paymentHash == "00ff")
    }

    @Test func recordsWithoutMetadataMapAsBefore() throws {
        // React Native and older records: empty metadata, no SDK-computed fields, no invoice.
        let old = Self.movement("bark.arkoor", "send", intended: -7_000, effective: -7_000, metadata: "",
                                sent: [("ark", "tark1recipient", 7_000)])
        let row = HistoryMapping.transaction(old)
        #expect(row.paymentHash == nil && row.paymentDescription == nil && row.recurring == nil && !row.isRefunded)
        #expect(row.type == "Arkoor" && row.amount == 7_000)
        let json = try #require(String(data: JSONEncoder().encode(row), encoding: .utf8))
        #expect(!json.contains("paymentHash") && !json.contains("paymentDescription") && !json.contains("recurring"))
        #expect(HomeActivityRow(row, ownDomain: "x").title == "Ark")
    }

    /// An exit's fee children are named by the txids bark recorded in its states, history included,
    /// never by amount; a send for the same amount stays a plain on-chain row.
    @Test func exitFeeChildrenShowAsExitFees() {
        typealias E = ExitProgressTests
        let earlier = E.processing([E.tx("root", "awaiting-confirmation", child: "child-1")])
        let current = E.processing([E.tx("root", "confirmed", height: 101, child: "child-2"), E.tx("leaf", "awaiting-cpfp-broadcast")])
        let fees = HistoryMapping.exitFees([E.exit("Processing", current, history: [earlier], id: "vtxo-a"),
                                            E.exit("Start", .init(kind: "start", tip_height: 100), id: "vtxo-b")])
        #expect(fees == ["child-1": "vtxo-a", "child-2": "vtxo-a"])

        func chain(_ txid: String) -> HistoryTransaction {
            HistoryMapping.transaction(WalletTransaction(txid: txid, txHex: "", onchainFeeSats: nil, balanceChangeSats: -1_234,
                                                         confirmation: .init(height: 101, hash: "h101"), isCpfp: true),
                                       exitFeeFor: fees[txid])
        }
        let fee = chain("child-2"), plain = chain("other")
        let row = HomeActivityRow(fee, ownDomain: "")
        #expect(fee.exitFeeFor == "vtxo-a" && fee.displayLabel == "Exit fee")
        #expect(row.title == "Exit fee" && !row.titleIsVerbatim && row.avatar == .exit && row.kind == .onchain)
        #expect(row.sign == "−" && row.sats == 1_234 && row.status == .done)
        let detail = ActivityDetail(fee, ownDomain: "")
        #expect(detail.status == "Sent" && detail.rail == .onchain && detail.fee == nil && detail.chainTxid == "child-2")
        let other = HomeActivityRow(plain, ownDomain: "")
        #expect(plain.exitFeeFor == nil && other.title == "Onchain" && other.avatar == .chain)
    }
}

/// The recurring journal side of the join (`RecurringExecutor.historyLinks`).
@MainActor
struct ActivityRecurringJoinTests {
    @Test func anOccurrencesOwnSuccessJoins() async throws {
        let h = try RecurringExecutorTests.Harness(); let schedule = try h.add(); h.arkMovementID = 42
        defer { try? FileManager.default.removeItem(at: h.directory) }
        await h.executor.execute()
        let links = await h.executor.historyLinks()
        #expect(links.map(\.movementID) == [42])
        #expect(links.first?.schedule == .init(scheduleID: schedule.id, name: schedule.label, interval: schedule.interval))
    }

    /// An "already paid" outcome can name an earlier manual payment: it must not read as recurring.
    @Test func alreadyPaidOutcomesDoNotJoin() async throws {
        let h = try RecurringExecutorTests.Harness(); let schedule = try h.add(); h.ambiguous = true
        defer { try? FileManager.default.removeItem(at: h.directory) }
        await h.executor.execute()
        h.resolution = .alreadyPaid(.init(amount: schedule.amountSat, movementID: 7, proof: .submitReturnedArkSuccess))
        await h.executor.checkStatus(schedule.id)
        #expect(try h.journal.load()[schedule.id]?.terminal?.outcome == .alreadyPaid)
        #expect(await h.executor.historyLinks().isEmpty)
    }
}
