import BarkBoltFFI

/// Exact outer contexts only. Never search nested text: unknown errors retain uncertainty.
enum OnchainSubmission {
    static func preBroadcast(_ error: any Error, action: OnchainAction) -> OnchainFailure? {
        if let failure = error as? OnchainFailure,
            [.claimFeeChanged, .cancellationRefused].contains(failure) { return failure }
        guard let message = (error as? BarkError)?.message else { return nil }
        // FFI 0.3.0 contexts; drainExits and extractTxFromPsbt only construct/sign, never broadcast.
        // https://github.com/smolcars/bark-bolt-ffi/blob/v0.3.0/src/core/wallet.rs
        // https://github.com/smolcars/bark-bolt-ffi/blob/v0.3.0/src/functions.rs
        if action == .claim {
            if ["No claimable exits found", "no vtxo ids given; pass drain_all to claim every exit",
                "pass either drain_all or a list of vtxo ids, not both"].contains(message)
                || ["Drain exits failed: ", "Failed to get fee: ", "invalid PSBT: ",
                    "Failed to extract transaction: ", "invalid bitcoin address: "].contains(where: message.hasPrefix)
                || ["bitcoin", "testnet", "testnet4", "signet", "regtest"].contains(where: {
                    message.hasPrefix("bitcoin address is not valid on \($0): ")
                }) { return .constructionFailed }
        }
        if action == .refresh && message.hasPrefix("invalid VTXO id: ") { return .constructionFailed }
        if [.startExit, .claim, .cancel].contains(action) && message.hasPrefix("invalid vtxo id: ") {
            return .constructionFailed
        }
        // b.finish() precedes sign/record/broadcast. Board persistence/cosign/broadcast failures do not qualify.
        // https://gitlab.com/ark-bitcoin/bark/-/blob/bfbe6b7c3088a09dd4e81c4ac24cdc763a627a8c/bark/src/board.rs
        // https://gitlab.com/ark-bitcoin/bark/-/blob/bfbe6b7c3088a09dd4e81c4ac24cdc763a627a8c/bark/src/onchain/bdk.rs
        if action == .board,
            ["Board failed: error building tx: ", "Board all failed: error building tx: "].contains(where: message.hasPrefix) {
            return .constructionFailed
        }
        return nil
    }
}
