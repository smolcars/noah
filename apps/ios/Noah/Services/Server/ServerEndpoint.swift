import Foundation

/// Noah server per variant (`getServerEndpoint` in `client/src/constants.ts`).
enum ServerEndpoint {
    static func baseURL(for variant: AppVariant) -> URL {
        switch variant {
        case .mainnet: URL(string: "https://noahwallet.io")!
        case .signet: URL(string: "https://signet.noahwallet.io")!
        case .regtest: URL(string: "http://localhost:3000")!
        }
    }
}
