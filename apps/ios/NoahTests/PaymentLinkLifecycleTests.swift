import Foundation
import SwiftUI
import Testing
import UIKit
@testable import Noah

/// Payment links through a real `AppModel` and mounted views: the launch and presentation paths the
/// route-level tests in `PaymentLinkTests` can't see.
@MainActor
struct PaymentLinkLifecycleTests {
    static let link = "lnurlp://example.com/.well-known/lnurlp/alice"

    /// A device's notification permission, answered when the test says.
    @MainActor final class LateStatus {
        private var result: PushPermissionModel.Status?
        private var waiting: [CheckedContinuation<PushPermissionModel.Status, Never>] = []
        func read() async -> PushPermissionModel.Status {
            if let result { return result }
            return await withCheckedContinuation { waiting.append($0) }
        }
        func answer(_ status: PushPermissionModel.Status) {
            result = status
            waiting.forEach { $0.resume(returning: status) }
            waiting = []
        }
    }

    /// Onboarding finished, as the launch does: the phase becomes `.wallet`, then the permission is read.
    private func walletApp(_ push: PushPermissionModel) async throws -> (AppModel, URL, Task<Void, Never>) {
        let root = FileManager.default.temporaryDirectory.appending(path: "payment-link-\(UUID())")
        let app = AppModel(settings: try SettingsStorage(rootPath: root.path), readLaunchState: { .onboarding },
                           settleRestore: { .nothingPending }, pushPermission: push)
        await app.start()
        // No wallet stands behind this model: its balance read stops instead of reopening one.
        let finishing = Task { await ForegroundExecution.$admission.withValue({ false }) { await app.finishOnboarding() } }
        try await SendPaymentTests.waitUntil { app.phase == .wallet }
        return (app, root, finishing)
    }

    private func device(_ late: LateStatus) -> PushPermissionModel {
        PushPermissionModel(dependencies: .init(isPhysicalDevice: true, status: { await late.read() }, request: {}))
    }

    private func send() -> SendModel {
        SendModel(controller: SendPaymentTests().make(SendPaymentTests.Backend(), SendPaymentTests.Storage()),
                  resolveRecipient: { .init(destination: $0, minMsat: 1000, maxMsat: 1_000_000_000, commentAllowed: 0) },
                  quotePayment: { .init(amount: $0.amount, fee: 0, total: $0.amount) }, discardQuote: { _ in })
    }

    private func tabs(_ app: AppModel, send: SendModel, tab: WalletTabs.Tab = .home) -> some View {
        WalletTabs(history: HistoryModel(dependencies: .init(load: { [] }, sync: {}, changes: { AsyncThrowingStream { $0.finish() } },
                                                             blockTime: { _ in nil }, price: { _, _ in nil })),
                   home: HomeModel(dependencies: .init(price: { _ in nil }, rounds: { [] }, backup: { PersistedBackupState() }, retryBackup: {},
                                                       autoBoards: { [] }, backgroundSnapshot: { .init(isRunning: false, completionRevision: 0) },
                                                       renewal: { nil })),
                   send: send, tab: tab)
            .environment(app)
            .environment(app.walletLock)
            .environment(\.homeFixture, HomeFixture(summary: nil, walletError: nil))
            .environment(\.scenePhase, .active)
    }

    private func mount(_ view: some View) throws -> UIWindow {
        let scene = try #require(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let window = UIWindow(windowScene: scene)
        window.rootViewController = UIHostingController(rootView: view)
        window.isHidden = false
        return window
    }

    private func covered(_ controller: UIViewController?) -> Bool {
        guard let controller else { return false }
        return controller.presentedViewController != nil || controller.children.contains { covered($0) }
    }

    @Test func notificationsDeniedLateDropTheLinkBeforeSendStartsOnIt() async throws {
        let late = LateStatus()
        let (app, root, finishing) = try await walletApp(device(late))
        let window = try mount(RootView().environment(app).environment(\.homeFixture, HomeFixture(summary: nil, walletError: nil))
            .environment(\.scenePhase, .active))
        defer { window.isHidden = true; window.rootViewController = nil; app.appBecameInactive(); try? FileManager.default.removeItem(at: root) }
        // The wallet's tabs are up but the permission read hasn't answered: hold, don't hand Send the link.
        #expect(app.paymentLinkGate == .hold)
        app.paymentLink.receive(try #require(URL(string: Self.link)), gate: app.paymentLinkGate)
        try await Task.sleep(for: .milliseconds(300))
        #expect(app.paymentLink.pending == Self.link) // WalletTabs never took it.
        late.answer(.denied)
        await finishing.value
        #expect(app.paymentLinkGate == .ignore)
        try await SendPaymentTests.waitUntil { app.paymentLink.pending == nil } // RootView dropped it with the tabs.
        late.answer(.granted) // Turning notifications on later brings nothing back.
        await app.pushPermission.refresh()
        #expect(app.paymentLinkGate == .open && app.paymentLink.pending == nil)
    }

    @Test func notificationsAllowedLateApplyTheLinkOnce() async throws {
        let late = LateStatus()
        let (app, root, finishing) = try await walletApp(device(late))
        let model = send()
        let window = try mount(tabs(app, send: model))
        defer { window.isHidden = true; window.rootViewController = nil; app.appBecameInactive(); try? FileManager.default.removeItem(at: root) }
        app.paymentLink.receive(try #require(URL(string: Self.link)), gate: app.paymentLinkGate)
        try await Task.sleep(for: .milliseconds(300))
        #expect(app.paymentLink.pending == Self.link && model.destination.isEmpty)
        late.answer(.granted)
        await finishing.value
        try await SendPaymentTests.waitUntil { model.destination == Self.link && !model.busy }
        #expect(app.paymentLink.pending == nil && app.paymentLinkGate == .open)
        // Once: after the user moves on, another permission read brings nothing back.
        model.destination = "alice@example.com"
        await app.pushPermission.refresh()
        try await Task.sleep(for: .milliseconds(300))
        #expect(model.destination == "alice@example.com" && app.paymentLinkGate == .open)
    }

    @Test func aLinkClosesSendsScannerAndShowsTheRequest() async throws {
        let push = PushPermissionModel(dependencies: .init(isPhysicalDevice: false, status: { .granted }, request: {}))
        let (app, root, finishing) = try await walletApp(push)
        await finishing.value
        #expect(app.phase == .wallet && app.paymentLinkGate == .open)
        let model = send()
        let window = try mount(tabs(app, send: model, tab: .send))
        defer { window.isHidden = true; window.rootViewController = nil; app.appBecameInactive(); try? FileManager.default.removeItem(at: root) }
        try await Task.sleep(for: .milliseconds(300))
        model.scanning = true
        try await SendPaymentTests.waitUntil { covered(window.rootViewController) }
        #expect(app.paymentLinkGate == .open)
        app.paymentLink.receive(try #require(URL(string: Self.link)), gate: app.paymentLinkGate)
        try await SendPaymentTests.waitUntil { model.destination == Self.link }
        try await SendPaymentTests.waitUntil { !covered(window.rootViewController) }
        #expect(!model.scanning)
    }
}
