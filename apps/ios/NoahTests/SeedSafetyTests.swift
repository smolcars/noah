import BarkBoltFFI
import Foundation
import Security
import SQLite3
import Testing

@testable import Noah

struct SeedSafetyTests {
    @Test func validatesWordsChecksumCountAndNormalization() throws {
        let seed = try generateMnemonic()
        let decorated = " \n" + seed.uppercased().replacingOccurrences(of: " ", with: "\t ") + "\n"
        let normalized = try StoredSeed.normalized(decorated)
        let same = normalized == seed
        #expect(same)
        // Public BIP39 entropy-zero vector, built without printing any words.
        let words = RecoveryPhraseInput.wordList
        let vector24 = Array(repeating: words[0], count: 23) + [words[102]]
        let accepts24 = (try? StoredSeed.normalized(vector24.joined(separator: " "))) != nil
        #expect(accepts24)
        for value in ["", "not-a-bip39-word", Array(repeating: words[0], count: 12).joined(separator: " "),
                      Array(repeating: words[0], count: 15).joined(separator: " ")] {
            let rejected = (try? StoredSeed.normalized(value)) == nil
            #expect(rejected)
        }
    }

    @Test func launchSafetyFixture() throws {
        let cases = try Fixtures.load("launch-seed-safety", input: LaunchStateTests.Input.self, expected: String.self)
        #expect(cases.count == 8)
        for item in cases {
            let status = try #require(LaunchState.SeedStatus(rawValue: item.input.seed))
            #expect(LaunchState.resolve(seed: status, walletDatabaseExists: item.input.walletDatabaseExists).rawValue == item.expected)
        }
    }

    @Test func multipleKeychainCandidatesAreNeverChosenOrChanged() throws {
        let service = "com.noah.mnemonic.test-\(UUID().uuidString)"
        let first = KeychainPassword(service: service, account: "noah")
        let second = KeychainPassword(service: service, account: "native")
        defer { first.remove(); second.remove() }
        try first.save(try generateMnemonic())
        let initiallyReadable: Bool
        if case .found = first.read() { initiallyReadable = true } else { initiallyReadable = false }
        #expect(initiallyReadable)
        try second.save(try generateMnemonic())
        let blocked = first.read() == .unavailable(errSecDuplicateItem)
        #expect(blocked)
        #expect(LaunchState.SeedStatus(first.read()) == .conflict)
        second.remove()
        let preserved: Bool
        if case .found = first.read() { preserved = true } else { preserved = false }
        #expect(preserved)
    }

    @Test(arguments: [false, true]) func invalidStoredSeedNeverCreatesOrReplaces(hasData: Bool) async {
        let fake = WalletCreatorTests.Fake()
        fake.seed = .found("")
        fake.database = hasData
        await #expect(throws: StoredSeed.Failure.invalid) {
            try await WalletCreator(dependencies: fake.dependencies()).createOrResume()
        }
        let untouched = fake.seed == .found("") && fake.database == hasData && fake.generated.isEmpty && fake.opened.isEmpty && fake.created.isEmpty
        #expect(untouched)
    }

    @Test func invalidStoredSeedStopsRestoreBeforeAnyNetworkOrFiles() async throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "invalid-restore-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let location = WalletDataLocation(variant: .regtest, documentsDirectory: root)
        let item = KeychainPassword(service: "com.noah.mnemonic.test-\(UUID().uuidString)", account: "noah")
        defer { item.remove() }
        try item.save("")
        let restorer = WalletRestorer(variant: .regtest, session: WalletSession(variant: .regtest, location: location),
            location: location, seed: item, makeClient: { _ in preconditionFailure("Invalid seed must not authenticate") })
        let phrase = try generateMnemonic()
        await #expect(throws: StoredSeed.Failure.invalid) { try await restorer.restore(mnemonic: phrase) }
        let unchanged = item.read() == .found("")
        #expect(unchanged)
        #expect(!FileManager.default.fileExists(atPath: root.path))
    }

    @Test func fingerprintConflictDoesNotWriteWalletFiles() async throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "seed-conflict-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let location = WalletDataLocation(variant: .regtest, documentsDirectory: root)
        try FileManager.default.createDirectory(at: location.directory, withIntermediateDirectories: true)
        var db: OpaquePointer?
        #expect(sqlite3_open(location.database.path, &db) == SQLITE_OK)
        #expect(sqlite3_exec(db, "CREATE TABLE bark_properties (id INTEGER PRIMARY KEY, network TEXT, fingerprint TEXT); INSERT INTO bark_properties VALUES (1, 'regtest', '00000000');", nil, nil, nil) == SQLITE_OK)
        sqlite3_close(db)
        let before = try Data(contentsOf: location.database)
        let seed = try generateMnemonic()
        let session = WalletSession(variant: .regtest, location: location)
        await #expect(throws: StoredSeed.Failure.conflict) {
            try await session.openExisting(mnemonic: seed, runDaemon: false, appVersion: "test")
        }
        let unchanged = try Data(contentsOf: location.database) == before
        #expect(unchanged)
        #expect(try FileManager.default.contentsOfDirectory(atPath: location.directory.path) == ["db.sqlite"])
    }
}
