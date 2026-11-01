/// What a cold launch and each return to the front run once the wallet is open: Home's read, the foreground pass, what
/// follows the pass, and Home's read again. Home reads first because the pass's on-chain sync holds the on-chain wallet
/// for its whole Esplora round trip, and Home's balance and history wait for it meanwhile.
@MainActor struct ForegroundCatchUp {
    var home: @MainActor () async -> Void
    var pass: @MainActor () async -> Void
    /// After the pass; false skips Home's second read (the wallet closed or the app left meanwhile).
    var afterPass: @MainActor () async -> Bool

    func run() async {
        await home()
        await pass()
        guard await afterPass() else { return }
        await home()
    }
}
