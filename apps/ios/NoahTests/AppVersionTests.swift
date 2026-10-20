import Foundation
import Testing
@testable import Noah

@MainActor
struct AppVersionTests {
    struct Input: Decodable {
        let currentVersion: String
        let response: Components.Schemas.AppVersionInfo?
    }
    @Test func matchesRNResponseAndFailureFixtures() async throws {
        struct Offline: Error {}
        let cases = try Fixtures.load("app-version", input: Input.self, expected: Components.Schemas.AppVersionInfo.self)
        #expect(cases.count == 4)
        for fixture in cases {
            var calls = 0
            let check = AppVersionCheck(currentVersion: fixture.input.currentVersion) { version in
                calls += 1
                #expect(version == fixture.input.currentVersion)
                guard let response = fixture.input.response else { throw Offline() }
                return response
            }
            await check.checkOnce()
            await check.checkOnce()
            #expect(calls == 1)
            #expect(check.updateRequired == fixture.expected.updateRequired)
            #expect(check.minimumVersion == fixture.expected.minimumRequiredVersion)
        }
    }

    @Test func serverDecisionIsCachedAndNeverBlocks() async {
        var calls = 0
        let check = AppVersionCheck(currentVersion: "0.2.0") { version in
            calls += 1
            #expect(version == "0.2.0")
            return .init(minimumRequiredVersion: "1.0.0", updateRequired: true)
        }
        await check.checkOnce()
        await check.checkOnce()
        #expect(calls == 1)
        #expect(check.updateRequired)
        #expect(check.minimumVersion == "1.0.0")
        #expect(!check.isChecking)
    }

    @Test func failureAllowsUseAndIsNotRetried() async {
        struct Offline: Error {}
        var calls = 0
        let check = AppVersionCheck { _ in calls += 1; throw Offline() }
        await check.checkOnce()
        await check.checkOnce()
        #expect(calls == 1)
        #expect(!check.updateRequired)
        #expect(check.minimumVersion == "0.0.1")
    }

    @Test func updateDestinationsMatchRN() {
        #expect(AppVersionCheck.updateURL(variant: .mainnet).absoluteString.hasSuffix("YQW6fu9w"))
        #expect(AppVersionCheck.updateURL(variant: .signet).absoluteString.hasSuffix("E4P44dXF"))
        #expect(AppVersionCheck.updateURL(variant: .regtest) == AppVersionCheck.updateURL(variant: .signet))
    }
}
