import Foundation
import Testing

@testable import Noah

struct ExitHistoryTests {
    @MainActor
    @Test func terminalDetailAndTimelineLoadDirectlyWithoutActiveOverviewEntry() async {
        let calls = OnchainModelTests.Calls()
        var dependencies = OnchainModelTests().dependencies(calls)
        dependencies.detail = { id in
            .init(id: id, amount: 30000, state: "Canceled", details: .init(kind: "canceled", tip_height: 101),
                  history: ["Start"], historyDetails: [.init(kind: "start", tip_height: 100)], claimable: false)
        }
        let model = OnchainModel(dependencies: dependencies, detailID: "canceled")
        await model.load()
        #expect(model.overview?.exits.isEmpty == true)
        #expect(model.exit?.id == "canceled")
        #expect(model.exit?.state == "Canceled")
        #expect(model.exit?.timeline.last?.isCurrent == true)
        #expect(model.exit?.timeline.count == 2)
        #expect(model.exit?.cancelable == false)
        #expect(await calls.mutations == 0)
        await calls.setReady(false)
        await model.load()
        #expect(model.exit == nil)
    }
}
