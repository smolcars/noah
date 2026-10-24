import BarkBoltFFI
import Foundation
import Testing

@testable import Noah

@MainActor struct SendSafetyTests {
    let start = Date(timeIntervalSince1970: 1_790_000_000)
    let address = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
    let quote = SendQuote(amount: 1000, fee: 208, total: 1208, feeRate: 1, approximate: true)

    func attempt(_ method: String = "ark", source: SendSource? = nil, max: Bool = false)
        -> SendPaymentController.Attempt
    {
        .init(
            payment: .init(
                destination: address, method: method, amount: 1000, source: source, max: max),
            snapshot: .init(movementIDs: [1], bdkTxids: ["old"]), startedAt: start,
            paymentHash: method == "lightning" ? "invoice-hash" : nil)
    }
    func movement(
        _ method: String = "ark", status: String = "successful", id: UInt32 = 2,
        amount: UInt64 = 1000, hash: String? = nil
    ) -> Movement {
        Movement(
            id: id, status: status,
            subsystemName: method == "ark"
                ? "bark.arkoor" : method == "onchain" ? "bark.offboard" : "bark.lightning_send",
            subsystemKind: method == "onchain" ? "send_onchain" : "send", metadataJson: "{}",
            intendedBalanceSats: -Int64(amount), effectiveBalanceSats: -Int64(amount),
            offchainFeeSats: 0,
            sentToAddresses: [
                .init(paymentMethod: .init(methodType: method, value: address), amountSats: amount)
            ],
            receivedOnAddresses: [], inputVtxoIds: [], outputVtxoIds: [], exitedVtxoIds: [],
            createdAt: ISO8601DateFormatter().string(from: start.addingTimeInterval(1)),
            updatedAt: "",
            completedAt: nil,
            paymentHash: hash, lightningInvoice: nil,
            lightningOffer: method == "offer" ? address : nil)
    }
    func transaction(
        id: String = "new", outgoing: Bool = true, confirmed: Bool = false,
        destination: String? = nil, witness: Bool = false, amount: UInt64 = 1000
    ) throws -> WalletTransaction {
        let script = try #require(PaymentEncoding.bitcoinScript(destination ?? address))
        let scriptHex = script.map { String(format: "%02x", $0) }.joined()
        let amountHex = (0..<8).map { String(format: "%02x", (amount >> ($0 * 8)) & 255) }.joined()
        let hex =
            "02000000" + (witness ? "0001" : "") + "01" + String(repeating: "00", count: 32)
            + "0000000000ffffffff01" + amountHex + String(format: "%02x", script.count)
            + scriptHex
            + (witness ? "010100" : "") + "00000000"
        return WalletTransaction(
            txid: id, txHex: hex, onchainFeeSats: 100, balanceChangeSats: outgoing ? -1100 : 1000,
            confirmation: confirmed ? .init(height: 100, hash: "block") : nil, isCpfp: false)
    }
    func roundTrip(_ value: SendPaymentController.Attempt) throws -> SendPaymentController.Attempt {
        try JSONDecoder().decode(
            SendPaymentController.Attempt.self, from: JSONEncoder().encode(value))
    }
    func controller(
        _ storage: SendPaymentTests.Storage,
        reconcile:
            @escaping @Sendable (SendPaymentController.Attempt) async throws ->
            SendPaymentController.Resolution
    )
        -> SendPaymentController
    {
        .init(
            dependencies: .init(
                prepare: { _, _ in .init(movementIDs: [], bdkTxids: []) },
                pay: { _, _, _ in
                    Issue.record("Recovery must never pay")
                    throw SendFailure.unresolved
                },
                reconcile: reconcile, read: { storage.attempt }, write: { try storage.write($0) }))
    }

    @Test func absentBdkHistoryIsNeverProofOfFailure() throws {
        #expect(try SendReconciliation.onchain(attempt("onchain", source: .onchain), transactions: []) == .uncertain)
    }

