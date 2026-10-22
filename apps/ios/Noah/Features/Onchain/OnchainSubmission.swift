import BarkBoltFFI

/// Only typed errors that precede board acceptance can release its journal lock.
enum OnchainSubmission {
    static func boardFailure(_ error: any Error) -> OnchainFailure? {
        guard let code = (error as? BarkError)?.code else { return nil }
        switch code {
        case .belowMinimumBoardAmount: return .belowMinimum
        case .tooManyInputs: return .tooManyInputs
        case .preparedInputsUnavailable: return .reservedInputs
        case .preparedBoardChanged, .preparedTransactionNotFound: return .changed
        case .unsupportedOperation: return .notSynced
        case .insufficientFunds: return .insufficientFunds
        case .dust, .invalidAmount, .invalidFeeRate: return .constructionFailed
        default: return nil
        }
    }

    static func preBroadcast(_ error: any Error, action: OnchainAction) -> OnchainFailure? {
        if let failure = error as? OnchainFailure,
           [.claimFeeChanged, .cancellationRefused, .boardNotAttempted].contains(failure) { return failure }
        // Renew's eligibility check refuses before submitting anything.
        if action == .refresh, let failure = error as? OnchainFailure, failure == .changed { return failure }
        return action == .board ? boardFailure(error) : nil
    }

    static func boardReceipt(_ result: BoardResult, quote: OnchainQuote) throws -> OnchainReceipt {
        guard result.txid == quote.prepared?.txid else { throw OnchainFailure.uncertain }
        switch result.broadcast {
        case .notAttempted: throw OnchainFailure.boardNotAttempted
        case .accepted, .failed:
            var receipt = OnchainReceipt(message: OnchainReceipt.boardingMessage(fee: quote.chainFee),
                txid: result.txid, onchainFee: quote.chainFee)
            receipt.arkFee = quote.fee
            if case .failed = result.broadcast {
                receipt.broadcastRetryPending = true
                receipt.message = OnchainReceipt.broadcastFailedMessage
            }
            return receipt
        }
    }
}
