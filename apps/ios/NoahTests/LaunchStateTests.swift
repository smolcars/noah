import Testing

@testable import Noah

struct LaunchStateTests {
    struct Input: Decodable {
        let seed: String
        let walletDatabaseExists: Bool
    }

    @Test func matchesSharedFixture() throws {
        let cases = try Fixtures.load("launch-state", input: Input.self, expected: String.self)
        #expect(cases.count == 6)
        for testCase in cases {
            let seed = try #require(LaunchState.SeedStatus(rawValue: testCase.input.seed))
            let state = LaunchState.resolve(
                seed: seed, walletDatabaseExists: testCase.input.walletDatabaseExists)
            #expect(state.rawValue == testCase.expected, "\(testCase.name)")
        }
    }

    @Test func emptyKeychainValueCountsAsAbsent() {
        #expect(LaunchState.SeedStatus(.found("")) == .absent)
        #expect(LaunchState.SeedStatus(.found("abandon")) == .present)
        #expect(LaunchState.SeedStatus(.notFound) == .absent)
        #expect(LaunchState.SeedStatus(.unavailable(-25308)) == .unavailable)
    }
}
