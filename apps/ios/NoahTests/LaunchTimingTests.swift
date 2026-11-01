import Foundation
@testable import Noah
import Testing

/// The launch's timing lines in the app log, which show how long Home's history takes to appear.
struct LaunchTimingTests {
    typealias Locked = RecurringExecutorTests.Locked

    @Test func eachMilestoneIsLoggedOnceInMillisecondsSinceTheProcessStarted() {
        let lines = Locked<[String]>([])
        let start = Date(timeIntervalSince1970: 1000)
        let timing = LaunchTiming(log: { line in lines.withLock { $0.append(line) } }, started: start)
        timing.mark(.walletOpened, now: start.addingTimeInterval(0.25))
        timing.mark(.summaryShown, now: start.addingTimeInterval(0.4))
        timing.mark(.summaryShown, now: start.addingTimeInterval(9))
        timing.mark(.historyShown, suffix: " (3 rows)", now: start.addingTimeInterval(1.5))
        #expect(lines.withLock { $0 } == ["Wallet open done at +250 ms", "First summary shown at +400 ms",
                                          "First history rows shown at +1500 ms (3 rows)"])
    }

    @Test func aPassLogsEachStageStartingAndEnding() async {
        struct Boom: Error {}
        let lines = Locked<[String]>([])
        let timing = PassTiming(log: { line in lines.withLock { $0.append(line) } })
        try? await timing.run("wallet sync") {}
        await #expect(throws: Boom.self) { try await timing.run("on-chain sync") { throw Boom() } }
        #expect(lines.withLock { $0.map { $0.replacing(/\+\d+ ms/, with: "+n ms") } } == [
            "Foreground pass: wallet sync started at +n ms", "Foreground pass: wallet sync done at +n ms",
            "Foreground pass: on-chain sync started at +n ms", "Foreground pass: on-chain sync failed at +n ms",
        ])
    }

    @Test func theProcessStartedBeforeNow() {
        #expect(LaunchTiming.processStart <= Date())
        #expect(Date().timeIntervalSince(LaunchTiming.processStart) < 24 * 60 * 60)
    }
}
