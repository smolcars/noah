import BarkBoltFFI
import Foundation

struct SendCandidate: Codable, Equatable, Sendable, Identifiable {
    var id: UInt32
    var status: String
    var amount: UInt64
    var createdAt: String
}

/// Receipts bind their movement exactly: SDK 0.6.0 returns it for Ark sends and offboards, and a
/// BOLT11 hash binds Lightning. Weak history correlation remains only for attempts with no result,
/// e.g. a crash before the receipt was saved (FFI item 19's pre-ID window).
enum SendReconciliation {
    /// The exact movement for history metadata, never a destination/amount/time match.
    /// Movements that predate the attempt (an inherited Lightning checkpoint) are left untouched.
    static func metadataMovementID(_ receipt: SendReceipt, snapshot: SendSnapshot) -> UInt32? {
        guard let id = receipt.movementID, !snapshot.movementIDs.contains(id) else { return nil }
        return id
    }

    static func matches(_ movement: Movement, payment: SendPayment, hash: String?) -> Bool {
        guard payment.source != .onchain else { return false }
        let subsystem = movement.subsystemName.lowercased()
        let kind = movement.subsystemKind.lowercased()
        switch payment.method {
        case "ark": guard subsystem == "bark.arkoor", kind == "send" else { return false }
        case "lightning", "offer", "lnurl":
            guard subsystem == "bark.lightning_send", kind == "send" else { return false }
        case "onchain":
            guard payment.source == .offchain, ["bark.offboard", "bark.round"].contains(subsystem),
                kind == (payment.max ? "offboard" : "send_onchain")
            else { return false }
        default: return false
        }
        if let hash { return movement.paymentHash == hash }
        guard movement.intendedBalanceSats < 0 || movement.effectiveBalanceSats < 0 else { return false }
        if payment.method == "lightning" { return false }
        return movement.sentToAddresses.contains {
            $0.paymentMethod.value == payment.destination
                && (payment.max || $0.amountSats == payment.amount)
        }
            || (payment.method == "offer" && movement.lightningOffer == payment.destination
                && movement.sentToAddresses.reduce(UInt64(0)) { $0 + $1.amountSats }
                    == payment.amount)
    }

    static func ark(_ attempt: SendPaymentController.Attempt, movements: [Movement])
        -> SendPaymentController.Resolution
    {
        let payment = attempt.payment
        guard !(payment.method == "onchain" && payment.source == .onchain) else {
            return .uncertain
        }
        let hash =
            ["lightning", "lnurl"].contains(payment.method)
            ? attempt.paymentHash ?? PaymentEncoding.invoice(payment.destination)?.hash : nil
        let candidates = movements.filter { movement in
            guard matches(movement, payment: payment, hash: hash) else { return false }
            // A BOLT11 hash identifies the payment even if its pending movement predates this attempt.
            if hash != nil { return true }
            guard !attempt.snapshot.movementIDs.contains(movement.id),
                let start = attempt.startedAt,
                let created = timestamp(movement.createdAt), created >= start
            else { return false }
            return true
        }
        if let hash {
            return lightningAttempt(candidates, hash: hash, amount: payment.amount, snapshot: attempt.snapshot,
                                    startedAt: attempt.startedAt, fixed: fixesAmount(attempt.snapshot.resolvedInvoice ?? payment.destination))
        }
        return .candidates(
            candidates.map {
                SendCandidate(
                    id: $0.id, status: $0.status,
                    amount: payment.max
                        ? $0.sentToAddresses.reduce(0) { $0 + $1.amountSats } : payment.amount,
                    createdAt: $0.createdAt)
            })
    }

    /// What a successful send's effective balance took beyond `actual`. A retried invoice's reused movement keeps its
    /// first attempt's fee field, so this is its fee; nil when bark's numbers can't give one.
    static func lightningFee(_ movement: Movement, actual: UInt64) -> UInt64? {
        let (spent, overflow) = Int64(0).subtractingReportingOverflow(movement.effectiveBalanceSats)
        guard !overflow, spent >= 0, UInt64(spent) >= actual else { return nil }
        return UInt64(spent) - actual
    }

    /// Whether `invoice` sets its own amount, so every attempt on its hash sent that amount.
    static func fixesAmount(_ invoice: String?) -> Bool {
        invoice.flatMap { PaymentEncoding.invoice($0)?.sats.map { $0 > 0 } } ?? false
    }

