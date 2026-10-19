import Testing

@testable import Noah

struct AppVariantTests {
    @Test func appGroupMatchesReactNativeIdentifiers() {
        #expect(AppVariant.mainnet.appGroup == "group.com.noahwallet.mainnet")
        #expect(AppVariant.signet.appGroup == "group.com.noahwallet.signet")
        #expect(AppVariant.regtest.appGroup == "group.com.noahwallet.regtest")
    }
}
