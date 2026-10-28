import BarkBoltFFI
import Foundation
@testable import Noah
import Testing

@MainActor struct BoardReviewLifetimeTests {
    actor Readiness {
        var count = 0
        var holdAt: Int
        var continuation: CheckedContinuation<Bool, Never>?
        init(holdAt: Int) { self.holdAt = holdAt }
        func ready() async -> Bool {
            count += 1
            if count == holdAt { return await withCheckedContinuation { continuation = $0 } }
            return true
        }
        func finish() { continuation?.resume(returning: true); continuation = nil }
    }
    @Test(arguments: [2, 3]) func backgroundDuringReadinessCannotPresentOrSubmit(holdAt: Int) async {
        let calls = OnchainModelTests.Calls()
        let readiness = Readiness(holdAt: holdAt)
        let cleanup = BoardPreparedTests.Calls()
        var dependencies = OnchainModelTests().dependencies(calls)
        dependencies.ready = { await readiness.ready() }
        dependencies.quote = { _, _, _, _ in BoardPreparedTests.quote }
        dependencies.discardReview = { try? await cleanup.discard($0) }
        let model = OnchainModel(dependencies: dependencies)
        let work = Task {
            await model.review(.board)
            if holdAt == 3 { await model.confirm() }
        }
        for _ in 0..<1000 where await readiness.continuation == nil { await Task.yield() }
        #expect(await readiness.continuation != nil)
        model.invalidate()
        await readiness.finish()
        await work.value
        #expect(model.quote == nil)
        #expect(await calls.mutations == 0)
        #expect(await cleanup.discarded.count == 1)
    }
    @Test func latePreparationIsDiscardedAfterNavigationAndSuspension() async {
        let calls = OnchainModelTests.Calls()
        let cleanup = BoardPreparedTests.Calls()
        var dependencies = OnchainModelTests().dependencies(calls)
        dependencies.quote = { _, _, _, _ in await calls.delayedQuote() }
        dependencies.discardReview = { try? await cleanup.discard($0) }
        let model = OnchainModel(dependencies: dependencies)
        let task = Task { await model.review(.board) }
        for _ in 0..<100 where await calls.continuation == nil { await Task.yield() }
        model.invalidate()
        await calls.finishQuote()
        await task.value
        #expect(model.quote == nil)
        #expect(await cleanup.discarded.count == 1)
        #expect(await calls.mutations == 0)
    }
}
