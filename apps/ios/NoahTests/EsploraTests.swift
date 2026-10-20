import BarkBoltFFI
import Foundation
import Testing
@testable import Noah

struct EsploraTests {
    private static let genesis = "00000008819873e925422c1ff0f99f7cc9bbb232af63a077a480a3633bee1ef6"
    private static let previous = EsploraSettings.Selection(endpoint: "https://old.example/api", variant: "signet")
    private static let candidate = EsploraSettings.Selection(endpoint: "https://new.example/api", variant: "signet")
    private final class Memory: @unchecked Sendable {
        private let lock = NSLock()
        private var values: [String: String] = [:]
        var store: EsploraSettings { EsploraSettings(read: { key in self.lock.withLock { self.values[key] } }, write: { key, value in
            self.lock.withLock { self.values[key] = value }
        }) }
        func imported(_ value: String) throws {
            struct State: Encodable { let endpointOverride: String }
            let raw = String(decoding: try JSONEncoder().encode(PersistedEnvelope(state: State(endpointOverride: value), version: 0)), as: UTF8.self)
            lock.withLock { values["esplora-storage"] = raw }
        }
    }
    private actor Calls {
        var values: [String] = []
        func add(_ value: String) { values.append(value) }
    }
    @Test func sharedNormalizationNetworkAndTipFixtures() throws {
        struct Fixture: Decodable {
            struct URLCase: Decodable { var input: String; var endpoint: String? }
            struct Genesis: Decodable { var value: String; var variant: String; var valid: Bool }
            struct Tip: Decodable { var value: String; var valid: Bool }
            var normalization: [URLCase]; var genesis: [Genesis]; var tipHeight: [Tip]
        }
        let fixture = try Fixtures.decode("esplora", as: Fixture.self)
        #expect(fixture.normalization.count + fixture.genesis.count + fixture.tipHeight.count == 24)
        for test in fixture.normalization { #expect((try? EsploraEndpoint.normalize(test.input)) == test.endpoint) }
        for test in fixture.genesis { #expect(((try? EsploraEndpoint.validateGenesis(test.value, variant: AppVariant(rawValue: test.variant)!)) != nil) == test.valid) }
        for test in fixture.tipHeight { #expect(((try? EsploraEndpoint.validateTip(test.value)) != nil) == test.valid) }
    }
    @Test func effectiveConfigPerVariant() {
        for variant in AppVariant.allCases {
            let config = WalletConfiguration.effective(for: variant, endpoint: "custom.example/api/")
            #expect(config.esplora == (variant == .regtest ? nil : "https://custom.example/api"))
            #expect(config.arkServer == WalletConfiguration.base(for: variant).arkServer)
            #expect(WalletConfiguration.effective(for: variant, endpoint: "https://user:pass@example.com").esplora == WalletConfiguration.base(for: variant).esplora)
        }
    }
    @Test func connectionTestFetchesBothResourcesAndRejectsWrongNetwork() async throws {
        let calls = Calls()
        let endpoint = try await EsploraEndpoint.validate("example.com/api/blocks/tip/hash", variant: .signet) { url in
            await calls.add(url)
            return url.hasSuffix("/0") ? Self.genesis : "123\n"
        }
        #expect(endpoint == "https://example.com/api")
        #expect(await calls.values == [endpoint + "/block-height/0", endpoint + "/blocks/tip/height"])
        await #expect(throws: EsploraEndpoint.Failure.self) {
            try await EsploraEndpoint.validate("example.com", variant: .mainnet) { _ in Self.genesis }
        }
    }
    @Test func invalidImportedValuesAreIgnoredAndNeedAttention() async throws {
        for input in ["https://user:pass@example.com", "https://example.com?secret=1", "https://wrong-network.example"] {
            let memory = Memory(); try memory.imported(input)
            let endpoint = try await memory.store.resolve(for: .mainnet) { _ in Self.genesis }
            #expect(endpoint == nil)
            #expect(memory.store.selection(for: .mainnet).input == input)
            #expect(memory.store.selection(for: .mainnet).attention != nil)
        }
    }
    @Test func validImportIsValidatedAndActivated() async throws {
        let memory = Memory(); try memory.imported(" custom.example/api/ ")
        let endpoint = try await memory.store.resolve(for: .signet) { $0.hasSuffix("/0") ? Self.genesis : "42" }
        #expect(endpoint == "https://custom.example/api")
        #expect(memory.store.selection(for: .signet).attention == nil)
    }
    @Test func regtestNeverFetchesOrAppliesImportedEndpoint() async throws {
        let memory = Memory(); try memory.imported("custom.example")
        let endpoint = try await memory.store.resolve(for: .regtest) { _ in Issue.record("regtest fetched Esplora"); return "" }
        #expect(endpoint == nil)
        #expect(memory.store.selection(for: .regtest).attention != nil)
    }
    @Test func processDeathWithPendingChangeUsesPreviousEndpoint() async throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "esplora-\(UUID())")
        defer { try? FileManager.default.removeItem(at: root) }
        let path = root.appending(path: "mmkv").path
        let first = EsploraSettings(storage: try SettingsStorage(rootPath: path))
        try first.save(.init(active: Self.previous, pending: Self.candidate))
        let relaunched = EsploraSettings(storage: try SettingsStorage(rootPath: path))
        let endpoint = try await relaunched.resolve(for: .signet) { _ in Issue.record("pending endpoint fetched"); return "" }
        #expect(endpoint == Self.previous.endpoint)
        #expect(try relaunched.record()?.pending == nil)
    }
    @Test func reopenFailureRevertsBeforeReopeningPrevious() async throws {
        let memory = Memory(); try memory.store.save(.init(active: Self.previous))
        let calls = Calls()
        await #expect(throws: EsploraEndpoint.Failure.self) {
            try await memory.store.change(to: Self.candidate) { selection, commit in
                await calls.add(selection.endpoint!)
                if selection == Self.candidate {
                    #expect(try memory.store.record() == .init(active: Self.previous, pending: Self.candidate))
                    throw EsploraEndpoint.Failure(message: "fake failed open")
                }
                #expect(try memory.store.record() == .init(active: Self.previous))
                try commit()
            }
        }
        #expect(await calls.values == [Self.candidate.endpoint!, Self.previous.endpoint!])
        #expect(try memory.store.record() == .init(active: Self.previous))
    }
    @Test func successfulReopenCommitsAfterOpening() async throws {
        let memory = Memory(); try memory.store.save(.init(active: Self.previous))
        try await memory.store.change(to: Self.candidate) { _, commit in
            #expect(try memory.store.record()?.active == Self.previous)
            try commit()
        }
        #expect(try memory.store.record() == .init(active: Self.candidate))
    }
    @Test func storageFailureReopensPreviousWithoutPublishingCandidate() async throws {
        for phase in ["pending", "commit"] {
            let memory = Memory(); try memory.store.save(.init(active: Self.previous))
            let faulty = EsploraSettings(read: memory.store.read, write: { key, value in
                let record = try JSONDecoder().decode(EsploraSettings.Record.self, from: Data(value.utf8))
                if phase == "pending" && record.pending != nil || phase == "commit" && record.active == Self.candidate {
                    throw EsploraEndpoint.Failure(message: "fake storage failure")
                }
                try memory.store.write(key, value)
            })
            let calls = Calls()
            await #expect(throws: EsploraEndpoint.Failure.self) {
                try await faulty.change(to: Self.candidate) { selection, commit in
                    await calls.add(selection.endpoint!)
                    try commit()
                }
            }
            #expect(await calls.values == (phase == "pending" ? [] : [Self.candidate.endpoint!, Self.previous.endpoint!]))
            #expect(try memory.store.record() == .init(active: Self.previous))
        }
    }

    @Test func persistentStorageFailureStillReopensPreviousAndResolvesPending() async throws {
        let memory = Memory(); try memory.store.save(.init(active: Self.previous))
        let lock = NSLock()
        nonisolated(unsafe) var writes = 0
        let faulty = EsploraSettings(read: memory.store.read, write: { key, value in
            try lock.withLock {
                writes += 1
                if writes > 1 { throw EsploraEndpoint.Failure(message: "disk full") }
                try memory.store.write(key, value)
            }
        })
        let calls = Calls()
        await #expect(throws: EsploraEndpoint.Failure.self) {
            try await faulty.change(to: Self.candidate) { selection, commit in
                await calls.add(selection.endpoint!)
                try commit()
            }
        }
        #expect(await calls.values == [Self.candidate.endpoint!, Self.previous.endpoint!])
        #expect(try await faulty.resolve(for: .signet) == Self.previous.endpoint)
    }

    @Test func interruptedCommitRetainsPreviousSelection() async throws {
        let memory = Memory(); try memory.store.save(.init(active: Self.previous))
        await #expect(throws: EsploraEndpoint.Failure.self) {
            try await memory.store.change(to: Self.candidate) { selection, commit in
                if selection == Self.candidate { throw CancellationError() }
                try commit()
            }
        }
        #expect(try memory.store.record() == .init(active: Self.previous))
    }
}
