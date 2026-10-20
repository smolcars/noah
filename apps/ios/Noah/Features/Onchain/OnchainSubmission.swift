import BarkBoltFFI

/// Call-specific v0.4.0 codes only. Unclassified upstream errors remain uncertain.
enum OnchainSubmission {
    static func preBroadcast(_ error: any Error, action: OnchainAction) -> OnchainFailure? {
        if let failure = error as? OnchainFailure,
            [.claimFeeChanged, .cancellationRefused].contains(failure) { return failure }
        guard let code = (error as? BarkError)?.code else { return nil }
        // error.rs maps BDK CreateTxError. boardAmount/boardAll build before sign/store/cosign.
        // core/wallet.rs refresh/start/cancel validation and drainExits errors still use `other`.
        // Claim broadcast errors are wrapped as OnchainFailure.uncertain at their call site.
        if action == .board, [.insufficientFunds, .dust].contains(code) { return .constructionFailed }
        return nil
    }
}
