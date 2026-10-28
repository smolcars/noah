import Foundation
@testable import Noah
import SwiftUI
import Testing
import UIKit

/// Widget routes through a real `AppModel` and the mounted tabs: a route that switches away from Send
/// would run Send's `onDisappear`, which discards a review.
@MainActor
struct WidgetRouteLifecycleTests {
    private func walletApp() async throws -> (AppModel, URL) {
        let root = FileManager.default.temporaryDirectory.appending(path: "widget-route-\(UUID())")
        let push = PushPermissionModel(dependencies: .init(isPhysicalDevice: false, status: { .granted }, request: {}))
        let app = AppModel(settings: try SettingsStorage(rootPath: root.path), readLaunchState: { .onboarding },
                           settleRestore: { .nothingPending }, pushPermission: push)
        await app.start()
        // No wallet stands behind this model: its balance read stops instead of reopening one.
        await ForegroundExecution.$admission.withValue({ false }) { await app.finishOnboarding() }
        try #require(app.phase == .wallet && app.paymentLinkGate == .open)
        return (app, root)
    }

    private func send(_ controller: SendPaymentController? = nil) -> SendModel {
        SendModel(controller: controller ?? SendPaymentTests().make(SendPaymentTests.Backend(), SendPaymentTests.Storage()),
                  resolveRecipient: { .init(destination: $0, minMsat: 1000, maxMsat: 1_000_000_000, commentAllowed: 0) },
                  quotePayment: { .init(amount: $0.amount, fee: 0, total: $0.amount) }, discardQuote: { _ in })
    }

    private func mount(_ app: AppModel, send: SendModel, tab: WalletTabs.Tab) throws -> UIWindow {
        let view = WalletTabs(history: HistoryModel(dependencies: .init(load: { [] }, sync: {}, changes: { AsyncThrowingStream { $0.finish() } },
                                                                        blockTime: { _ in nil }, price: { _, _ in nil })),
                              home: HomeModel(dependencies: .init(price: { _ in nil }, rounds: { [] }, backup: { PersistedBackupState() },
                                                                  retryBackup: {}, autoBoards: { [] },
                                                                  backgroundSnapshot: { .init(isRunning: false, completionRevision: 0) },
                                                                  renewal: { nil })),
                              send: send, tab: tab)
            .environment(app)
            .environment(app.walletLock)
            .environment(\.homeFixture, HomeFixture(summary: nil, walletError: nil))
            .environment(\.scenePhase, .active)
        let scene = try #require(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let window = UIWindow(windowScene: scene)
        window.rootViewController = UIHostingController(rootView: view)
        window.isHidden = false
        return window
    }

    private func route(_ app: AppModel, _ path: String) throws {
        app.widgetRoute.receive(try #require(URL(string: "com.noahwallet.\(app.variant.rawValue)://widget/\(path)")),
                                gate: app.paymentLinkGate, variant: app.variant)
    }

    /// Home and Receive leave a review on screen as it is; so does Send itself, which it already shows.
    @Test(arguments: ["home", "receive", "send", "scan"])
    func aRouteNeverDiscardsASendReview(path: String) async throws {
        let (app, root) = try await walletApp()
        let model = send()
        let window = try mount(app, send: model, tab: .send)
        defer { window.isHidden = true; window.rootViewController = nil; app.appBecameInactive(); try? FileManager.default.removeItem(at: root) }
        try await Task.sleep(for: .milliseconds(300))
        model.arkBalance = 1_000_000 // Send read no wallet when it appeared.
        #expect(await QRScanRoute.apply("lightning:\(PaymentLinkTests.invoice)", to: model))
        try await SendPaymentTests.waitUntil { model.showReview && model.quote != nil }
        try route(app, path)
        try await SendPaymentTests.waitUntil { app.widgetRoute.pending == nil }
        try await Task.sleep(for: .milliseconds(400)) // Long enough for a tab switch to run Send's onDisappear.
        #expect(model.showReview && model.quote != nil && model.stage == .review, "\(path)")
        #expect(!model.scanning, "\(path)")
    }
}
