import Foundation
import Testing
@testable import Noah

@MainActor
struct HomeParityTests {
    struct StatusInput: Decodable {
        let operation: String
        let status: String
        let pending: Bool?
        let at: Double?
        let now: Double?
        let final: Bool?
    }
    enum StatusExpected: Decodable, Equatable {
        case banner(String?), round(Bool)
        init(from decoder: Decoder) throws {
            let value = try decoder.singleValueContainer()
            if value.decodeNil() { self = .banner(nil) }
            else if let active = try? value.decode(Bool.self) { self = .round(active) }
            else { self = .banner(try value.decode(String.self)) }
        }
    }
    struct StatusFixtures: Decodable {
        let facts: [String]
        let symbols: [String: String]
        let cases: [Fixtures.File<StatusInput, StatusExpected>.Case]
    }

    @Test func statusMatchesSharedRNFixtures() throws {
        let fixtures = try Fixtures.decode("home-status", as: StatusFixtures.self)
        #expect(fixtures.cases.count == 43)
        for fixture in fixtures.cases {
            let input = fixture.input
            switch input.operation {
            case "backup":
                var state = PersistedBackupState()
                state.lastBackupStatus = try #require(.init(rawValue: input.status))
                state.backupPending = try #require(input.pending)
                state.lastBackupAt = input.at
                let banner = HomeBanner.backup(state, now: try #require(input.now), locale: Locale(identifier: "en"))
                #expect(.banner(banner?.title) == fixture.expected, "\(fixture.name)")
                #expect((banner?.retry == true) == (state.lastBackupStatus == .failed))
            case "round":
                let round = HomeRound(roundID: 1, status: input.status, isFinal: try #require(input.final), isSuccess: false)
                #expect(.round(round.active) == fixture.expected, "\(fixture.name)")
            default: Issue.record("Unknown Home fixture operation: \(input.operation)")
            }
        }
    }

    @Test func unknownRoundStatusesRetainFinality() throws {
        struct Input: Decodable { let status: String; let isFinal: Bool }
        let cases = try Fixtures.load("home-rounds", input: Input.self, expected: Bool.self)
            .filter { $0.input.status == "future_status" }
        #expect(cases.count == 2)
        for fixture in cases {
            #expect(HomeRound(roundID: 1, status: fixture.input.status, isFinal: fixture.input.isFinal, isSuccess: false).active == fixture.expected)
        }
    }

    // Home v2 dropped the Bitcoin facts line; hidden fiat still matches RN.
    @Test func hiddenFiatMatchesSharedRNFixtures() throws {
        let fixtures = try Fixtures.decode("home-status", as: StatusFixtures.self)
        #expect(fixtures.symbols.count == FiatCurrency.supported.count)
        for (currency, symbol) in fixtures.symbols {
            #expect(HomeModel.hiddenFiat(currency: currency) == "\(symbol) ••••")
        }
    }

    @Test func pricesCacheForFiveMinutesAndSwitchCurrency() async {
        var now = Date(timeIntervalSince1970: 10_000)
        var requests: [String] = []
        var deps = dependencies()
        deps.now = { now }
        deps.price = { currency in requests.append(currency); return currency == "USD" ? 100_000 : 90_000 }
        let model = HomeModel(dependencies: deps)
        await model.loadPrice(currency: "USD")
        #expect(model.btcPrice == 100_000)
        await model.loadPrice(currency: "USD")
        #expect(requests == ["USD"])
        await model.loadPrice(currency: "EUR")
        #expect(model.btcPrice == 90_000)
        now += 300
        await model.loadPrice(currency: "USD")
        #expect(requests == ["USD", "EUR", "USD"])
    }

    @Test func priceFailuresDoNotInventAFiatBalance() async {
        var deps = dependencies()
        deps.price = { _ in .nan }
        let model = HomeModel(dependencies: deps)
        await model.loadPrice(currency: "USD")
        #expect(model.btcPrice == nil)
    }

    @Test func pollsActiveRoundsEveryThirtySecondsAndStopsWhenFinal() async {
        var now = Date(timeIntervalSince1970: 10_000)
        var calls = 0
        var sleeps: [Duration] = []
        var rounds = [HomeRound(roundID: 1, status: "pending", isFinal: false, isSuccess: false)]
        var deps = dependencies()
        deps.rounds = { calls += 1; return rounds }
        deps.now = { now }
        deps.sleep = { duration in sleeps.append(duration); now += 30 }
        let model = HomeModel(dependencies: deps)
        await model.refreshRounds(force: true)
        await model.refreshRounds()
        #expect(calls == 1)
        await model.backgroundChanged(.init(isRunning: true, completionRevision: 0))
        await model.waitForRoundCheck()
        #expect(calls == 1)
        await model.backgroundChanged(.init(isRunning: false, completionRevision: 1))
        #expect(calls == 2)
        rounds = [HomeRound(roundID: 1, status: "confirmed", isFinal: true, isSuccess: true)]
        await model.waitForRoundCheck()
        #expect(calls == 3)
        #expect(model.activeRounds.isEmpty && model.nextRoundCheck == nil)
        now += 300
        await model.waitForRoundCheck()
        #expect(calls == 3 && sleeps == [.seconds(30), .seconds(30)])
        await model.refreshRounds(force: true)
        #expect(calls == 4)
    }

    @Test func roundsResumeAfterInitialBackgroundJob() async {
        var background = true
        var calls = 0
        var deps = dependencies()
        deps.backgroundSnapshot = { .init(isRunning: background, completionRevision: background ? 0 : 1) }
        deps.rounds = { calls += 1; return [] }
        let model = HomeModel(dependencies: deps)
        await model.refresh(currency: "USD")
        #expect(calls == 0)
        background = false
        await model.backgroundChanged(await deps.backgroundSnapshot())
        #expect(calls == 1)
    }

    @Test func shortBackgroundJobRefreshesIdleRoundsBetweenTicks() async {
        let activity = WalletBackgroundActivity()
        let stream = await activity.changes()
        var iterator = stream.makeAsyncIterator()
        var calls = 0
        var rounds: [HomeRound] = []
        var deps = dependencies()
        deps.rounds = { calls += 1; return rounds }
        let model = HomeModel(dependencies: deps)
        await model.backgroundChanged(await iterator.next()!)
        await model.refreshRounds(force: true)
        #expect(calls == 1 && model.activeRounds.isEmpty)
        // Both edges happen before the consumer gets another turn (and without a tick).
        await activity.begin()
        #expect(await activity.isRunning)
        rounds = [.init(roundID: 2, status: "pending", isFinal: false, isSuccess: false)]
        await activity.end()
        let completed = await iterator.next()!
        #expect(!completed.isRunning && completed.completionRevision == 1)
        #expect(!(await activity.isRunning))
        await model.backgroundChanged(completed)
        #expect(calls == 2 && model.activeRounds == rounds)
    }

    @Test func olderBackgroundSnapshotsCannotOverwriteNewerEvents() async {
        let activity = WalletBackgroundActivity()
        await activity.begin()
        let oldStart = await activity.snapshot
        await activity.end()
        let completed = await activity.snapshot
        let model = HomeModel(dependencies: dependencies())
        await model.backgroundChanged(completed)
        await model.backgroundChanged(oldStart)
        #expect(!model.backgroundRunning)
        await activity.begin()
        await model.backgroundChanged(await activity.snapshot)
        await model.backgroundChanged(completed)
        #expect(model.backgroundRunning)
        await activity.end()
        await model.backgroundChanged(await activity.snapshot)
        #expect(!model.backgroundRunning)
    }

    @Test func walletChangesRefreshIdleRounds() async {
        let (events, continuation) = AsyncThrowingStream<String, Error>.makeStream()
        var deps = dependencies()
        var calls = 0
        deps.rounds = { calls += 1; return [] }
        deps.walletChanges = { events }
        let model = HomeModel(dependencies: deps)
        continuation.yield("databaseChanged")
        continuation.finish()
        await model.observeWallet()
        #expect(calls == 1)
    }

    @Test func walletEventsFromRoundSyncKeepTheThirtySecondCadence() async {
        let (events, continuation) = AsyncThrowingStream<String, Error>.makeStream()
        var deps = dependencies()
        var calls = 0
        deps.rounds = {
            calls += 1
            continuation.yield("databaseChanged")
            continuation.finish()
            return [.init(roundID: 1, status: "pending", isFinal: false, isSuccess: false)]
        }
        deps.walletChanges = { events }
        let model = HomeModel(dependencies: deps)
        continuation.yield("databaseChanged")
        await model.observeWallet()
        #expect(calls == 1 && model.activeRounds.count == 1)
        #expect(model.nextRoundCheck != nil)
    }

    @Test func autoBoardSuccessShowsNewReceiptsForFiveSecondsWithoutReplay() async {
        var now = Date(timeIntervalSince1970: 10_000)
        var completions = [HomeModel.AutoBoardCompletion(id: UUID(), netSats: 20_000)]
        var deps = dependencies()
        deps.now = { now }
        deps.autoBoards = { completions }
        let model = HomeModel(dependencies: deps)
        await model.refreshAutoBoards()
        #expect(model.autoBoard == nil, "historical receipts must not replay on launch")
        completions.append(.init(id: UUID(), netSats: 25_000))
        await model.refreshAutoBoards()
        #expect(model.autoBoard?.netSats == 25_000)
        now += 4.999
        await model.refreshAutoBoards()
        #expect(model.autoBoard != nil)
        now += 0.001
        await model.refreshAutoBoards()
        #expect(model.autoBoard == nil)
        await model.refreshAutoBoards()
        #expect(model.autoBoard == nil)
    }

    @Test func backupRetryUsesManualCoordinatorAndReadsUpdatedStatus() async {
        var state = PersistedBackupState()
        state.markFailed("Offline")
        var calls = 0
        var deps = dependencies()
        deps.backup = { state }
        deps.retryBackup = { calls += 1; state.markInProgress(now: 1000) }
        let model = HomeModel(dependencies: deps)
        await model.observeBackup()
        #expect(model.backup.lastBackupStatus == .failed)
        await model.retryBackup()
        #expect(calls == 1)
        #expect(!model.isRetryingBackup)
        #expect(model.backup.lastBackupStatus == .inProgress)
    }

    @Test func idleStatusReadsOnlyAtSubscriptionAndJournalEvents() async {
        let (backupEvents, backupContinuation) = AsyncStream<PersistedBackupState>.makeStream()
        let (boardEvents, boardContinuation) = AsyncStream<Void>.makeStream()
        var backupReads = 0
        var boardReads = 0
        var sleeps = 0
        var deps = dependencies()
        deps.backup = { backupReads += 1; return .init() }
        deps.autoBoards = { boardReads += 1; return [] }
        deps.backupChanges = { backupEvents }
        deps.boardingChanges = { boardEvents }
        deps.sleep = { _ in sleeps += 1 }
        var failed = PersistedBackupState()
        failed.markFailed("Offline")
        backupContinuation.yield(failed)
        backupContinuation.finish()
        boardContinuation.yield(())
        boardContinuation.finish()
        let model = HomeModel(dependencies: deps)
        await model.observeBackup()
        await model.observeBoarding()
        #expect(model.backup == failed)
        #expect(backupReads == 1 && boardReads == 2)
        await model.waitForRoundCheck()
        await model.waitForBannerExpiry()
        #expect(sleeps == 0 && backupReads == 1 && boardReads == 2)
    }

    @Test func backupAndBoardingExpireWithOneShotTimers() async {
        var now = Date(timeIntervalSince1970: 10)
        var sleeps: [Duration] = []
        var completions: [HomeModel.AutoBoardCompletion] = []
        var deps = dependencies()
        deps.now = { now }
        deps.sleep = { duration in
            sleeps.append(duration)
            now += Double(duration.components.seconds) + Double(duration.components.attoseconds) / 1e18
        }
        deps.autoBoards = { completions }
        let model = HomeModel(dependencies: deps)
        var state = PersistedBackupState()
        state.markSuccess(snapshotSha256: "sha", backupId: nil, uploaded: true, stillPending: false, now: 10_000)
        model.updateBackup(state)
        await model.refreshAutoBoards()
        now += 2
        completions.append(.init(id: UUID(), netSats: 25_000))
        await model.refreshAutoBoards()
        #expect(model.nextBannerExpiry == Date(timeIntervalSince1970: 15))
        await model.waitForBannerExpiry()
        #expect(HomeBanner.backup(model.backup, now: model.bannerNow.timeIntervalSince1970 * 1000, locale: .current) == nil)
        #expect(model.autoBoard != nil)
        await model.waitForBannerExpiry()
        #expect(model.autoBoard == nil && model.nextBannerExpiry == nil)
        await model.waitForBannerExpiry()
        #expect(sleeps == [.seconds(3), .seconds(2)])
    }

    @Test func cancelledExpiryDoesNotClearANewBanner() async {
        var deps = dependencies()
        deps.now = { Date(timeIntervalSince1970: 10) }
        deps.sleep = { _ in throw CancellationError() }
        let model = HomeModel(dependencies: deps)
        var state = PersistedBackupState()
        state.markSuccess(snapshotSha256: "sha", backupId: nil, uploaded: true, stillPending: false, now: 10_000)
        model.updateBackup(state)
        await model.waitForBannerExpiry()
        #expect(model.nextBannerExpiry == Date(timeIntervalSince1970: 15))
    }

    private func dependencies() -> HomeModel.Dependencies {
        .init(price: { _ in nil }, rounds: { [] }, backup: { .init() }, retryBackup: {},
              autoBoards: { [] }, backgroundSnapshot: { .init(isRunning: false, completionRevision: 0) })
    }
}
