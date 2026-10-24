import Foundation
import Observation

struct HomeRound: Codable, Equatable, Identifiable, Sendable {
    var roundID: UInt32
    var status: String
    var isFinal: Bool
    var isSuccess: Bool
    var fundingTxid: String?
    var unsignedFundingTxids: [String] = []
    var error: String?
    var id: UInt32 { roundID }
    var active: Bool { !isFinal || status == "pending" || status == "unconfirmed" }
    var statusLabel: String {
        switch status {
        case "pending": "Pending"
        case "unconfirmed": "Unconfirmed"
        case "confirmed": "Confirmed"
        case "failed": "Failed"
        case "canceled": "Canceled"
        default: status
        }
    }
}

struct HomeBanner: Equatable {
    var title: String
    var message: String
    var retry = false

    static func backup(_ state: PersistedBackupState, now: Double, locale: Locale) -> Self? {
        switch state.lastBackupStatus {
        case .inProgress:
            return Self(title: "Backing up wallet", message: "Running in background")
        case .failed:
            return Self(title: "Backup failed", message: "An unknown error occurred while backing up.", retry: true)
        case .success:
            guard !state.backupPending, let time = state.lastBackupAt, now - time < 5_000 else { return nil }
            let formatter = DateFormatter()
            formatter.locale = locale
            formatter.setLocalizedDateFormatFromTemplate("jm")
            return Self(title: "Backup completed", message: #"Last backup \#(formatter.string(from: Date(timeIntervalSince1970: time / 1000)))"#) // Never shown: Home toasts only the title.
        default: return nil
        }
    }
}

@MainActor
@Observable
final class HomeModel {
    struct AutoBoardCompletion: Sendable {
        let id: UUID
        let netSats: UInt64
    }
    struct Dependencies {
        var price: (String) async throws -> Double?
        var rounds: () async throws -> [HomeRound]
        var backup: () -> PersistedBackupState
        var retryBackup: () async throws -> Void
        var autoBoards: () async throws -> [AutoBoardCompletion]
        var backgroundSnapshot: () async -> WalletBackgroundActivity.Snapshot
        var backgroundChanges: () async -> AsyncStream<WalletBackgroundActivity.Snapshot> = { AsyncStream { $0.finish() } }
        var walletChanges: () async throws -> AsyncThrowingStream<String, Error> = { AsyncThrowingStream { $0.finish() } }
        var backupChanges: () -> AsyncStream<PersistedBackupState> = { AsyncStream { $0.finish() } }
        var boardingChanges: () -> AsyncStream<Void> = { AsyncStream { $0.finish() } }
        var sleep: (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
        var now: () -> Date = { Date() }
        var renewal: () async throws -> HomeRenewal? = { nil }

        static var live: Self {
            Self(
                price: { currency in
                    try await AppServices.shared.client.fiatPrices(body: .json(.init())).ok.body.json.rates.additionalProperties[currency]
                },
                rounds: { try await WalletSession.shared.homePendingRounds() },
                backup: { SettingsStorage.shared.persistedState(PersistedBackupState.self, key: PersistedBackupState.key) ?? .init() },
                retryBackup: { try await AppServices.shared.backupCoordinator.flush(.manual, requireEnabled: false) },
                autoBoards: {
                    try await OnchainOperationJournal.shared.loadAll().compactMap { attempt in
                        guard attempt.quote.automatic, attempt.quote.action == .board,
                              let receipt = attempt.receipt, receipt.broadcastRetryPending != true else { return nil }
                        return AutoBoardCompletion(id: attempt.id, netSats: attempt.quote.net)
                    }
                },
                backgroundSnapshot: { await WalletBackgroundActivity.shared.snapshot },
                backgroundChanges: { await WalletBackgroundActivity.shared.changes() },
                walletChanges: {
                    let events = try await WalletSession.shared.stateChanges()
                    return AsyncThrowingStream { continuation in
                        let task = Task {
                            do {
                                for try await event in events { continuation.yield(event.reason) }
                                continuation.finish()
                            } catch { continuation.finish(throwing: error) }
                        }
                        continuation.onTermination = { _ in task.cancel() }
                    }
                },
                backupChanges: { AppServices.shared.backupCoordinator.statusUpdates.stream() },
                boardingChanges: { OnchainOperationJournal.shared.changes.stream() },
                renewal: { try await WalletSession.shared.homeRenewal() })
        }
    }

