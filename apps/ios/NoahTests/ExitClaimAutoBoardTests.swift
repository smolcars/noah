import BarkBoltFFI
import Foundation
@testable import Noah
import Testing

/// Auto-board after an exit claim to this phone: the claimed coins stay on-chain. Only a real
/// deposit is offered, prepared by amount; with only the claim on-chain there's no prompt; once the
/// claimed coin is spent it no longer counts.
struct ExitClaimAutoBoardTests {
    static let claim = "\(ExitClaimExecutionTests.claimTxid):0"

    @Test func aClaimsTxidIsKnownBeforeItsSent() {
        #expect(BitcoinTransaction.txid(hex: ExitClaimExecutionTests.claimTransaction) == ExitClaimExecutionTests.claimTxid)
        // bitcoind's weight for the same transaction.
        #expect(BitcoinTransaction.weight(hex: ExitClaimExecutionTests.claimTransaction) == 470)
        for broken in ["", "zz", String(ExitClaimExecutionTests.claimTransaction.dropLast(2)), ExitClaimExecutionTests.claimTransaction + "00"] {
            #expect(BitcoinTransaction.txid(hex: broken) == nil)
        }
    }

    @Test func heldValueIsTheUnspentConfirmedClaimOnly() {
        let utxos: [OnchainUtxo] = [
            .local(outpoint: .init(txid: ExitClaimExecutionTests.claimTxid, vout: 0), amountSats: 58_705, confirmationHeight: 1_278),
            .local(outpoint: .init(txid: "deposit", vout: 0), amountSats: 40_000, confirmationHeight: 1_280),
            .local(outpoint: .init(txid: "pending-claim", vout: 0), amountSats: 9_000, confirmationHeight: nil),
        ]
        #expect(AutoBoardFunding.held([Self.claim, "pending-claim:0"], utxos: utxos) == 58_705)
        // Spent: gone from the wallet's coins, so nothing is held back any more.
        #expect(AutoBoardFunding.held([Self.claim], utxos: Array(utxos.dropFirst())) == 0)
    }