    static func lightning(_ candidates: [Movement], hash: String, amount: UInt64, snapshot: SendSnapshot, fixed: Bool)
        -> SendPaymentController.Resolution
    {
        guard let (movement, actual) = sent(candidates, hash: hash) else { return .uncertain }
        if movement.status == "successful", actual > 0 {
            let receipt = receipt(movement, actual: actual, hash: hash)
            if snapshot.movementIDs.contains(movement.id) { return alreadyPaid(receipt, fixed: fixed) }
            guard actual == amount else { return .uncertain }
            return .paid(receipt)
        }
        // A hash alone cannot identify the reviewed amount of an amountless invoice.
        guard actual == amount else { return .uncertain }
        if ["failed", "canceled"].contains(movement.status) { return .failed }
        return .uncertain
    }

    /// This attempt's outcome from its hash's movement. Retrying a failed invoice, bark 0.8 reuses the hash's finished
    /// movement (async-payments.md, "Reused hashes"), so a movement already in the snapshot is never read by its status
    /// alone. Success is this attempt's when bark had no paid record before it (`SendSnapshot.invoicePaid`; journals
    /// without it go by whether the movement completed after `startedAt`), with the fee its effective balance took
    /// beyond the amount: the fee field keeps the first attempt's. Failure needs the movement to have completed after
    /// `startedAt`; until then the attempt may still be running.
    static func lightningAttempt(_ candidates: [Movement], hash: String, amount: UInt64, snapshot: SendSnapshot,
                                 startedAt: Date?, fixed: Bool) -> SendPaymentController.Resolution
    {
        guard let (movement, actual) = sent(candidates, hash: hash) else { return .uncertain }
        guard snapshot.movementIDs.contains(movement.id) else {
            return lightning(candidates, hash: hash, amount: amount, snapshot: snapshot, fixed: fixed)
        }
        let completedSince = startedAt.flatMap { start in movement.completedAt.flatMap(timestamp).map { $0 > start } }
        if movement.status == "successful", actual > 0 {
            let receipt = receipt(movement, actual: actual, hash: hash)
            switch snapshot.invoicePaid ?? completedSince.map(!) {
            case true?: return alreadyPaid(receipt, fixed: fixed)
            case false?: return actual == amount && lightningFee(movement, actual: actual) != nil ? .paid(receipt) : .uncertain
            case nil: return .uncertain
            }
        }
        guard actual == amount, ["failed", "canceled"].contains(movement.status) else { return .uncertain }
        return completedSince == true ? .failed : .uncertain
    }

    /// The hash's one Lightning send movement and what it sent, if its amounts add up.
    private static func sent(_ candidates: [Movement], hash: String) -> (Movement, UInt64)? {
        guard candidates.count == 1, let movement = candidates.first,
              movement.subsystemName == "bark.lightning_send", movement.subsystemKind == "send",
              movement.paymentHash == hash else { return nil }
        var actual: UInt64 = 0
        for destination in movement.sentToAddresses {
            let (sum, overflow) = actual.addingReportingOverflow(destination.amountSats)
            guard !overflow else { return nil }
            actual = sum
        }
        return (movement, actual)
    }

    private static func receipt(_ movement: Movement, actual: UInt64, hash: String) -> SendReceipt {
        SendReceipt(amount: actual, paymentHash: hash, movementID: movement.id,
                    fee: lightningFee(movement, actual: actual) ?? movement.offchainFeeSats, proof: .actualMovement)
    }

    /// An invoice without its own amount may have been paid by a retry for another amount, and bark's reused movement
    /// keeps the first attempt's numbers: nothing on it proves what was sent or paid in fees, so the receipt shows neither.
    private static func alreadyPaid(_ receipt: SendReceipt, fixed: Bool) -> SendPaymentController.Resolution {
        guard !fixed else { return .alreadyPaid(receipt) }
        var unproven = receipt
        unproven.amount = 0
        unproven.fee = nil
        return .alreadyPaid(unproven)
    }

