import Foundation
import Testing
@testable import Noah

struct BtcMapLimitsTests {
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
        let repository = BtcMapRepository(cacheURL: cache, fetch: { try await requests.fetch($0) })
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
