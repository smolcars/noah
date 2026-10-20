import Foundation
import Testing
@testable import Noah

@MainActor
struct HomeParityTests {
    struct BackupInput: Decodable {
        let lastBackupStatus: PersistedBackupState.Status
        let backupPending: Bool
        let lastBackupAt: Double?
        let now: Double
    }
    struct RoundInput: Decodable { let status: String; let isFinal: Bool }

    @Test func backupMatchesRNFixtures() throws {
        let cases = try Fixtures.load("home-backup", input: BackupInput.self, expected: String?.self)
        #expect(cases.count == 25)
        for fixture in cases {
            var state = PersistedBackupState()
            state.lastBackupStatus = fixture.input.lastBackupStatus
            state.backupPending = fixture.input.backupPending
            state.lastBackupAt = fixture.input.lastBackupAt
            let banner = HomeBanner.backup(state, now: fixture.input.now, locale: Locale(identifier: "en"))
            #expect(banner?.title == fixture.expected, "\(fixture.name)")
            #expect((banner?.retry == true) == (state.lastBackupStatus == .failed))
        }
    }

    @Test func activeRoundsMatchRNFixtures() throws {
        let cases = try Fixtures.load("home-rounds", input: RoundInput.self, expected: Bool.self)
        #expect(cases.count == 12)
        for fixture in cases {
            #expect(HomeRound(roundID: 1, status: fixture.input.status, isFinal: fixture.input.isFinal, isSuccess: false).active == fixture.expected)
        }
    }

    @Test func factsAndHiddenFiatMatchRN() throws {
        let facts = try Fixtures.load("home-facts", input: Int.self, expected: String.self)
        #expect(facts.count == HomeFacts.values.count)
        for fixture in facts { #expect(HomeFacts.values[fixture.input] == fixture.expected) }
        let symbols = try Fixtures.load("home-hidden-fiat", input: String.self, expected: String.self)
        #expect(symbols.count == FiatCurrency.supported.count)
        for fixture in symbols { #expect(HomeModel.hiddenFiat(currency: fixture.input) == fixture.expected) }
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
        var rounds = [HomeRound(roundID: 1, status: "pending", isFinal: false, isSuccess: false)]
        var background = false
        var deps = dependencies()
        deps.rounds = { calls += 1; return rounds }
        deps.now = { now }
        deps.backgroundRunning = { background }
        let model = HomeModel(dependencies: deps)
        await model.refreshRounds(force: true)
        await model.tick()
        #expect(calls == 1)
        now += 30
        background = true
        await model.tick()
        #expect(calls == 1)
        background = false
        await model.tick()
        #expect(calls == 2)
        rounds = [HomeRound(roundID: 1, status: "confirmed", isFinal: true, isSuccess: true)]
        now += 30
        await model.tick()
        #expect(calls == 3)
        #expect(model.activeRounds.isEmpty)
        now += 300
        await model.tick()
        #expect(calls == 3)
        await model.refreshRounds(force: true)
        #expect(calls == 4)
    }

    @Test func roundsResumeAfterInitialBackgroundJob() async {
        var background = true
        var calls = 0
        var deps = dependencies()
        deps.backgroundRunning = { background }
        deps.rounds = { calls += 1; return [] }
        let model = HomeModel(dependencies: deps)
        await model.refresh(currency: "USD")
        #expect(calls == 0)
        background = false
        await model.tick()
        #expect(calls == 1)
    }

    @Test func autoBoardSuccessShowsNewReceiptsForFiveSecondsWithoutReplay() async {
        var now = Date(timeIntervalSince1970: 10_000)
        var completions = [HomeModel.AutoBoardCompletion(id: UUID(), netSats: 20_000)]
        var deps = dependencies()
        deps.now = { now }
        deps.autoBoards = { completions }
        let model = HomeModel(dependencies: deps)
        await model.tick()
        #expect(model.autoBoard == nil, "historical receipts must not replay on launch")
        completions.append(.init(id: UUID(), netSats: 25_000))
        await model.tick()
        #expect(model.autoBoard?.netSats == 25_000)
        now += 4.999
        await model.tick()
        #expect(model.autoBoard != nil)
        now += 0.001
        await model.tick()
        #expect(model.autoBoard == nil)
        await model.tick()
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
        await model.tick()
        #expect(model.backup.lastBackupStatus == .failed)
        await model.retryBackup()
        #expect(calls == 1)
        #expect(!model.isRetryingBackup)
        #expect(model.backup.lastBackupStatus == .inProgress)
    }

    private func dependencies() -> HomeModel.Dependencies {
        .init(price: { _ in nil }, rounds: { [] }, backup: { .init() }, retryBackup: {},
              autoBoards: { [] }, backgroundRunning: { false })
    }
}