    /// The SDK-returned txid binds the result; destination/time-only candidates remain unproven.
    /// SDK 0.6.0 also returns the movement id, which must agree; journals saved before it bind by txid alone.
    static func offboardReceipt(_ receipt: SendReceipt, payment: SendPayment, movements: [Movement]) -> SendReceipt {
        guard receipt.proof == .submitReturnedTxid, let txid = receipt.txid else { return receipt }
        struct Metadata: Decodable { var offboard_txid: String; var offboard_tx: String }
        let matches = movements.filter {
            (receipt.movementID == nil || $0.id == receipt.movementID)
                && $0.subsystemName == "bark.offboard" && $0.subsystemKind == (payment.max ? "offboard" : "send_onchain")
                && (try? JSONDecoder().decode(Metadata.self, from: Data($0.metadataJson.utf8)))?.offboard_txid == txid
        }
        guard matches.count == 1, let movement = matches.first,
              let metadata = try? JSONDecoder().decode(Metadata.self, from: Data(movement.metadataJson.utf8)),
              let actual = try? SendTransaction.amount(to: payment.destination, hex: metadata.offboard_tx), actual > 0,
              payment.max || actual == payment.amount
        else { return receipt }
        var result = receipt
        result.amount = actual
        result.amountIsPending = false
        result.fee = movement.offchainFeeSats
        result.movementID = movement.id
        return result
    }

    static func onchain(_ attempt: SendPaymentController.Attempt, transactions: [WalletTransaction])
        throws
        -> SendPaymentController.Resolution
    {
        let originalTxid = attempt.receipt?.txid ?? attempt.broadcastingTxid
        let original = transactions.first { $0.txid == originalTxid }
        let inputs = attempt.quote?.prepared.flatMap { $0.txid == originalTxid ? $0.inputs : nil }
            ?? original.flatMap { try? SendTransaction.parse(hex: $0.txHex).inputs }
        let conflict = inputs.flatMap { inputs in
            transactions.first { tx in
                guard tx.txid != originalTxid, tx.confirmation != nil,
                      let parsed = try? SendTransaction.parse(hex: tx.txHex) else { return false }
                return !Set(inputs).isDisjoint(with: parsed.inputs)
            }?.txid
        }
        if var receipt = attempt.receipt, [.submitReturnedTxid, .preparedTxid].contains(receipt.proof), let txid = receipt.txid {
            if let tx = transactions.first(where: { $0.txid == txid }) {
                receipt.status = tx.confirmation == nil ? .submitted : .confirmed
                receipt.fee = tx.onchainFeeSats ?? receipt.fee
                if tx.confirmation != nil { receipt.broadcastRetryPending = nil }
            } else {
                // External offboards use independent chain-source status below.
                receipt.status = attempt.payment.source == .offchain ? .submitted : .unlocated
            }
            receipt.confirmedConflictTxid = conflict
            if conflict != nil { receipt.status = .conflicted }
            return .paid(receipt)
        }
        if let txid = attempt.broadcastingTxid, let quote = attempt.quote,
           quote.prepared?.txid == txid, attempt.payment.method == "onchain", attempt.payment.source == .onchain {
            guard original != nil || conflict != nil else { return .uncertain }
            return .paid(.init(amount: quote.amount, txid: txid,
                               status: conflict != nil ? .conflicted : original?.confirmation == nil ? .submitted : .confirmed,
                               fee: quote.fee, proof: .preparedTxid,
                               // BDK records the spend before submission; cached presence cannot prove acceptance.
                               broadcastRetryPending: original?.confirmation == nil ? true : nil,
                               confirmedConflictTxid: conflict))
        }
        // Legacy journals have no exact pre-broadcast identity; retain their conservative evidence path.
        guard attempt.payment.method == "onchain", attempt.payment.source == .onchain, !attempt.payment.max,
            let previous = attempt.snapshot.bdkTxids
        else { return .uncertain }
        var candidates: [String] = []
        for tx in transactions where !previous.contains(tx.txid) && tx.balanceChangeSats < 0 {
            let amount = try SendTransaction.amount(to: attempt.payment.destination, hex: tx.txHex)
            if amount > 0, amount == attempt.payment.amount { candidates.append(tx.txid) }
        }
        // A previously evicted or unsynced historical payment can reappear after submission.
        // Only a txid returned by submit binds a BDK transaction to this attempt (FFI item 16).
        return candidates.isEmpty ? .uncertain : .possibleTransactions(candidates.sorted())
    }

