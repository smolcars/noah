import Foundation

enum ArkInfoEndpoints {
    static func blockHeight(variant: AppVariant, esploraOverride: String?, regtestHost: String = RegtestHost.host(),
                            regtestBitcoindURL: String? = RegtestHost.bitcoindURL()) -> String {
        if variant != .regtest, let esploraOverride { return esploraOverride.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/blocks/tip/height" }
        switch variant {
        case .mainnet: return "https://mempool.second.tech/api/blocks/tip/height"
        case .signet: return "https://mempool.space/signet/api/blocks/tip/height"
        case .regtest: return regtestBitcoindURL ?? "http://\(regtestHost):18443"
        }
    }
}
