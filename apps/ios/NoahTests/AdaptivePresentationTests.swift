import Foundation
import SwiftUI
import Testing
import UIKit
@testable import Noah

/// Opt-in UI acceptance without funding or submitting a real wallet operation.
/// Write a screen name to Documents/adaptive-screen; "stop" restores the app.
@MainActor
@Suite(.enabled(if: ProcessInfo.processInfo.environment["NOAH_ADAPTIVE_UI"] == "1"))
struct AdaptivePresentationTests {
    @Test(.timeLimit(.minutes(15))) func presentFixtures() async throws {
        let scene = try #require(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let keyWindow = scene.windows.first { $0.isKeyWindow }
        let window = try #require(keyWindow)
        let original = window.rootViewController
        let app = AppModel()
        let storage = SendPaymentTests.Storage()
        let backend = SendPaymentTests.Backend()
        let controller = SendPaymentTests().make(backend, storage)
        let payment = SendPayment(destination: "layout@example.com", method: "ark", amount: 500)
        let quote = SendQuote(amount: 500, fee: 0, total: 500)
        let transaction = HistoryTransaction(id: "layout-1", type: "Arkoor", amount: 500,
            date: "2026-10-20T12:00:00Z", direction: "outgoing", source: "ark", destination: "layout@example.com")
        let history = HistoryModel(dependencies: .init(load: { [transaction] }, sync: {},
            changes: { AsyncThrowingStream { $0.finish() } }, blockTime: { _ in nil }, price: { _, _ in nil }))
        await history.refresh(currency: "USD")
        let recurring = try RecurringExecutorTests.Harness()
        defer { try? FileManager.default.removeItem(at: recurring.directory) }
        try recurring.store.bind(recurring.owner.withLock { $0 })
        for index in 0..<3 {
            var schedule = RecurringScheduleTests.schedule(start: recurring.now + Double(index + 1) * 86_400_000)
            schedule.id = "layout-\(index)"
            schedule.label = "Layout \(index + 1)"
            schedule.status = "paused"
            try recurring.store.upsert(schedule)
        }
        let folder = URL.documentsDirectory
        let request = folder.appending(path: "adaptive-screen")
        let ready = folder.appending(path: "adaptive-ready")
        let host = UIHostingController(rootView: AnyView(Color.clear))
        window.rootViewController = host
        defer {
            window.rootViewController = original
            try? FileManager.default.removeItem(at: ready)
            try? FileManager.default.removeItem(at: request)
        }
        var current = ""
        let deadline = ContinuousClock.now.advanced(by: .seconds(840))
        while ContinuousClock.now < deadline {
            let screen = (try? String(contentsOf: request, encoding: .utf8))?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "review"
            if screen == "stop" { return }
            if screen != current {
                let view: AnyView
                switch screen {
                case "review":
                    view = AnyView(SendConfirmation(payment: payment, quote: quote, controller: controller, cancel: {}, done: {}, onLeave: {}))
                case "receipt":
                    await backend.succeed()
                    controller.submit(payment, quote: quote)
                    try await SendPaymentTests.waitUntil { controller.state != .ready && controller.state != .submitting }
                    view = AnyView(NavigationStack { SendOutcomeView(controller: controller, done: {}, onLeave: {}) })
                case "history":
                    view = AnyView(TransactionsView(onRepeatPayment: { _ in }))
                case "transaction":
                    view = AnyView(NavigationStack { TransactionDetailView(transaction: transaction, onRepeatPayment: { _ in }, onClose: {}) })
                case "board-review":
                    view = AnyView(OnchainReviewSheet(quote: .init(action: .board, walletID: "layout", ids: [],
                        amount: 50000, fee: 100, chainFee: 500, confirmed: 80000), confirm: {}))
                case "lock": view = AnyView(WalletLockView())
                case "push-permission": view = AnyView(PushNotificationsRequiredView())
                case "onboarding": view = AnyView(OnboardingFlow(model: app))
                case "email": view = AnyView(NavigationStack { SettingsHost(page: .email) })
                case "lightning": view = AnyView(NavigationStack { SettingsHost(page: .lightningAddress) })
                case "debug": view = AnyView(NavigationStack { DebugView() })
                case "settings": view = AnyView(NavigationStack { SettingsHost(page: nil) })
                case "profile": view = AnyView(NavigationStack { SettingsHost(page: .profile) })
                case "backup": view = AnyView(NavigationStack { SettingsHost(page: .backup) })
                case "boarding": view = AnyView(NavigationStack { BoardArkView() })
                case "exits": view = AnyView(NavigationStack { UnilateralExitView() })
                case "recurring-editor": view = AnyView(NavigationStack { RecurringPaymentEditorView(executor: recurring.executor) })
                case "recurring-list": view = AnyView(NavigationStack { RecurringPaymentsView(executor: recurring.executor) })
                case "feedback": view = AnyView(NavigationStack { FeedbackView() })
                case "story": view = AnyView(NavigationStack { NoahStoryView() })
                case "ark-info": view = AnyView(NavigationStack { ArkInfoView() })
                case "esplora": view = AnyView(NavigationStack { EsploraSettingsScreen() })
                case "delete": view = AnyView(DeleteWalletSheet())
                case "vtxos": view = AnyView(NavigationStack { VtxosView(model: onchainModel()) })
                case "vtxo-detail": view = AnyView(NavigationStack { VtxoDetailView(id: "layout-vtxo") })
                case "exit-detail": view = AnyView(NavigationStack { ExitVtxoDetailView(id: "layout-exit", model: onchainModel(detailID: "layout-exit")) })
                default:
                    Issue.record("Unknown adaptive fixture screen")
                    return
                }
                host.rootView = AnyView(view.environment(app).environment(app.walletLock).environment(history).environment(SettingsSummary())
                    .environment(\.scenePhase, .active))
                host.view.setNeedsLayout()
                host.view.layoutIfNeeded()
                try await Task.sleep(for: .milliseconds(300))
                try screen.write(to: ready, atomically: true, encoding: .utf8)
                current = screen
            }
            try await Task.sleep(for: .milliseconds(200))
        }
        Issue.record("Adaptive fixture session was not stopped")
    }

    private func onchainModel(detailID: String? = nil) -> OnchainModel {
        let vtxo = VtxoModel(id: "layout-vtxo", amount: 50000, expiry: 2000, state: "Spendable", kind: "Arkoor",
            exitDepth: 1, exitWeight: 500, registered: true, serverPubkey: "02" + String(repeating: "a", count: 64),
            exitDelta: 144, anchorPoint: String(repeating: "b", count: 64) + ":0",
            point: String(repeating: "c", count: 64) + ":1", expiring: false, tip: 1000)
        let exit = ExitModel(id: "layout-exit", amount: 50000, state: "Start",
            details: .init(kind: "start", tip_height: 1000, claimable_height: 1144),
            history: [], historyDetails: [], claimable: false)
        let overview = OnchainOverview(walletID: "layout", vtxos: [vtxo], exits: [exit], confirmed: 80000,
            tip: 1000, pendingTotal: 50000, claimableHeight: 1144)
        return OnchainModel(dependencies: .init(ready: { true }, overview: { overview },
            quote: { action, ids, _, _ in .init(action: action, walletID: "layout", ids: ids, amount: 50000, fee: 0, chainFee: 0, confirmed: 80000) }, execute: { _ in throw OnchainFailure.unavailable },
            attempt: { [] }, acknowledge: { _ in }, detail: { _ in exit }), detailID: detailID)
    }
}
