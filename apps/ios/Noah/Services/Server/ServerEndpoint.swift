import Foundation

/// Noah server per variant (`getServerEndpoint` in `client/src/constants.ts`).
enum ServerEndpoint {
    static func baseURL(for variant: AppVariant, environment: [String: String] = ProcessInfo.processInfo.environment) -> URL {
        switch variant {
        case .mainnet: return URL(string: "https://noahwallet.io")!
        case .signet: return URL(string: "https://signet.noahwallet.io")!
        case .regtest:
            // Like EXPO_PUBLIC_REGTEST_SERVER_URL: point regtest at another local server.
            if let override = environment["NOAH_REGTEST_SERVER_URL"], let url = URL(string: override) {
                return url
            }
            return URL(string: "http://localhost:3000")!
        }
    }
}
