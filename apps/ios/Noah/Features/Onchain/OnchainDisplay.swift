import Foundation

/// Read-only presentation; never used for exit eligibility or submission.
enum OnchainDisplay {
    static func currentSelection(_ vtxos: [VtxoModel], ids: Set<String>) -> [VtxoModel] {
        vtxos.filter { ids.contains($0.id) }
    }

    static func remaining(current: UInt32?, target: UInt32?, locale: String) -> String {
        guard let current, let target else { return L10n.text("Unknown", locale: locale) }
        guard target > current else { return L10n.text("Now", locale: locale) }
        return L10n.plural("{n} blocks", count: Int(target - current), locale: locale)
    }

    static func block(_ block: ExitDetails.Block) -> String { "\(block.height) (\(block.hash))" }

    static func explorerURL(_ anchor: String, variant: AppVariant) -> URL? {
        guard variant != .regtest, let txid = anchor.split(separator: ":").first, !txid.isEmpty else { return nil }
        return URL(string: "https://mempool.space/" + (variant == .signet ? "signet/" : "") + "tx/" + txid)
    }
}

extension ExitDetails {
    var displayClaimableHeight: UInt32? { claimable_height ?? claimable_since?.height }
}
