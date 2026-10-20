import Foundation
import Testing

@testable import Noah

struct ArkInfoEndpointTests {
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
