import BarkBoltFFI
import Foundation

struct SendCandidate: Codable, Equatable, Sendable, Identifiable {
    var id: UInt32
    var status: String
    var amount: UInt64
    var createdAt: String
}

/// FFI item 16 will replace weak history correlation with durable operation identity.
enum SendReconciliation {
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
            return lightning(candidates, hash: hash, amount: payment.amount, snapshot: attempt.snapshot)
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

    static func lightning(_ candidates: [Movement], hash: String, amount: UInt64, snapshot: SendSnapshot)
        -> SendPaymentController.Resolution
    {
        guard candidates.count == 1, let movement = candidates.first,
              movement.subsystemName == "bark.lightning_send", movement.subsystemKind == "send",
              movement.paymentHash == hash else { return .uncertain }
        var actual: UInt64 = 0
        for destination in movement.sentToAddresses {
            let (sum, overflow) = actual.addingReportingOverflow(destination.amountSats)
            guard !overflow else { return .uncertain }
            actual = sum
        }
        if movement.status == "successful", actual > 0 {
            let receipt = SendReceipt(amount: actual, paymentHash: hash, movementID: movement.id,
                                      fee: movement.offchainFeeSats, proof: .actualMovement)
            if snapshot.movementIDs.contains(movement.id) { return .alreadyPaid(receipt) }
            guard actual == amount else { return .uncertain }
            return .paid(receipt)
        }
        // A hash alone cannot identify the reviewed amount of an amountless invoice.
        guard actual == amount else { return .uncertain }
        if ["failed", "canceled"].contains(movement.status) { return .failed }
        return .uncertain
    }

    /// The SDK-returned txid binds the result; destination/time-only candidates remain unproven.
    static func offboardReceipt(_ receipt: SendReceipt, payment: SendPayment, movements: [Movement]) -> SendReceipt {
        guard receipt.proof == .submitReturnedTxid, let txid = receipt.txid else { return receipt }
        struct Metadata: Decodable { var offboard_txid: String; var offboard_tx: String }
        let matches = movements.filter {
            $0.subsystemName == "bark.offboard" && $0.subsystemKind == (payment.max ? "offboard" : "send_onchain")
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
        if var receipt = attempt.receipt, [.submitReturnedTxid, .preparedTxid].contains(receipt.proof), let txid = receipt.txid {
            if let tx = transactions.first(where: { $0.txid == txid }) {
                receipt.status = tx.confirmation == nil ? .submitted : .confirmed
                receipt.fee = tx.onchainFeeSats ?? receipt.fee
                if tx.confirmation != nil { receipt.broadcastRetryPending = nil }
            } else {
                // External offboards aren't in BDK's wallet history (FFI handoff item 15).
                receipt.status = attempt.payment.source == .offchain ? .submitted : .unlocated
            }
            return .paid(receipt)
        }
        if let txid = attempt.broadcastingTxid, let quote = attempt.quote,
           quote.prepared?.txid == txid, attempt.payment.method == "onchain", attempt.payment.source == .onchain {
            guard let tx = transactions.first(where: { $0.txid == txid }) else { return .uncertain }
            return .paid(.init(amount: quote.amount, txid: txid,
                               status: tx.confirmation == nil ? .submitted : .confirmed,
                               fee: quote.fee, proof: .preparedTxid,
                               // BDK records the spend before submission; cached presence cannot prove acceptance.
                               broadcastRetryPending: tx.confirmation == nil ? true : nil))
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

    private static func timestamp(_ value: String) -> Date? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.date(from: value) ?? ISO8601DateFormatter().date(from: value)
    }
}

/// Read only the outputs of BDK's consensus transaction; malformed evidence must never unlock Send.
enum SendTransaction {
    static func amount(to address: String, hex: String) throws -> UInt64 {
        guard let script = PaymentEncoding.bitcoinScript(address), hex.count % 2 == 0 else {
            throw SendFailure.unresolved
        }
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
        for _ in 0..<inputs {
            _ = try take(36)
            _ = try take(size())
            _ = try take(4)
        }
        var amount: UInt64 = 0
        let outputs = try size()
        for _ in 0..<outputs {
            let value = try integer(8)
            let outputScript = try take(size())
            if outputScript == script {
                let (sum, overflow) = amount.addingReportingOverflow(value)
                guard !overflow else { throw SendFailure.unresolved }
                amount = sum
            }
        }
        if witness {
            for _ in 0..<inputs {
                let items = try size()
                for _ in 0..<items { _ = try take(size()) }
            }
        }
        _ = try take(4)
        guard cursor == bytes.count else { throw SendFailure.unresolved }
        return amount
    }
}
