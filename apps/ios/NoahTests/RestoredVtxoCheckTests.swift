import Foundation
@testable import Noah
import Testing

/// A restored backup's spendable VTXOs are checked with the server once, after a sync; a VTXO left unanswered keeps
/// the check for the next pass, up to `RestoredVtxoCheck.maxPasses`.
struct RestoredVtxoCheckTests {
    typealias Locked = RecurringExecutorTests.Locked
    struct Unanswered: Error {}

    private let root = FileManager.default.temporaryDirectory.appending(path: "vtxo-check-\(UUID().uuidString)")
    private var marker: URL { root.appending(path: RestoreTransaction.vtxoCheckName) }

    private func steps(_ calls: Locked<[String]>, ids: [String] = ["a", "b"], failing: Set<String> = []) -> RestoredVtxoCheck.Steps {
        .init(sync: { calls.withLock { $0.append("sync") } },
              spendableIDs: { ids },
              adopt: { id in
                  calls.withLock { $0.append(id) }
                  if failing.contains(id) { throw Unanswered() }
              })
    }

    private func leaveMarker(_ text: String = "") throws {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try Data(text.utf8).write(to: marker)
    }

    @Test func withoutARestoreNothingRuns() async throws {
        let calls = Locked<[String]>([])
        #expect(try await RestoredVtxoCheck.run(marker: marker, steps(calls)) == 0)
        #expect(calls.withLock { $0 }.isEmpty)
    }

    @Test func syncsThenChecksEachSpendableVtxoOnce() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        try leaveMarker()
        let calls = Locked<[String]>([])
        #expect(try await RestoredVtxoCheck.run(marker: marker, steps(calls)) == 0)
        #expect(calls.withLock { $0 } == ["sync", "a", "b"])
        #expect(!FileManager.default.fileExists(atPath: marker.path))
        // Done: the next pass does nothing.
        #expect(try await RestoredVtxoCheck.run(marker: marker, steps(calls)) == 0)
        #expect(calls.withLock { $0 } == ["sync", "a", "b"])
    }

    @Test func anUnansweredVtxoDoesNotStopTheOthersAndIsCheckedAgain() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        try leaveMarker()
        let calls = Locked<[String]>([])
        #expect(try await RestoredVtxoCheck.run(marker: marker, steps(calls, failing: ["a"])) == 1)
        #expect(calls.withLock { $0 } == ["sync", "a", "b"])
        #expect(try String(contentsOf: marker, encoding: .utf8) == "1")
        calls.withLock { $0 = [] }
        #expect(try await RestoredVtxoCheck.run(marker: marker, steps(calls)) == 0)
        #expect(calls.withLock { $0 } == ["sync", "a", "b"])
        #expect(!FileManager.default.fileExists(atPath: marker.path))
    }

    @Test func givesUpAfterTheLastPass() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        try leaveMarker()
        let calls = Locked<[String]>([])
        for pass in 1..<RestoredVtxoCheck.maxPasses {
            #expect(try await RestoredVtxoCheck.run(marker: marker, steps(calls, failing: ["a"])) == 1)
            #expect(try String(contentsOf: marker, encoding: .utf8) == String(pass))
        }
        #expect(try await RestoredVtxoCheck.run(marker: marker, steps(calls, failing: ["a"])) == 1)
        #expect(!FileManager.default.fileExists(atPath: marker.path))
    }

    @Test func aFailedSyncKeepsTheCheckWithoutCountingAPass() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        try leaveMarker()
        let calls = Locked<[String]>([])
        var offline = steps(calls)
        offline.sync = { throw Unanswered() }
        await #expect(throws: Unanswered.self) { try await RestoredVtxoCheck.run(marker: marker, offline) }
        #expect(calls.withLock { $0 }.isEmpty)
        #expect(try String(contentsOf: marker, encoding: .utf8).isEmpty)
    }
}