    private(set) var btcPrice: Double?
    private(set) var activeRounds: [HomeRound] = []
    private(set) var backup = PersistedBackupState()
    private(set) var isRetryingBackup = false
    private(set) var backgroundRunning = false
    var backgroundMessage: String? { backgroundRunning ? "Background task in progress..." : nil }
    /// Refreshable VTXOs automatic renewal hasn't reached; checked at the existing refresh moments.
    private(set) var renewal: HomeRenewal?
    private var loadingRenewal = false
    private(set) var autoBoard: (netSats: UInt64, createdAt: Date)?
    private let dependencies: Dependencies
    private var quotes: [String: (price: Double, at: Date)] = [:]
    private var priceGeneration = 0
    private var loadingRounds = false
    private var roundsRefreshPending = false
    private var completionRevision: UInt64?
    private var backgroundRevision: UInt64?
    private(set) var nextRoundCheck: Date?
    private(set) var bannerNow = Date()
    private var seenAutoBoards: Set<UUID>?

    init(dependencies: Dependencies = .live) { self.dependencies = dependencies }

    func loadPrice(currency: String) async {
        priceGeneration += 1
        let generation = priceGeneration
        if let cached = quotes[currency], dependencies.now().timeIntervalSince(cached.at) < 300 {
            btcPrice = cached.price
            return
        }
        btcPrice = quotes[currency]?.price
        guard let price = try? await dependencies.price(currency), price.isFinite, price > 0,
              generation == priceGeneration else { return }
        quotes[currency] = (price, dependencies.now())
        btcPrice = price
    }

