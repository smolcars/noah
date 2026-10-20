import Testing
import Security

@testable import Noah

struct LaunchStateTests {
    struct Source: Decodable {
        let name: String
        let read: String
        let raw: String?
        var result: KeychainPassword.ReadResult {
            switch read {
            case "found": .found(raw!)
            case "absent": .notFound
            case "conflict": .unavailable(errSecDuplicateItem)
            case "invalidEncoding": .unavailable(errSecDecode)
            default: .unavailable(errSecInteractionNotAllowed)
            }
        }
    }
    struct Input: Decodable {
        let sources: [Source]
        let walletDatabaseExists: Bool
        let databaseFingerprint: String
        let multipleDatabases: Bool
    }
    struct Expected: Decodable { let outcome: String; let reason: String }

    static func checkFixture(_ name: String, count: Int) throws {
        let cases = try Fixtures.load(name, input: Input.self, expected: Expected.self)
        #expect(cases.count == count)
        for item in cases {
            let sources = item.input.sources.map(\.result)
            let result = LaunchState.inspect(sources: sources, walletDatabaseExists: item.input.walletDatabaseExists,
                fingerprintMatches: item.input.databaseFingerprint != "mismatch", multipleDatabases: item.input.multipleDatabases)
            #expect(result.state.rawValue == item.expected.outcome, "\(item.name)")
            #expect(result.reason.rawValue == item.expected.reason, "\(item.name)")
        }
    }

    @Test func matchesSharedFixture() throws { try Self.checkFixture("launch-state", count: 6) }

    @Test func corruptKeychainValuesNeverCountAsAbsent() {
        #expect(LaunchState.SeedStatus(.found("")) == .invalid)
        #expect(LaunchState.SeedStatus(.found("abandon")) == .invalid)
        #expect(LaunchState.SeedStatus(.notFound) == .absent)
        #expect(LaunchState.SeedStatus(.unavailable(-25308)) == .unavailable)
    }
}
