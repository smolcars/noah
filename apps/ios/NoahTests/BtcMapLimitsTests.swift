import Foundation
import Testing
@testable import Noah

struct BtcMapLimitsTests {
    struct Input: Decodable { let operation: String; let text: String }

    @Test func sharedDepthContract() throws {
        let place = Data(#"{"id":1,"lat":1,"lon":2,"icon":"cafe","name":"Cafe","updated_at":"today"}"#.utf8)
        for fixture in try Fixtures.load("btc-map-limits", input: Input.self, expected: String.self) {
            let data = Data(fixture.input.text.utf8)
            let result: String
            do {
                switch fixture.input.operation {
                case "snapshot": _ = try BtcMapSnapshot.parse(data)
                case "patch": _ = try BtcMapLimits.decodeRecords(BtcMapPatch.self, from: data)
                case "detail": _ = try BtcMapDetail.parse(place: data, comments: Data("[]".utf8))
                case "comments": _ = try BtcMapDetail.parse(place: place, comments: data)
                default: Issue.record("Unknown limits operation"); continue
                }
                result = "accepted"
            } catch BtcMapError.resourceLimit { result = "resourceLimit" }
            #expect(result == fixture.expected, "\(fixture.name)")
        }
    }

    private final class Clock: @unchecked Sendable {
        private let lock = NSLock()
        private var date = Date(timeIntervalSince1970: 1000)
        func now() -> Date { lock.withLock { date } }
        func advance(_ seconds: Double) { lock.withLock { date.addTimeInterval(seconds) } }
    }

    @Test func clockRollbackExpiresSyncAndPerMerchantOversizeCooldowns() async throws {
        actor Requests {
            var count = 0
            var reject = true
            func allow() { reject = false }
            func fetch(_ url: URL, place: Data) throws -> Data {
                count += 1
                if reject { throw BtcMapError.resourceLimit }
                return url.path.hasSuffix("/places") ? Data("[]".utf8) : place
            }
        }
        let folder = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let raw = Data(#"{"schemaVersion":1,"generatedAt":"today","source":"btcmap.org","places":[{"id":1,"lat":1,"lon":2,"icon":"cafe","name":"Cafe","updated_at":"today"}]}"#.utf8)
        let cache = folder.appending(path: "places")
        try raw.write(to: cache)
        let clock = Clock()
        let requests = Requests()
        let place = try #require(BtcMapSnapshot.parse(raw).places.first)
        let placeData = try JSONEncoder().encode(place)
        let repository = BtcMapRepository(cacheURL: cache, fetch: { try await requests.fetch($0, place: placeData) }, clock: { clock.now() })
        await #expect(throws: BtcMapError.resourceLimit) { try await repository.sync() }
        await #expect(throws: BtcMapError.resourceLimit) { try await repository.detail(for: place) }
        clock.advance(-1)
        await requests.allow()
        _ = try await repository.sync()
        _ = try await repository.detail(for: place)
        #expect(await requests.count == 4)
        clock.advance(2)
        _ = try await repository.sync()
        _ = try await repository.detail(for: place)
        #expect(await requests.count == 4)
        clock.advance(-3)
        _ = try await repository.sync()
        _ = try await repository.detail(for: place)
        #expect(await requests.count == 6)
    }

    @Test func limitsRejectBytesRecordsAndStrings() throws {
        #expect(throws: (any Error).self) { try BtcMapLimits.check(Data(count: BtcMapLimits.bytes + 1)) }
        try BtcMapLimits.check(String(repeating: "x", count: BtcMapLimits.stringBytes))
        #expect(throws: (any Error).self) { try BtcMapLimits.check(String(repeating: "x", count: BtcMapLimits.stringBytes + 1)) }
        let array = "[" + Array(repeating: "1", count: BtcMapLimits.records + 1).joined(separator: ",") + "]"
        #expect(throws: (any Error).self) { try BtcMapLimits.decodeRecords(Int.self, from: Data(array.utf8)) }
        let place = "{\"id\":1,\"lat\":1,\"lon\":2,\"icon\":\"cafe\",\"updated_at\":\"2026-01-01\",\"name\":\"" + String(repeating: "x", count: BtcMapLimits.stringBytes + 1) + "\"}"
        #expect(throws: (any Error).self) { try BtcMapLimits.decodeRecords(BtcMapPatch.self, from: Data("[\(place)]".utf8)) }
        #expect(throws: (any Error).self) { try BtcMapDetail.parse(place: Data(place.utf8), comments: Data("[]".utf8)) }
    }

    @Test func streamingStopsAtTheLimitWithoutWaitingForTheResponseToFinish() async throws {
        actor Bytes {
            var count = 0
            func next() -> UInt8? { count += 1; return count <= 100 ? 1 : nil }
        }
        let bytes = Bytes()
        let stream = AsyncStream<UInt8>(unfolding: { await bytes.next() })
        await #expect(throws: (any Error).self) { try await BtcMapRepository.collect(stream, limit: 16) }
        #expect(await bytes.count == 17)
        let exact = AsyncStream<UInt8> { continuation in
            for _ in 0..<16 { continuation.yield(1) }
            continuation.finish()
        }
        #expect(try await BtcMapRepository.collect(exact, limit: 16).count == 16)
    }

    @Test func encodedSizeMatchesFullEncodingIncludingUTF8EscapesAndMetadata() throws {
        let raw = Data(#"{"schemaVersion":1,"generatedAt":"é\n","source":"é\\\"","places":[{"id":1,"lat":1,"lon":2,"icon":"cafe","name":"é🚀\"\n","updated_at":"today"}]}"#.utf8)
        var snapshot = try BtcMapSnapshot.parse(raw)
        let place = try #require(snapshot.places.first)
        for count in [0, 1, 3] {
            snapshot.places = Array(repeating: place, count: count)
            #expect(try BtcMapLimits.encodedSize(snapshot) == JSONEncoder().encode(snapshot).count)
        }
    }

    @Test func mergedByteLimitIsCheckedBeforeWritingOrAdvancingCursor() async throws {
        let place = #"{"id":1,"lat":1,"lon":2,"icon":"cafe","name":"Cafe","updated_at":"today"}"#
            .replacingOccurrences(of: "Cafe", with: String(repeating: "x", count: BtcMapLimits.stringBytes))
        func entries(_ range: ClosedRange<Int>) -> String {
            range.map { place.replacingOccurrences(of: #""id":1"#, with: #""id":\#($0)"#) }.joined(separator: ",")
        }
        let original = Data((#"{"schemaVersion":1,"generatedAt":"today","source":"btcmap.org","places":["# + entries(1...2000) + "]}").utf8)
        let patches = Data(("[" + entries(2001...2100) + "]").utf8)
        actor Requests {
            var cursors: [String?] = []
            func fetch(_ url: URL, patches: Data) -> Data {
                cursors.append(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "updated_since" }?.value)
                return patches
            }
        }
        let folder = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let cache = folder.appending(path: "places")
        try original.write(to: cache)
        let requests = Requests()
        let repository = BtcMapRepository(cacheURL: cache, fetch: { await requests.fetch($0, patches: patches) })
        let initial = try await repository.load()
        await #expect(throws: BtcMapError.resourceLimit) { try await repository.sync(now: Date(timeIntervalSince1970: 1000)) }
        #expect(await requests.cursors == [initial.generatedAt])
        #expect(try await repository.load().places == initial.places)
        #expect(try await repository.load().generatedAt == initial.generatedAt)
        #expect(try Data(contentsOf: cache) == original)
        await #expect(throws: BtcMapError.resourceLimit) { try await repository.sync(now: Date(timeIntervalSince1970: 1300)) }
        #expect(await requests.cursors == [initial.generatedAt, initial.generatedAt])
    }

    @Test func oversizedSyncDoesNotRetryOrReplaceCacheAndHasCooldown() async throws {
        actor Requests {
            var count = 0
            func fetch(_ url: URL) throws -> Data { count += 1; throw BtcMapError.resourceLimit }
        }
        let folder = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let cache = folder.appending(path: "places")
        let requests = Requests()
        let repository = try BtcMapRepositoryTests.smallRepository(in: folder, fetch: { try await requests.fetch($0) })
        let initial = try await repository.load()
        let now = Date.now
        for offset in [0.0, 1, 299] {
            await #expect(throws: (any Error).self) { try await repository.sync(now: now.addingTimeInterval(offset)) }
        }
        #expect(await requests.count == 1)
        #expect(try await repository.load().generatedAt == initial.generatedAt)
        #expect(!FileManager.default.fileExists(atPath: cache.path))
        await #expect(throws: (any Error).self) { try await repository.sync(now: now.addingTimeInterval(300)) }
        #expect(await requests.count == 2)
    }}
