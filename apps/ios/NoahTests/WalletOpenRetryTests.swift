import Foundation
@testable import Noah
import Testing

/// A failed wallet open is tried again on its own (issue 447): Home must not sit on "Couldn't open your wallet"
/// until the user leaves Noah and comes back.
@MainActor struct WalletOpenRetryTests {
    @Test func triesAgainWithDoublingWaitsUntilTheWalletOpens() async {
        var waits: [Duration] = []
        var attempts = 0
        let retry = WalletOpenRetry(sleep: { waits.append($0) })
        await withCheckedContinuation { opened in
            retry.start {
                attempts += 1
                guard attempts == 6 else { return false }
                opened.resume()
                return true
            }
            // One loop at a time: a failure during the loop's own try starts no second one.
            retry.start { attempts += 100; return true }
            #expect(retry.isRunning)
        }
        #expect(attempts == 6)
        #expect(waits == [2, 4, 8, 16, 30, 30].map(Duration.seconds))
        await Task.yield()
        #expect(!retry.isRunning)
    }

    /// A try can end long after it opened the wallet (its server setup is slow). If the wallet was closed again
    /// meanwhile and a newer open failed, that failure's request must outlive the old try's success.
    @Test func aStartDuringATryKeepsTheLoopGoing() async {
        var attempts = 0
        let retry = WalletOpenRetry(sleep: { _ in })
        retry.start {
            attempts += 1
            if attempts == 1 { retry.start { true } }
            return true
        }
        while retry.isRunning { await Task.yield() }
        #expect(attempts == 2)
    }

    /// A return to the front starts the loop inside its pass, whose budget is revoked when the pass ends. A try
    /// that kept that budget would be refused as cancelled every time (`BackgroundExecution.checkActive`).
    @Test func aTryDoesNotKeepTheBudgetOfThePassThatStartedIt() async {
        let pass = RecurringSubmissionBudget()
        let retry = WalletOpenRetry(sleep: { _ in })
        let admitted = await withCheckedContinuation { tried in
            RecurringSubmissionBudget.$current.withValue(pass) {
                retry.start {
                    tried.resume(returning: RecurringSubmissionBudget.available)
                    return true
                }
            }
            pass.revoke()
        }
        #expect(admitted)
    }
}
