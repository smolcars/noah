/// What a cold launch and each return to the front run once the wallet is open: Home's read, a wait for Home's history
/// read, the foreground pass, what follows the pass, and Home's read again. Home reads first because the pass's on-chain
/// sync holds the on-chain wallet for its whole Esplora round trip, and Home's balance and history wait for it meanwhile.
@MainActor struct ForegroundCatchUp {
    /// How long the pass waits for Home's history read (`WalletSession.waitForHistoryReads`). The read is local (bark's
    /// database, no network) and starts as Home appears, so it normally takes well under a second; 3 s leaves room for a
    /// slow cold start and holds maintenance and Lightning claims back at most that long when the history never loads.
    static let historyWait: Duration = .seconds(3)

    var home: @MainActor () async -> Void
    /// Home's and Activity's history, which `HistoryModel` reads on its own; capped at `historyWait`.
    var history: @MainActor () async -> Void
    var pass: @MainActor () async -> Void
    /// After the pass; false skips Home's second read (the wallet closed or the app left meanwhile).
    var afterPass: @MainActor () async -> Bool

    func run() async {
        await home()
        await history()
        await pass()
        guard await afterPass() else { return }
        await home()
    }
}
