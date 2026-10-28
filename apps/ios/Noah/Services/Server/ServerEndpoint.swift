import Foundation

/// Noah server per variant (`getServerEndpoint` in `client/src/constants.ts`).
enum ServerEndpoint {
    static func priceURLs(base: URL) -> (current: URL, historical: URL) {
        (base.appending(path: "v0/prices"), base.appending(path: "v0/historical-price"))
    }

    static func baseURL(
        for variant: AppVariant, environment: [String: String] = ProcessInfo.processInfo.environment,
        info: [String: Any] = Bundle.main.infoDictionary ?? [:]) -> URL {
        switch variant {
        case .mainnet: return URL(literal: "https://noahwallet.io")
        case .signet: return URL(literal: "https://signet.noahwallet.io")
        case .regtest:
            // Like EXPO_PUBLIC_REGTEST_SERVER_URL: point regtest at another local server.
            if let override = environment["NOAH_REGTEST_SERVER_URL"], let url = URL(string: override) {
                return url
            }
            return RegtestHost.serverURL(info: info) ?? URL(literal: "http://localhost:3000")
        }
    }
}

/// Regtest endpoints baked in at build time, so a physical device can reach the
/// stack on a developer's machine: `xcodebuild NOAH_REGTEST_HOST=192.168.x.y
/// NOAH_REGTEST_SERVER_URL=http://192.168.x.y:3000`. Mirrors RN's
/// EXPO_PUBLIC_REGTEST_URL / EXPO_PUBLIC_REGTEST_SERVER_URL and Android's -PnoahRegtestHost.
/// `NOAH_REGTEST_ARK_URL` / `NOAH_REGTEST_BITCOIND_URL` replace the host's default
/// :3535 / :18443 endpoints, e.g. for a second stack on other ports. Only regtest reads them.
enum RegtestHost {
    static func host(info: [String: Any] = Bundle.main.infoDictionary ?? [:]) -> String {
        nonEmpty(info["NoahRegtestHost"]) ?? "localhost"
    }

    static func serverURL(info: [String: Any] = Bundle.main.infoDictionary ?? [:]) -> URL? {
        nonEmpty(info["NoahRegtestServerURL"]).flatMap(URL.init(string:))
    }

    static func arkURL(info: [String: Any] = Bundle.main.infoDictionary ?? [:]) -> String? {
        nonEmpty(info["NoahRegtestArkURL"])
    }

    static func bitcoindURL(info: [String: Any] = Bundle.main.infoDictionary ?? [:]) -> String? {
        nonEmpty(info["NoahRegtestBitcoindURL"])
    }

    private static func nonEmpty(_ value: Any?) -> String? {
        guard let value = (value as? String)?.trimmingCharacters(in: .whitespaces), !value.isEmpty else { return nil }
        return value
    }
}
