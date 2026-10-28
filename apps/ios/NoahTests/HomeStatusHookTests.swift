import Foundation
@testable import Noah
import Testing

@Suite(.timeLimit(.minutes(1)))
struct HomeStatusHookTests {
    @Test func backupSnapshotsFollowSavedStateWithoutChangingUploadDeduplication() async throws {
        let world = BackupCoordinatorTests.World()
        let coordinator = BackupCoordinator(dependencies: world.dependencies(clock: .init()))
        var snapshots = coordinator.statusUpdates.stream().makeAsyncIterator()
        try await coordinator.flush(.manual)
        let uploaded = await snapshots.next()
        #expect(uploaded == world.state)
        #expect(uploaded?.lastBackupStatus == .success)
        #expect(world.calls.count == 1)
        try await coordinator.flush(.manual)
        let unchanged = await snapshots.next()
        #expect(unchanged == world.state)
        #expect(unchanged?.lastBackupStatus == .idle)
        #expect(unchanged?.lastBackupAt == uploaded?.lastBackupAt)
        #expect(world.calls.count == 2 && world.calls.last == "sha-1")
    }

    @Test func journalEventsFollowDurableWritesWithoutChangingReceiptsOrRepeatProtection() async throws {
        let directory = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appending(path: "attempt.json")
        let journal = OnchainOperationJournal(file: file)
        let events = journal.changes.stream()
        let actionCalls = FakeSeed(nil)
        let quote = OnchainJournalTests.quote
        let message = try await journal.perform(quote) {
            #expect(try JSONDecoder().decode([OnchainOperationJournal.Attempt].self, from: Data(contentsOf: file)).first?.receipt == nil)
            try actionCalls.save("called")
            return .init(message: "submitted", txid: "txid")
        }
        var iterator = events.makeAsyncIterator()
        #expect(await iterator.next() != nil)
        let receipt = try await journal.load()?.receipt
        #expect(message == "submitted" && receipt?.txid == "txid")
        #expect(try JSONDecoder().decode([OnchainOperationJournal.Attempt].self, from: Data(contentsOf: file)).first?.receipt?.txid == "txid")
        await #expect(throws: OnchainFailure.busy) {
            _ = try await journal.perform(quote) { Issue.record("Must not repeat"); return "duplicate" }
        }
        #expect(actionCalls.current == "called")
    }
}
