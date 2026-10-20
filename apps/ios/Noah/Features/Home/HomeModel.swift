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
            return Self(title: "Backup failed", message: state.lastBackupError ?? "An unknown error occurred while backing up.", retry: true)
        case .success:
            guard !state.backupPending, let time = state.lastBackupAt, now - time < 5_000 else { return nil }
            let formatter = DateFormatter()
            formatter.locale = locale
            formatter.setLocalizedDateFormatFromTemplate("jm")
            return Self(title: "Backup completed", message: "Last backup \(formatter.string(from: Date(timeIntervalSince1970: time / 1000)))")
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
        var backgroundRunning: () async -> Bool
        var now: () -> Date = { Date() }

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
                backgroundRunning: { await WalletBackgroundActivity.shared.isRunning })
        }
    }

    private(set) var btcPrice: Double?
    private(set) var activeRounds: [HomeRound] = []
    private(set) var backup = PersistedBackupState()
    private(set) var isRetryingBackup = false
    private(set) var backgroundRunning = false
    private(set) var fact = HomeFacts.values.randomElement() ?? ""
    private(set) var autoBoard: (netSats: UInt64, createdAt: Date)?
    private let dependencies: Dependencies
    private var quotes: [String: (price: Double, at: Date)] = [:]
    private var priceGeneration = 0
    private var loadingRounds = false
    private var nextRoundCheck: Date?
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
        guard !loadingRounds, !backgroundRunning else { return }
        let now = dependencies.now()
        if !force {
            guard let nextRoundCheck, now >= nextRoundCheck else { return }
        }
        loadingRounds = true
        defer { loadingRounds = false }
        do {
            activeRounds = try await dependencies.rounds().filter(\.active)
        } catch { AppLog("home").w("Pending round status unavailable") }
        nextRoundCheck = activeRounds.isEmpty ? nil : now.addingTimeInterval(30)
    }

    /// Poll only presentation state; round progression stays at RN's 30 s cadence.
    func tick() async {
        backup = dependencies.backup()
        let wasBackgroundRunning = backgroundRunning
        backgroundRunning = await dependencies.backgroundRunning()
        if let completed = try? await dependencies.autoBoards() {
            let ids = Set(completed.map(\.id))
            if let seenAutoBoards, let latest = completed.last(where: { !seenAutoBoards.contains($0.id) }) {
                autoBoard = (latest.netSats, dependencies.now())
            }
            seenAutoBoards = ids
        }
        if let autoBoard, dependencies.now().timeIntervalSince(autoBoard.createdAt) >= 5 { self.autoBoard = nil }
        await refreshRounds(force: wasBackgroundRunning && !backgroundRunning)
    }

    func refresh(currency: String) async {
        fact = HomeFacts.values.randomElement() ?? ""
        await tick()
        await refreshRounds(force: true)
        await loadPrice(currency: currency)
    }

    func retryBackup() async {
        guard !isRetryingBackup else { return }
        isRetryingBackup = true
        defer { isRetryingBackup = false; backup = dependencies.backup() }
        do { try await dependencies.retryBackup() }
        catch { AppLog("home").w("Manual backup failed") }
    }

    static func hiddenFiat(currency: String) -> String {
        let symbols = ["USD": "$", "EUR": "€", "GBP": "£", "CAD": "CA$", "CHF": "CHF", "AUD": "A$",
                       "JPY": "¥", "BRL": "R$", "KRW": "₩", "INR": "₹", "MXN": "MX$", "SGD": "S$"]
        return "\(symbols[currency] ?? "$") ••••"
    }
}
