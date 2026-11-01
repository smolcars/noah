@testable import Noah
import Testing

/// A cold launch and each return to the front read Home before the foreground pass: its on-chain sync holds the on-chain
/// wallet for its whole Esplora round trip, and Home's balance and history would wait for it.
@MainActor
struct ForegroundCatchUpTests {
    @Test func homeIsReadBeforeThePassAndAgainAfterIt() async {
        var calls: [String] = []
        await ForegroundCatchUp(home: { calls.append("home") }, pass: { calls.append("pass") }) {
            calls.append("after the pass")
            return true
        }.run()
        #expect(calls == ["home", "pass", "after the pass", "home"])
    }

    /// The wallet closed or the app left during the pass: nothing reads it again.
    @Test func homeIsNotReadAgainOnceTheWalletIsGone() async {
        var calls: [String] = []
        await ForegroundCatchUp(home: { calls.append("home") }, pass: { calls.append("pass") }) { false }.run()
        #expect(calls == ["home", "pass"])
    }
}