    actor Board {
        let balance: UInt64
        /// The claimed coin's value; coin selection leaves it out when it's excluded.
        let claimed: UInt64
        /// A coin selection that spends the claimed coin anyway.
        let ignoresExclusions: Bool
        let chainFee: UInt64
        var amounts: [UInt64] = []
        var exclusions: [Set<String>] = []
        var discarded: [UUID] = []
        init(balance: UInt64, claimed: UInt64 = 0, ignoresExclusions: Bool = false, chainFee: UInt64 = 150) {
            self.balance = balance; self.claimed = claimed; self.ignoresExclusions = ignoresExclusions; self.chainFee = chainFee
        }
        func prepare(_ amount: UInt64, excluding: Set<String> = []) throws -> OnchainQuote {
            amounts.append(amount)
            exclusions.append(excluding)
            let spendsClaim = claimed > 0 && (ignoresExclusions || !excluding.contains(ExitClaimAutoBoardTests.claim))
            // bark drains only what isn't excluded; with nothing else it's the usual InsufficientFunds.
            guard amount > 0 || balance - (spendsClaim ? 0 : claimed) > chainFee else {
                throw BarkError(message: "insufficient", code: .insufficientFunds)
            }
            var quote = BoardPreparedTests.quote
            quote.id = UUID(); quote.automatic = true; quote.confirmed = balance
            quote.amount = amount == 0 ? balance - (spendsClaim ? 0 : claimed) - chainFee : amount
            quote.chainFee = chainFee; quote.fee = 330; quote.feeRate = 1
            quote.prepared = try .init(PreparedBoard(id: quote.id.uuidString, txid: "board", grossSats: quote.amount, netSats: quote.net,
                                                     arkFeeSats: 330, onchainFeeSats: chainFee, vsize: chainFee, inputs: (spendsClaim ? [
                                                         "deposit:0",
                                                         ExitClaimAutoBoardTests.claim,
                                                     ] : ["deposit:0"]).map {
                                                         .init(txid: String($0.prefix { $0 != ":" }), vout: try #require(UInt32($0.split(separator: ":")[1])))
                                                     }))
            return quote
        }
        func discard(_ quote: OnchainQuote) { discarded.append(quote.id) }
        func review(held: UInt64, claimed: Set<String> = [ExitClaimAutoBoardTests.claim]) async throws -> OnchainQuote? {
            try await WalletSession.reserveBoardReview(minimum: 20_000, feeRate: 1, claims: .init(outpoints: claimed, held: held),
                                                       discard: { await self.discard($0) }, prepare: { try await self.prepare($0, excluding: $1) })
        }
    }

    @Test func aLaterDepositIsOfferedWithoutTheClaimedCoins() async throws {
        // The ₿58,705 claim plus a ₿40,000 deposit: the claim is excluded, so only the deposit, less fees and
        // the reserve, is boarded, prepared once after the probe.
        let board = Board(balance: 98_705, claimed: 58_705)
        let quote = try #require(try await board.review(held: 58_705))
        #expect(await board.amounts == [0, 40_000 - 150 - (5_000 + 43)])
        #expect(await board.exclusions == [[Self.claim], [Self.claim]])
        #expect(!quote.spends([Self.claim]))
        #expect(await board.discarded.count == 1)
        #expect(try quote.confirmed - quote.amount - #require(quote.chainFee) >= 58_705 + BoardingRules.reserve)
        // Without the claim nothing is excluded and the whole balance would have gone.
        let unclaimed = Board(balance: 98_705)
        #expect(try await unclaimed.review(held: 0, claimed: [])?.amount == 98_555 - 5_043)
        #expect(await unclaimed.exclusions == [[], []])
    }

    @Test func withOnlyTheClaimOnChainThereIsNoBoard() async throws {
        // autoBoardQuote doesn't even ask; if asked, bark refuses rather than spend the claim.
        let board = Board(balance: 58_705, claimed: 58_705)
        await #expect(throws: OnchainFailure.insufficientFunds) { try await board.review(held: 58_705) }
        #expect(await board.amounts == [0])
        #expect(await board.exclusions == [[Self.claim]])
    }

    @Test func aSmallDepositBesideTheClaimGivesNoPrompt() async throws {
        // ₿15,000 beside the claim: what auto-board may board stays under ₿20,000, so nothing is prepared after the probe.
        let board = Board(balance: 73_705, claimed: 58_705)
        #expect(try await board.review(held: 58_705) == nil)
        #expect(await board.amounts == [0])
    }

    @Test func aPreparationThatSpendsTheClaimedCoinAnywayIsDiscarded() async throws {
        let board = Board(balance: 98_705, claimed: 58_705, ignoresExclusions: true)
        #expect(try await board.review(held: 58_705) == nil)
        // The probe and the one sized board, both discarded: no prompt, and no retry at a lower amount.
        #expect(await board.amounts.count == 2)
        #expect(await board.discarded.count == 2)
    }

    /// Claimed outpoints as `prepareBoard` exclusions; malformed ones are dropped.
    @Test func claimOutpointsBecomeSdkExclusions() {
        let points = WalletSession.outpoints([Self.claim, "deposit:1", "malformed", "a:b", "x:1:2"])
        #expect(Set(points.map { "\($0.txid):\($0.vout)" }) == [Self.claim, "deposit:1"])
    }

    @Test func submittingRechecksTheClaimedCoins() async throws {
        for (claimed, held, submits) in [(Set<String>(), UInt64(0), true), ([Self.claim], 0, false), ([], 58_705, false)] {
            let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
            defer { try? FileManager.default.removeItem(at: file) }
            let journal = OnchainOperationJournal(file: file)
            let session = WalletSession()
            var quote = try await Board(balance: 45_000, claimed: 5_000, ignoresExclusions: true).prepare(39_000)
            quote.walletID = "wallet"
            let reviewed = quote
            let sent = BoardSubmissions()
            await session.retainBoardReview(reviewed, discard: {}, submit: {
                await sent.add()
                return .init(txid: "board", broadcast: .accepted, vtxoIds: [])
            })
            let submit = {
                try await session.submitBoard(reviewed, walletID: "wallet", journal: journal, confirmed: { 45_000 },
                                              claims: { .init(outpoints: claimed, held: held) })
            }
            if submits { _ = try await submit() } else { await #expect(throws: OnchainFailure.changed) { _ = try await submit() } }
            #expect(await sent.count == (submits ? 1 : 0))
        }
    }

    actor BoardSubmissions {
        var count = 0
        func add() { count += 1 }
    }

    @MainActor @Test func claimsAreRecordedBesideHandledInOneWrite() throws {
        var disk: String? = #"{"wallet":["deposit:0"]}"# // An original outpoint-only ledger.
        var writes = 0
        var store = AutoBoardFunding()
        store.read = { disk }; store.write = { disk = $0; writes += 1 }
        try AutoBoardFunding.markClaimed([Self.claim], walletID: "wallet", store: store)
        #expect(writes == 1)
        #expect(try store.claims(walletID: "wallet") == [Self.claim])
        #expect(try !store.hasUnhandled(.init(walletID: "wallet", outpoints: ["deposit:0", Self.claim])))
        // Marking it again changes nothing.
        try AutoBoardFunding.markClaimed([Self.claim], walletID: "wallet", store: store)
        #expect(writes == 1)
        #expect(try store.claims(walletID: "other").isEmpty)
    }

    @Test func aClaimToThisPhoneThatCantBeRecordedIsNotSent() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let wallet = ExitClaimExecutionTests.Wallet()
        var access = wallet.access()
        access.claimedToPhone = { _ in throw OnchainFailure.unavailable }
        let journal = OnchainOperationJournal(file: file)
        let reviewed = try await WalletSession.claimQuote(ids: ["a"], destination: ExitClaimExecutionTests.ownAddress, access: access)
        await #expect(throws: OnchainFailure.constructionFailed) {
            _ = try await WalletSession.executeClaim(reviewed, journal: journal, access: access)
        }
        #expect(await wallet.broadcasts.isEmpty)
        // Nothing was sent, so nothing blocks a new claim.
        #expect(try await journal.loadAll().isEmpty)
    }
}

