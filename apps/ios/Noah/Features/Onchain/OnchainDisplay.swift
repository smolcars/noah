import Foundation

/// Read-only presentation; never used for exit eligibility or submission.
enum OnchainDisplay {
    static func block(_ block: ExitDetails.Block) -> String { "\(block.height) (\(block.hash))" }

    static func explorerURL(_ anchor: String, variant: AppVariant) -> URL? {
        guard variant != .regtest, let txid = anchor.split(separator: ":").first, !txid.isEmpty else { return nil }
        return URL(string: "https://mempool.space/" + (variant == .signet ? "signet/" : "") + "tx/" + txid)
    }
}

extension ExitDetails {
    var displayClaimableHeight: UInt32? { claimable_height ?? claimable_since?.height }
}