    @Test func bdkRecoveryRequiresOneExactAmount() throws {
        let saved = attempt("onchain", source: .onchain)
        #expect(try SendReconciliation.onchain(saved, transactions: [transaction(amount: 999)]) == .uncertain)
        #expect(try SendReconciliation.onchain(saved, transactions: [transaction(), transaction(id: "second")]) == .possibleTransactions(["new", "second"]))
    }

    @Test func onchainMaxRequiresPreparationAndAnOpenWallet() async throws {
        let payment = attempt("onchain", source: .onchain, max: true).payment
        try SendMaxValidation.available(payment)
        let session = WalletSession(variant: .regtest)
        await #expect(throws: WalletSession.SessionError.notOpen) {
            _ = try await session.submitSend(payment, quote: quote, snapshot: .init(movementIDs: []))
        }
    }

    /// FFI 0.7.0's broadcastPrepared throws only before it records the transaction.
    @Test func firstBroadcastThrowsUnlockOnchainSendAsNotSubmitted() {
        for code in BarkErrorCode.allCases {
            let failure = SendOnchainSubmission.notSubmitted(BarkError(message: "arbitrary diagnostic", code: code))
            #expect(failure == ([.preparedTransactionNotFound, .preparedInputsUnavailable].contains(code) ? .fundsChanged : .constructionFailed))
        }
        // A confirmed-only parent reorged out: unavailable inputs. An unreachable chain source: untyped.
        #expect(SendOnchainSubmission.notSubmitted(BarkError(message: "prepared input parent is no longer confirmed; discard and prepare again",
                                                             code: .preparedInputsUnavailable)) == .fundsChanged)
        #expect(SendOnchainSubmission.notSubmitted(BarkError(message: "chain source unreachable")) == .constructionFailed)
        #expect(SendOnchainSubmission.notSubmitted(URLError(.timedOut)) == nil)
    }

    @Test func offchainMaxRequiresExactReviewedVtxos() throws {
        try SendMaxValidation.offchain(currentIDs: ["b", "a"], reviewedIDs: ["a", "b"])
        for changed in [["a"], ["a", "b", "new"], ["replacement"], []] {
            #expect(throws: SendFailure.fundsChanged) {
                try SendMaxValidation.offchain(currentIDs: changed, reviewedIDs: ["a", "b"])
            }
        }
        #expect(throws: SendFailure.fundsChanged) {
            try SendMaxValidation.offchain(currentIDs: [], reviewedIDs: nil)
        }
    }

    @Test func maxPreSubmissionRefusalPersistsFailureAndAllowsNewReview() async throws {
        let storage = SendPaymentTests.Storage()
        let subject = SendPaymentController(
            dependencies: .init(
                prepare: { _, _ in .init(movementIDs: [], bdkTxids: []) },
                pay: { _, _, _ in throw SendNotSubmitted(reason: .fundsChanged) },
                reconcile: { _ in
                    Issue.record("No SDK submission occurred")
                    return .uncertain
                },
                read: { storage.attempt }, write: { try storage.write($0) }))
        subject.submit(attempt("onchain", source: .onchain, max: true).payment, quote: quote)
        try await SendPaymentTests.waitUntil { subject.state != .submitting }
        #expect(subject.state == .failed)
        #expect(subject.message == SendFailure.fundsChanged.errorDescription)
        storage.attempt = try roundTrip(#require(storage.attempt))
        let restarted = controller(storage) { _ in .uncertain }
        #expect(restarted.state == .failed)
        restarted.acknowledge()
        #expect(restarted.state == .ready)
    }

    @Test func missingBdkHistoryIgnoresArkHistoryAndStaysLockedAfterRelaunch() async throws {
        let saved = try roundTrip(attempt("onchain", source: .onchain))
        #expect(
            SendReconciliation.ark(saved, movements: [movement("onchain", status: "failed")])
                == .uncertain)
        let storage = SendPaymentTests.Storage()
        storage.attempt = saved
        let old = try transaction(id: "old")
        let received = try transaction(id: "incoming", outgoing: false)
        let subject = controller(storage) {
            try SendReconciliation.onchain($0, transactions: [old, received])
        }
        await subject.checkStatus()
        #expect(subject.state == .uncertain && subject.canOverride)
        subject.acknowledge()
        #expect(subject.state == .uncertain)
        storage.attempt = try roundTrip(#require(storage.attempt))
        let restarted = controller(storage) { _ in .uncertain }
        #expect(restarted.state == .uncertain)
        restarted.startNewPaymentAfterHistoryCheck(confirmed: false)
        #expect(restarted.locked)
        restarted.startNewPaymentAfterHistoryCheck(confirmed: true)
        #expect(restarted.state == .ready)
    }

    @Test(arguments: [false, true])
    func rediscoveredHistoricalBdkTransactionIsOnlyPossibleEvidence(confirmed: Bool) async throws {
        let saved = try roundTrip(attempt("onchain", source: .onchain))
        let tx = try transaction(confirmed: confirmed)
        let storage = SendPaymentTests.Storage()
        storage.attempt = saved
        let subject = controller(storage) { try SendReconciliation.onchain($0, transactions: [tx]) }
        await subject.checkStatus()
        #expect(subject.state == .uncertain && subject.canOverride)
        #expect(subject.attempt?.receipt == nil)
        #expect(subject.attempt?.possibleTxids == ["new"])
        subject.acknowledge()
        #expect(subject.locked)
        storage.attempt = try roundTrip(#require(storage.attempt))
        let restarted = controller(storage) { try SendReconciliation.onchain($0, transactions: [tx]) }
        await restarted.checkStatus()
        #expect(restarted.state == .uncertain && restarted.canOverride)
        #expect(restarted.attempt?.receipt == nil)
        #expect(restarted.attempt?.possibleTxids == ["new"])
        restarted.startNewPaymentAfterHistoryCheck(confirmed: false)
        #expect(restarted.locked)
        restarted.startNewPaymentAfterHistoryCheck(confirmed: true)
        #expect(!restarted.locked)
    }

    @Test func submittedBdkReceiptUsesActualFeeEvenAboveEstimate() throws {
        var saved = attempt("onchain", source: .onchain)
        saved.quote = quote
        saved.receipt = .init(amount: 1000, txid: "new", status: .submitted, proof: .submitReturnedTxid)
        var tx = try transaction()
        tx.onchainFeeSats = 500
        guard case .paid(let receipt) = try SendReconciliation.onchain(saved, transactions: [tx]) else {
            Issue.record("Returned transaction must remain tracked")
            return
        }
        #expect(receipt.fee == 500)
        #expect(receipt.fee! > quote.fee)
        #expect(receipt.amount == 1000)
        #expect(try roundTrip(.init(payment: saved.payment, snapshot: saved.snapshot, receipt: receipt)).receipt?.fee == 500)
    }

    @Test func unrelatedBdkDestinationDoesNotEstablishPayment() throws {
        let other = try transaction(destination: "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy")
        #expect(
            try SendReconciliation.onchain(
                attempt("onchain", source: .onchain), transactions: [other])
                == .uncertain)
    }

    @Test func malformedBdkEvidenceNeverUnlocksAndLegacySnapshotFailsClosed() throws {
        var tx = try transaction()
        tx.txHex = "0000"
        #expect(throws: SendFailure.unresolved) {
            try SendReconciliation.onchain(attempt("onchain", source: .onchain), transactions: [tx])
        }
        var legacy = attempt("onchain", source: .onchain)
        legacy.snapshot.bdkTxids = nil
        #expect(try SendReconciliation.onchain(legacy, transactions: []) == .uncertain)
    }

    @Test(arguments: ["successful", "failed", "canceled", "pending"])
    func bolt11RecoveryUsesOnlyKnownHash(status: String) async throws {
        let saved = try roundTrip(attempt("lightning"))
        let unrelated = movement("lightning", status: status, hash: "other-hash")
        #expect(SendReconciliation.ark(saved, movements: [unrelated]) == .uncertain)
        // A pre-existing successful hash is already paid, not a new success. This journal predates the paid flag, so a
        // reused movement is read by when it completed; a failure counts once it finished after the attempt started.
        var matchingMovement = movement("lightning", status: status, id: 1, hash: "invoice-hash")
        if ["failed", "canceled"].contains(status) {
            matchingMovement.intendedBalanceSats = 0
            matchingMovement.effectiveBalanceSats = 0
            matchingMovement.completedAt = HistoryRules.isoDate(start.addingTimeInterval(60))
        } else if status == "successful" {
            matchingMovement.completedAt = HistoryRules.isoDate(start.addingTimeInterval(-60))
        }
        let matched = matchingMovement
        let storage = SendPaymentTests.Storage()
        storage.attempt = saved
        let subject = controller(storage) {
            SendReconciliation.ark($0, movements: [unrelated, matched])
        }
        await subject.checkStatus()
        #expect(subject.canOverride == (status == "pending"))
        if status == "successful" {
            #expect(
                subject.state
                    == .alreadyPaid(.init(amount: 0, paymentHash: "invoice-hash", movementID: 1, proof: .actualMovement))
            )
        } else if status == "pending" {
            #expect(subject.state == .uncertain)
        } else {
            #expect(subject.state == .failed)
        }
    }

    @Test func reusedAmountlessInvoiceShowsNoAmountOrFeeAcrossRestart() async throws {
        var saved = attempt("lightning")
        saved.payment.amount = 2000
        var old = movement("lightning", id: 1, amount: 1000, hash: "invoice-hash")
        old.offchainFeeSats = 83
        old.effectiveBalanceSats = -1083
        old.completedAt = HistoryRules.isoDate(start.addingTimeInterval(-60))
        let matched = old
        let storage = SendPaymentTests.Storage()
        storage.attempt = saved
        let subject = controller(storage) { SendReconciliation.ark($0, movements: [matched]) }
        await subject.checkStatus()
        // bark's reused movement can't say which attempt's amount and fee it holds.
        let receipt = SendReceipt(amount: 0, paymentHash: "invoice-hash", movementID: 1, proof: .actualMovement)
        #expect(subject.state == .alreadyPaid(receipt))
        #expect(subject.attempt?.receipt == nil)
        storage.attempt = try roundTrip(#require(storage.attempt))
        #expect(controller(storage) { _ in .uncertain }.state == .alreadyPaid(receipt))
    }

    @Test(arguments: ["pending", "failed", "canceled", "successful"])
    func invoiceAmountMismatchNeverResumesOrProvesNewPayment(status: String) {
        let saved = attempt("lightning")
        let wrong = movement("lightning", status: status, amount: 2000, hash: "invoice-hash")
        #expect(SendReconciliation.ark(saved, movements: [wrong]) == .uncertain)
        var pending = wrong
        pending.id = 1
        pending.status = "pending"
        #expect(SendReconciliation.ark(saved, movements: [pending]) == .uncertain)
    }

    @Test func newInvoiceSuccessUsesMovementAmountAndFee() {
        var paid = movement("lightning", hash: "invoice-hash")
        paid.offchainFeeSats = 83
        paid.effectiveBalanceSats = -1083
        #expect(SendReconciliation.ark(attempt("lightning"), movements: [paid])
                == .paid(.init(amount: 1000, paymentHash: "invoice-hash", movementID: 2, fee: 83, proof: .actualMovement)))
    }

    @Test func confirmedReceiptRevalidatesThroughReorgAndMissingHistory() async throws {
        var saved = attempt("onchain", source: .onchain)
        saved.receipt = .init(amount: 1000, txid: "new", status: .confirmed, fee: 100, proof: .submitReturnedTxid)
        let storage = SendPaymentTests.Storage()
        storage.attempt = try roundTrip(saved)
        let pending = try transaction()
        let subject = controller(storage) { try SendReconciliation.onchain($0, transactions: [pending]) }
        await subject.checkStatus()
        #expect(subject.state == .succeeded(.init(amount: 1000, txid: "new", status: .submitted, fee: 100, proof: .submitReturnedTxid)))
        storage.attempt = try roundTrip(#require(storage.attempt))
        let missing = controller(storage) { try SendReconciliation.onchain($0, transactions: []) }
        await missing.checkStatus()
        #expect(missing.state == .uncertain && missing.canOverride)
        #expect(missing.attempt?.receipt?.txid == "new")
        #expect(missing.attempt?.receipt?.status == .unlocated)
        missing.acknowledge()
        missing.startNewPaymentAfterHistoryCheck(confirmed: false)
        #expect(missing.locked && storage.attempt?.receipt?.txid == "new")
        storage.attempt = try roundTrip(#require(storage.attempt))
        let confirmed = try transaction(confirmed: true)
        let restarted = controller(storage) { try SendReconciliation.onchain($0, transactions: [confirmed]) }
        #expect(restarted.state == .uncertain)
        await restarted.checkStatus()
        #expect(restarted.state == .succeeded(saved.receipt!))
    }

    @Test func roundOneMissingHistoryFailureAndInvoiceReceiptReopenUncertain() throws {
        for method in ["onchain", "lightning"] {
            var saved = attempt(method, source: method == "onchain" ? .onchain : nil)
            saved.safetyVersion = 1
            saved.failed = true
            if method == "lightning" { saved.receipt = .init(amount: 2000, paymentHash: "invoice-hash", movementID: 1) }
            let storage = SendPaymentTests.Storage()
            storage.attempt = try roundTrip(saved)
            let subject = controller(storage) { _ in .uncertain }
            #expect(subject.state == .uncertain && subject.attempt?.failed == false)
        }
    }

    @Test(
        arguments: ["ark", "offer", "lnurl", "onchain"],
        ["successful", "failed", "canceled", "pending"])
    func weakRecoveryNeverAutoUnlocks(method: String, status: String) async throws {
        let saved = try roundTrip(attempt(method, source: method == "onchain" ? .offchain : nil))
        let candidate = movement(method, status: status)
        let storage = SendPaymentTests.Storage()
        storage.attempt = saved
        let subject = controller(storage) { SendReconciliation.ark($0, movements: [candidate]) }
        await subject.checkStatus()
        #expect(subject.state == .uncertain)
        #expect(subject.attempt?.candidates?.count == 1)
        subject.acknowledge()
        #expect(subject.locked)
        storage.attempt = try roundTrip(#require(storage.attempt))
        let restarted = controller(storage) { _ in .uncertain }
        #expect(restarted.state == .uncertain && restarted.canOverride)
        restarted.startNewPaymentAfterHistoryCheck(confirmed: false)
        #expect(restarted.locked)
        storage.failWrite = true
        restarted.startNewPaymentAfterHistoryCheck(confirmed: true)
        #expect(restarted.locked)
        storage.failWrite = false
        restarted.startNewPaymentAfterHistoryCheck(confirmed: true)
        #expect(restarted.state == .ready && storage.attempt == nil)
    }

    @Test func weakCandidateRequiresSubsystemTimeSourceAndExactAmount() {
        for method in ["ark", "offer", "lnurl", "onchain"] {
            let saved = attempt(method, source: method == "onchain" ? .offchain : nil)
            var wrongSubsystem = movement(method)
            wrongSubsystem.subsystemName = "bark.exit"
            var wrongKind = movement(method)
            wrongKind.subsystemKind = "receive"
            var old = movement(method)
            old.createdAt = ISO8601DateFormatter().string(from: start.addingTimeInterval(-1))
            let wrongAmount = movement(method, amount: 999)
            #expect(
                SendReconciliation.ark(
                    saved,
                    movements: [
                        wrongSubsystem, wrongKind, old, wrongAmount, movement(method, id: 1),
                    ])
                    == .candidates([]))
        }
        var max = attempt("onchain", source: .offchain, max: true)
        var offboard = movement("onchain", amount: 999)
        offboard.subsystemKind = "offboard"
        if case .candidates(let candidates) = SendReconciliation.ark(max, movements: [offboard]) {
            #expect(candidates.count == 1)
        } else {
            Issue.record("Expected weak MAX candidate")
        }
        max.payment.source = .onchain
        #expect(SendReconciliation.ark(max, movements: [offboard]) == .uncertain)
    }

    @Test func offboardTxidIsOnlySubmissionEvenAfterRestartAndMissingHistory() async throws {
        var saved = attempt("onchain", source: .offchain)
        saved.receipt = .init(amount: 1000, txid: "offboard", status: .submitted, proof: .submitReturnedTxid)
        let storage = SendPaymentTests.Storage()
        storage.attempt = try roundTrip(saved)
        let subject = controller(storage) { try SendReconciliation.onchain($0, transactions: []) }
        await subject.checkStatus()
        #expect(subject.state == .succeeded(saved.receipt!))
        #expect(saved.receipt!.isSubmitted && !subject.canOverride)
    }

    @Test func submittedReceiptCannotBeDowngradedByFailure() async throws {
        var saved = attempt("onchain", source: .onchain)
        saved.receipt = .init(amount: 1000, txid: "recorded", status: .submitted, proof: .submitReturnedTxid)
        let storage = SendPaymentTests.Storage()
        storage.attempt = try roundTrip(saved)
        let subject = controller(storage) { _ in .failed }
        await subject.checkStatus()
        #expect(subject.state == .succeeded(saved.receipt!))
    }

    @Test func consensusOutputsSupportLegacyAndWitnessAndRejectTruncation() throws {
        for witness in [true, false] {
            let tx = try transaction(witness: witness)
            #expect(try SendTransaction.amount(to: address, hex: tx.txHex) == 1000)
            #expect(
                try SendTransaction.amount(to: "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy", hex: tx.txHex)
                    == 0)
            for trim in [1, 2, 10, 30] {
                #expect(throws: SendFailure.unresolved) {
                    try SendTransaction.amount(to: address, hex: String(tx.txHex.dropLast(trim)))
                }
            }
        }
    }
    @Test func legacyWeakTerminalResultsAreNotTrustedAfterUpgrade() throws {
        for method in ["ark", "offer", "lnurl", "onchain"] {
            for failed in [true, false] {
                var saved = attempt(method, source: method == "onchain" ? .offchain : nil)
                saved.safetyVersion = nil
                saved.failed = failed
                saved.receipt = failed ? nil : .init(amount: 1000, movementID: 2)
                let storage = SendPaymentTests.Storage()
                storage.attempt = try roundTrip(saved)
                let recovered = controller(storage) { _ in .uncertain }
                #expect(recovered.state == .uncertain)
                #expect(recovered.canOverride)
            }
        }
        var saved = attempt("onchain", source: .onchain)
        saved.safetyVersion = nil
        saved.failed = true
        saved.snapshot.bdkTxids = nil
        let storage = SendPaymentTests.Storage()
        storage.attempt = try roundTrip(saved)
        #expect(controller(storage) { _ in .uncertain }.state == .uncertain)
        saved.receipt = .init(amount: 1000, txid: "recorded-before-upgrade")
        storage.attempt = try roundTrip(saved)
        let migrated = controller(storage) { _ in .uncertain }
        #expect(migrated.state == .uncertain && migrated.canOverride)
        #expect(migrated.attempt?.possibleTxids == ["recorded-before-upgrade"])
        #expect(migrated.attempt?.receipt == nil)
    }

    @Test(arguments: ["ark", "offer", "lnurl", "onchain"])
    func missingWeakHistoryRemainsLockedUntilExplicitOverride(method: String) async throws {
        let storage = SendPaymentTests.Storage()
        storage.attempt = try roundTrip(
            attempt(method, source: method == "onchain" ? .offchain : nil))
        let recovered = controller(storage) { SendReconciliation.ark($0, movements: []) }
        await recovered.checkStatus()
        #expect(recovered.state == .uncertain && recovered.attempt?.candidates == [])
        recovered.acknowledge()
        #expect(recovered.locked)
        recovered.startNewPaymentAfterHistoryCheck(confirmed: true)
        #expect(recovered.state == .ready)
    }
}