    func refreshRounds(force: Bool = false) async {
        if force { roundsRefreshPending = true }
        guard !loadingRounds, !backgroundRunning else { return }
        let now = dependencies.now()
        guard roundsRefreshPending || nextRoundCheck.map({ now >= $0 }) == true else { return }
        loadingRounds = true
        defer { loadingRounds = false }
        repeat {
            roundsRefreshPending = false
            do {
                activeRounds = try await dependencies.rounds().filter(\.active)
            } catch { AppLog("home").w(#"Pending round status unavailable"#) }
            nextRoundCheck = activeRounds.isEmpty ? nil : dependencies.now().addingTimeInterval(30)
        } while roundsRefreshPending && !backgroundRunning && !Task.isCancelled
    }

    /// A fresh read for the renew review and its confirmation; nil when it fails, so
    /// nothing is reviewed or submitted on stale data.
    func currentRenewal() async -> HomeRenewal? {
        do {
            renewal = try await dependencies.renewal()
            return renewal
        } catch {
            AppLog("home").w(#"Renewal status unavailable"#)
            return nil
        }
    }

    func refreshRenewal() async {
        guard !loadingRenewal else { return }
        loadingRenewal = true
        defer { loadingRenewal = false }
        do { renewal = try await dependencies.renewal() }
        catch { AppLog("home").w(#"Renewal status unavailable"#) }
    }

    func backgroundChanged(_ snapshot: WalletBackgroundActivity.Snapshot) async {
        guard backgroundRevision.map({ snapshot.revision >= $0 }) ?? true else { return }
        backgroundRevision = snapshot.revision
        let completed = completionRevision.map { $0 != snapshot.completionRevision } ?? false
        completionRevision = snapshot.completionRevision
        backgroundRunning = snapshot.isRunning
        await refreshRounds(force: completed)
    }

    func observeBackground() async {
        for await snapshot in await dependencies.backgroundChanges() {
            guard !Task.isCancelled else { return }
            await backgroundChanged(snapshot)
        }
    }

    func observeWallet() async {
        do {
            var resubscribe = true
            while resubscribe && !Task.isCancelled {
                resubscribe = false
                for try await reason in try await dependencies.walletChanges() {
                    guard !Task.isCancelled else { return }
                    // Round sync writes its own pending state; those events must retain the 30 s cadence.
                    await refreshRounds(force: activeRounds.isEmpty)
                    await refreshRenewal()
                    if reason == "resyncRequired" { resubscribe = true; break }
                }
            }
        } catch { AppLog("home").w(#"Wallet status observation unavailable"#) }
    }

    func observeBackup() async {
        let updates = dependencies.backupChanges()
        updateBackup(dependencies.backup())
        for await state in updates {
            guard !Task.isCancelled else { return }
            updateBackup(state)
        }
    }

    func observeBoarding() async {
        let updates = dependencies.boardingChanges()
        await refreshAutoBoards()
        for await _ in updates {
            guard !Task.isCancelled else { return }
            await refreshAutoBoards()
        }
    }

    func updateBackup(_ state: PersistedBackupState) {
        backup = state
        expireBanners()
    }

    func refreshAutoBoards() async {
        if let completed = try? await dependencies.autoBoards() {
            let ids = Set(completed.map(\.id))
            if let seenAutoBoards, let latest = completed.last(where: { !seenAutoBoards.contains($0.id) }) {
                autoBoard = (latest.netSats, dependencies.now())
            }
            seenAutoBoards = ids
        }
        expireBanners()
    }

    func expireBanners() {
        bannerNow = dependencies.now()
        if let autoBoard, bannerNow.timeIntervalSince(autoBoard.createdAt) >= 5 { self.autoBoard = nil }
    }

    var nextBannerExpiry: Date? {
        var dates: [Date] = []
        if backup.lastBackupStatus == .success, !backup.backupPending, let time = backup.lastBackupAt {
            let expiry = Date(timeIntervalSince1970: time / 1000 + 5)
            if expiry > bannerNow { dates.append(expiry) }
        }
        if let autoBoard { dates.append(autoBoard.createdAt.addingTimeInterval(5)) }
        return dates.min()
    }

    func waitForBannerExpiry() async {
        guard let expiry = nextBannerExpiry else { return }
        do { try await dependencies.sleep(.seconds(max(0, expiry.timeIntervalSince(dependencies.now())))) }
        catch { return }
        guard !Task.isCancelled else { return }
        expireBanners()
    }

    func waitForRoundCheck() async {
        guard let nextRoundCheck else { return }
        do { try await dependencies.sleep(.seconds(max(0, nextRoundCheck.timeIntervalSince(dependencies.now())))) }
        catch { return }
        guard !Task.isCancelled else { return }
        await refreshRounds()
        await refreshRenewal()
    }

    func refresh(currency: String) async {
        updateBackup(dependencies.backup())
        await backgroundChanged(await dependencies.backgroundSnapshot())
        await refreshAutoBoards()
        await refreshRounds(force: true)
        await refreshRenewal()
        await loadPrice(currency: currency)
    }

    func retryBackup() async {
        guard !isRetryingBackup else { return }
        isRetryingBackup = true
        defer { isRetryingBackup = false; updateBackup(dependencies.backup()) }
        do { try await dependencies.retryBackup() }
        catch { AppLog("home").w(#"Manual backup failed"#) }
    }

    static func hiddenFiat(currency: String) -> String {
        let symbols = ["USD": "$", "EUR": "€", "GBP": "£", "CAD": "CA$", "CHF": "CHF", "AUD": "A$",
                       "JPY": "¥", "BRL": "R$", "KRW": "₩", "INR": "₹", "MXN": "MX$", "SGD": "S$"]
        return "\(symbols[currency] ?? "$") ••••"
    }
}
