@preconcurrency import BackgroundTasks
import UIKit

@MainActor final class BackgroundScheduler {
    enum Kind: String, CaseIterable, Sendable {
        case refresh, maintenance
        var identifier: String { Bundle.main.bundleIdentifier! + "." + rawValue }
        var interval: TimeInterval { self == .refresh ? 15 * 60 : 6 * 60 * 60 }
    }

    @MainActor struct Dependencies {
        var pending: @MainActor () async -> [String]
        var submit: @MainActor (BGTaskRequest) throws -> Void
        var cancel: @MainActor (String) -> Void
        var eligible: @MainActor () -> Bool
        var isForeground: @MainActor () -> Bool
        var perform: @Sendable (Kind) async throws -> Void
        var now: @MainActor () -> Date = { Date() }
    }

    static let shared = BackgroundScheduler(dependencies: .live())
    private let d: Dependencies
    private var reconciling = false
    private let log = AppLog("backgroundTasks")
    init(dependencies: Dependencies) { d = dependencies }

    /// Must be registered before didFinishLaunching returns, including cold headless launches.
    func register() {
        for kind in Kind.allCases {
            let registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: kind.identifier, using: .main) { task in
                MainActor.assumeIsolated { self.launch(task, kind: kind) }
            }
            if !registered { log.w("Background task registration failed: \(kind.rawValue)") }
        }
    }

    /// Do not replace a pending request on every launch (that keeps postponing it).
    func schedule() async {
        guard !reconciling else { return }
        reconciling = true
        defer { reconciling = false }
        let pending = await d.pending()
        guard d.eligible() else {
            for kind in Kind.allCases { d.cancel(kind.identifier) }
            return
        }
        for kind in Kind.allCases where !pending.contains(kind.identifier) {
            let request: BGTaskRequest
            switch kind {
            case .refresh: request = BGAppRefreshTaskRequest(identifier: kind.identifier)
            case .maintenance:
                let processing = BGProcessingTaskRequest(identifier: kind.identifier)
                processing.requiresNetworkConnectivity = true
                processing.requiresExternalPower = false
                request = processing
            }
            request.earliestBeginDate = d.now().addingTimeInterval(kind.interval)
            do { try d.submit(request) }
            catch { log.w("Couldn't schedule background task: \(kind.rawValue)") }
        }
    }

    func launch(_ task: BGTask, kind: Kind) {
        let execution = start(kind) { task.setTaskCompleted(success: $0) }
        task.expirationHandler = { execution.systemExpired() }
        Task { await schedule() }
    }

    func start(_ kind: Kind, completion: @escaping (Bool) -> Void) -> BackgroundExecution {
        BackgroundExecution(completion: completion) { [d] in
            guard await MainActor.run(body: { !d.isForeground() && d.eligible() }) else { return }
            try Task.checkCancellation()
            try await d.perform(kind)
        }
    }
}

extension BackgroundScheduler.Dependencies {
    @MainActor static func live(services: AppServices = .shared) -> Self {
        Self(pending: { await BGTaskScheduler.shared.pendingTaskRequests().map(\.identifier) },
             submit: { try BGTaskScheduler.shared.submit($0) },
             cancel: { BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: $0) },
             eligible: {
                 (try? HeadlessWalletGate.mnemonic(location: .init(variant: services.variant), settings: .shared,
                                                  readSeed: { services.seed.read() })) != nil
             },
             isForeground: { UIApplication.shared.applicationState != .background },
             perform: { kind in
                 try await services.withBackgroundWallet {
                     try await services.ensureWalletOpen()
                     try Task.checkCancellation()
                     try await services.session.sync()
                     try Task.checkCancellation()
                     if kind == .maintenance {
                         try await services.session.maintenanceDelegated()
                         try Task.checkCancellation()
                     }
                     // Exactly the push/foreground executor: durable occurrence journal,
                     // stop generation, ArkInfo fee cap and inherited submission budget.
                     await RecurringExecutor.shared.execute()
                     try Task.checkCancellation()
                     if kind == .maintenance {
                         let enabled = SettingsStorage.shared.persistedState(
                             PersistedServerState.self, key: PersistedServerState.key)?.isBackupEnabled == true
                         if enabled { try await services.backupCoordinator.flush(.startup) }
                     }
                     try Task.checkCancellation()
                     if let summary = try await services.session.summary() {
                         let expiry = try? await services.session.closestExpiryBlocks()
                         WidgetDataWriter.write(balance: summary.balance, closestExpiryBlocks: expiry ?? nil, variant: services.variant)
                     }
                 }
             })
    }
}
