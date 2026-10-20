import Foundation
import Testing

@testable import Noah

struct ArkInfoEndpointTests {
    @Test func blockHeightMatchesRNDefaultsAndOverride() {
        #expect(ArkInfoEndpoints.blockHeight(variant: .mainnet, esploraOverride: nil) == "https://mempool.second.tech/api/blocks/tip/height")
        #expect(ArkInfoEndpoints.blockHeight(variant: .signet, esploraOverride: nil) == "https://mempool.space/signet/api/blocks/tip/height")
        for variant in [AppVariant.mainnet, .signet] {
            #expect(ArkInfoEndpoints.blockHeight(variant: variant, esploraOverride: "https://custom.example/api/") == "https://custom.example/api/blocks/tip/height")
        }
        #expect(ArkInfoEndpoints.blockHeight(variant: .regtest, esploraOverride: "https://ignored.example", regtestHost: "192.0.2.1") == "http://192.0.2.1:18443")
    }

    @Test func pricesUseTheConfiguredServerForEveryVariant() {
        for (variant, origin) in [(AppVariant.mainnet, "https://noahwallet.io"), (.signet, "https://signet.noahwallet.io"), (.regtest, "http://localhost:3000")] {
            let urls = ServerEndpoint.priceURLs(base: ServerEndpoint.baseURL(for: variant, environment: [:], info: [:]))
            #expect(urls.current.absoluteString == origin + "/v0/prices")
            #expect(urls.historical.absoluteString == origin + "/v0/historical-price")
        }
        let base = ServerEndpoint.baseURL(for: .regtest, environment: ["NOAH_REGTEST_SERVER_URL": "http://192.0.2.1:3000/proxy/"], info: [:])
        #expect(ServerEndpoint.priceURLs(base: base).current.absoluteString == "http://192.0.2.1:3000/proxy/v0/prices")
    }
}