    static func chainEvidence(
        _ resolution: SendPaymentController.Resolution, source: SendSource?,
        lookup: @Sendable (String) async throws -> ChainTransactionStatus
    ) async -> SendPaymentController.Resolution {
        guard case .paid(var receipt) = resolution, let txid = receipt.txid else { return resolution }
        do { receipt = chainEvidence(try await lookup(txid), receipt: receipt, source: source) }
        catch {
            // Preserve newer BDK evidence even when the independent chain source is unavailable.
            receipt.chainStatusError = ChainTransactionStatus.errorText
        }
        return .paid(receipt)
    }

    static func chainEvidence(_ status: ChainTransactionStatus, receipt: SendReceipt, source: SendSource?) -> SendReceipt {
        var result = receipt
        result.chainStatus = status
        result.chainStatusError = nil
        // BDK's reorg/competing-spend rules still own its lock and receipt state.
        // Absence or mempool evidence only downgrades a confirmation; it never resolves an unlocated receipt.
        if source == .offchain, result.status != .conflicted {
            if case .confirmed = status { result.status = .confirmed }
            else if result.status == .confirmed { result.status = .submitted }
        }
        return result
    }

    private static func timestamp(_ value: String) -> Date? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.date(from: value) ?? ISO8601DateFormatter().date(from: value)
    }
}

/// Parse BDK's consensus transaction; malformed evidence must never unlock Send.
enum SendTransaction {
    struct Input: Codable, Equatable, Hashable, Sendable {
        var txid: String
        var vout: UInt32
    }
    static func amount(to address: String, hex: String) throws -> UInt64 {
        guard let script = PaymentEncoding.bitcoinScript(address) else { throw SendFailure.unresolved }
        var amount: UInt64 = 0
        for output in try parse(hex: hex).outputs where output.script == script {
            let (sum, overflow) = amount.addingReportingOverflow(output.amount)
            guard !overflow else { throw SendFailure.unresolved }
            amount = sum
        }
        return amount
    }
    static func parse(hex: String) throws -> (inputs: [Input], outputs: [(amount: UInt64, script: [UInt8])]) {
        guard hex.count % 2 == 0 else { throw SendFailure.unresolved }
        var bytes: [UInt8] = []
        var index = hex.startIndex
        while index < hex.endIndex {
            let end = hex.index(index, offsetBy: 2)
            guard let byte = UInt8(hex[index..<end], radix: 16) else {
                throw SendFailure.unresolved
            }
            bytes.append(byte)
            index = end
        }
        var cursor = 0
        func take(_ count: Int) throws -> [UInt8] {
            guard count >= 0, count <= bytes.count - cursor else { throw SendFailure.unresolved }
            defer { cursor += count }
            return Array(bytes[cursor..<cursor + count])
        }
        func integer(_ count: Int) throws -> UInt64 {
            try take(count).enumerated().reduce(0) { $0 | UInt64($1.element) << ($1.offset * 8) }
        }
        func size() throws -> Int {
            let tag = try integer(1)
            let value = try tag < 253 ? tag : integer(tag == 253 ? 2 : tag == 254 ? 4 : 8)
            guard value <= UInt64(bytes.count) else { throw SendFailure.unresolved }
            return Int(value)
        }
        _ = try take(4)
        let witness = bytes.count > cursor + 1 && bytes[cursor] == 0 && bytes[cursor + 1] == 1
        if witness { _ = try take(2) }
        let inputs = try size()
        guard inputs > 0 else { throw SendFailure.unresolved }
        var outpoints: [Input] = []
        for _ in 0..<inputs {
            let txid = try take(32).reversed().map { String(format: "%02x", $0) }.joined()
            outpoints.append(.init(txid: txid, vout: UInt32(try integer(4))))
            _ = try take(size())
            _ = try take(4)
        }
        var parsedOutputs: [(UInt64, [UInt8])] = []
        let outputs = try size()
        for _ in 0..<outputs {
            let value = try integer(8)
            let outputScript = try take(size())
            parsedOutputs.append((value, outputScript))
        }
        if witness {
            for _ in 0..<inputs {
                let items = try size()
                for _ in 0..<items { _ = try take(size()) }
            }
        }
        _ = try take(4)
        guard cursor == bytes.count else { throw SendFailure.unresolved }
        return (outpoints, parsedOutputs)
    }
}