/// An open page keeps the daemon clock of its last read; coming back restarts it there too, so needs-funds
/// waits for a daemon cycle after the app returns instead of showing at once.
@MainActor
struct ExitSceneTests {
    @Test func comingBackRestartsTheDaemonEvidence() async throws {
        var txs = ExitProgressTests.path.map { ExitProgressTests.tx($0, "verify-inputs") }
        txs[0] = ExitProgressTests.tx("root", "awaiting-cpfp-broadcast")
        let exit = ExitProgressTests.exit("Processing", ExitProgressTests.processing(txs))
        var read = OnchainOverview(walletID: "wallet", vtxos: [], exits: [exit], confirmed: 100, tip: 100, pendingTotal: 0, claimableHeight: nil)
        read.packageFees = [exit.id: 1_215]
        read.daemonSince = .distantPast
        let overview = read
        let seen = try ["wallet": ExitSightings(seen: [#require(exit.parkedEpisode): .init(tip: 100, at: .distantPast)])]
        let stored = try #require(String(bytes: JSONEncoder().encode(seen), encoding: .utf8))
        let model = OnchainModel(dependencies: .init(ready: { true }, overview: { overview },
                                                     quote: { _, _, _, _ in throw OnchainFailure.unavailable },
                                                     execute: { _ in throw OnchainFailure.unavailable },
                                                     attempt: { [] }, acknowledge: { _ in },
                                                     sightings: .init(
                                                         read: { stored },
                                                         write: { _ in })))
        await model.load()
        let now = Date()
        #expect(model.progress(exit, now: now).phase == .needsFunds)
        model.sceneChanged(at: now)
        #expect(model.progress(exit, now: now.addingTimeInterval(74)).phase == .sending)
        #expect(model.progress(exit, now: now.addingTimeInterval(75)).phase == .needsFunds)
    }
}

/// Hold to start and hold to claim through the real start and claim paths (`startQuote`/`executeStart`,
/// `claimQuote`/`executeClaim`) and the durable journal. The app leaves and comes back before the hold
/// (the screen drops the review and fetches it again), the hold fires twice (a size-class change
/// rebuilds it), the app comes back after the hold, then a fresh model and journal on the same file (a
/// process restart) reviews and holds again. Exactly one wallet mutation each.
@MainActor
struct ExitRestartTests {
    nonisolated static let overview = OnchainOverview(
        walletID: "wallet",
        vtxos: [],
        exits: [],
        confirmed: 100_000,
        tip: 100,
        pendingTotal: 0,
        claimableHeight: nil)

    /// What `OnchainScreen` and the review pages do when the scene becomes inactive, then active again.
    static func reactivate(_ model: OnchainModel, _ action: OnchainAction, ids: [String], destination: String? = nil) async {
        model.invalidate()
        #expect(model.quote == nil)
        await model.review(action, ids: ids, destination: destination)
    }

    static func hold(_ model: OnchainModel) async {
        async let held = model.confirm()
        async let rebuilt = model.confirm()
        _ = await (held, rebuilt)
    }

    @Test func aClaimHeldAgainAfterARestartIsSentOnce() async {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let wallet = ExitClaimExecutionTests.Wallet(), to = ExitClaimExecutionTests.ownAddress
        func launch() -> OnchainModel {
            let journal = OnchainOperationJournal(file: file), access = wallet.access()
            return OnchainModel(dependencies: .init(ready: { true }, overview: { Self.overview },
                                                    quote: { _, ids, _, destination in try await WalletSession.claimQuote(
                                                        ids: ids,
                                                        destination: destination,
                                                        access: access) },
                                                    execute: { try await WalletSession.executeClaim($0, journal: journal, access: access) },
                                                    attempt: { try await journal.loadAll() }, acknowledge: { try await journal.acknowledge($0) }))
        }
        let first = launch()
        await first.review(.claim, ids: ["a"], destination: to)
        await Self.reactivate(first, .claim, ids: ["a"], destination: to)
        #expect(first.quote != nil)
        await Self.hold(first)
        #expect(await wallet.broadcasts.count == 1)
        await Self.reactivate(first, .claim, ids: ["a"], destination: to)
        #expect(first.quote == nil && first.error != nil)
        let restarted = launch()
        await restarted.review(.claim, ids: ["a"], destination: to)
        #expect(restarted.quote == nil && restarted.error != nil)
        await restarted.confirm()
        #expect(await wallet.broadcasts.count == 1)
    }

    actor StartWallet {
        var starts: [[String]] = []
        /// What happened, in order: Noah's new run, then bark's start.
        var events: [String] = []
        var estimatorFails = false
        var runFails = false
        func start(_ ids: [String]) { starts.append(ids); events.append("start") }
        func began() throws {
            if runFails { throw OnchainFailure.unavailable }
            events.append("run")
        }
        func failRuns(_ fails: Bool) { runFails = fails }
        func failEstimates() { estimatorFails = true }
        func estimate() throws -> EmergencyExitFeeEstimate {
            if estimatorFails { throw OnchainFailure.unavailable }
            return .init(exitBroadcastFeeSats: 5_533, claimFeeSats: 262, totalFeeSats: 5_795, feeRateSatPerVb: 3, txsToBroadcast: 4, excludedVtxos: [])
        }
        /// bark still lists the VTXO as spendable: only the journal stops a second start.
        nonisolated func access() -> WalletSession.OnchainStartAccess {
            .init(walletID: "wallet", confirmed: { 18_400 }, spendable: { ["v": 31_000] },
                  estimate: { _ in try await self.estimate() }, start: { await self.start($0) }, began: { _ in try await self.began() })
        }
    }

    /// The new run couldn't be recorded: bark isn't asked, and the start can be reviewed and held again.
    @Test func aStartWhoseRunCantBeRecordedIsNotSent() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let wallet = StartWallet(), journal = OnchainOperationJournal(file: file), access = wallet.access()
        let model = OnchainModel(dependencies: .init(ready: { true }, overview: { Self.overview },
                                                     quote: { _, ids, _, _ in try await WalletSession.startQuote(ids: ids, access: access) },
                                                     execute: { try await WalletSession.executeStart($0, journal: journal, access: access) },
                                                     attempt: { try await journal.loadAll() }, acknowledge: { try await journal.acknowledge($0) }))
        await wallet.failRuns(true)
        await model.review(.startExit, ids: ["v"])
        #expect(await model.confirm() == false)
        #expect(model.error == OnchainFailure.constructionFailed.errorDescription && model.quote == nil && model.estimateFallback == nil)
        #expect(await wallet.starts.isEmpty)
        // Nothing was sent, so the journal holds nothing back: Review again gets a fresh quote.
        #expect(try await journal.loadAll().isEmpty)
        await wallet.failRuns(false)
        await model.review(.startExit, ids: ["v"])
        #expect(model.quote != nil)
        #expect(await model.confirm() == true)
        #expect(await wallet.starts == [["v"]])
    }

    /// The first estimate worked, the one at submit didn't: nothing was sent, so the hold isn't a success
    /// (the page stays, offering "Continue without estimate"). With consent it is.
    @Test func aHoldWhoseSubmitEstimateFailsIsNotASuccess() async {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let starter = StartWallet(), claimer = ExitClaimExecutionTests.Wallet()
        let journal = OnchainOperationJournal(file: file), start = starter.access(), claim = claimer.access()
        let model = OnchainModel(dependencies: .init(ready: { true }, overview: { Self.overview },
                                                     quote: { action, ids, _, destination in
                                                         action == .claim ? try await WalletSession.claimQuote(
                                                             ids: ids,
                                                             destination: destination,
                                                             access: claim)
                                                             : try await WalletSession.startQuote(ids: ids, access: start)
                                                     },
                                                     execute: { $0.action == .claim ? try await WalletSession.executeClaim($0, journal: journal, access: claim)
                                                         : try await WalletSession.executeStart($0, journal: journal, access: start)
                                                     },
                                                     attempt: { try await journal.loadAll() }, acknowledge: { try await journal.acknowledge($0) }))

        await model.review(.startExit, ids: ["v"])
        await starter.failEstimates()
        #expect(await model.confirm() == false)
        #expect(model.error == nil && model.estimateFallback != nil && model.quote == nil)
        #expect(await starter.starts.isEmpty)
        await model.continueWithoutEstimate()
        #expect(await model.confirm() == true)
        #expect(await starter.starts == [["v"]])

        await model.review(.claim, ids: ["a"], destination: ExitClaimExecutionTests.ownAddress)
        await claimer.change("estimator")
        #expect(await model.confirm() == false)
        #expect(model.error == nil && model.estimateFallback != nil && model.quote == nil)
        #expect(await claimer.broadcasts.isEmpty)
        await model.continueWithoutEstimate()
        #expect(await model.confirm() == true)
        #expect(await claimer.broadcasts.count == 1)
    }

    @Test func aStartHeldAgainAfterARestartStartsOnce() async {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let wallet = StartWallet()
        func launch() -> OnchainModel {
            let journal = OnchainOperationJournal(file: file), access = wallet.access()
            return OnchainModel(dependencies: .init(ready: { true }, overview: { Self.overview },
                                                    quote: { _, ids, _, _ in try await WalletSession.startQuote(ids: ids, access: access) },
                                                    execute: { try await WalletSession.executeStart($0, journal: journal, access: access) },
                                                    attempt: { try await journal.loadAll() }, acknowledge: { try await journal.acknowledge($0) }))
        }
        let first = launch()
        await first.review(.startExit, ids: ["v"])
        await Self.reactivate(first, .startExit, ids: ["v"])
        #expect(first.quote?.fee == 262 && first.quote?.chainFee == 5_533)
        await Self.hold(first)
        #expect(await wallet.starts == [["v"]])
        #expect(await wallet.events == ["run", "start"])
        await Self.reactivate(first, .startExit, ids: ["v"])
        #expect(first.quote == nil && first.error != nil)
        let restarted = launch()
        await restarted.review(.startExit, ids: ["v"])
        #expect(restarted.quote == nil && restarted.error != nil)
        await restarted.confirm()
        #expect(await wallet.starts == [["v"]])
    }
}
