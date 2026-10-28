import Foundation
@testable import Noah
import Testing

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
}
