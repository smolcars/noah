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

    /// A device's notification permission, answered, failed or left waiting when the test says.
    @MainActor final class LateStatus {
        private enum Answer { case status(PushPermissionModel.Status), failure }
        private var result: Answer?
        private var waiting: [CheckedContinuation<Answer, Never>] = []
        func read() async throws -> PushPermissionModel.Status {
            let answer: Answer
            if let result { answer = result } else { answer = await withCheckedContinuation { waiting.append($0) } }
            guard case .status(let status) = answer else { throw CancellationError() }
            return status
        }
        /// Later reads wait for the next answer.
        func pend() { result = nil }
        func answer(_ status: PushPermissionModel.Status) { settle(.status(status)) }
        func fail() { settle(.failure) }
        private func settle(_ answer: Answer) {
            result = answer
            waiting.forEach { $0.resume(returning: answer) }
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

    /// The scene's own notifications stay out of it: each test backgrounds its model on its own center.
    private func device(_ late: LateStatus, _ center: NotificationCenter = NotificationCenter()) -> PushPermissionModel {
        PushPermissionModel(dependencies: .init(isPhysicalDevice: true, status: { try await late.read() }, request: {}), center: center)
    }

    /// Notifications allowed at launch and App lock on.
    private func lockedDevice(_ late: LateStatus, _ center: NotificationCenter) async throws -> (AppModel, URL) {
        let (app, root, finishing) = try await walletApp(device(late, center))
        late.answer(.granted)
        await finishing.value
        app.walletLock.policy = BiometricLockPolicy(enabled: true)
        return (app, root)
    }

    /// What leaving for Settings does: the app locks and its permission answer goes stale.
    private func background(_ app: AppModel, _ center: NotificationCenter) async throws {
        app.walletLock.background()
        center.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        try await Task.sleep(for: .milliseconds(50)) // The model observes it on the main queue.
    }

    private func unlock(_ app: AppModel) { app.walletLock.policy.authenticated(generation: app.walletLock.policy.generation) }

    /// Long enough for the mounted views to act on a link they shouldn't have; the gate itself is checked directly.
    private func settle() async throws { try await Task.sleep(for: .milliseconds(100)) }

    private func send(_ controller: SendPaymentController? = nil) -> SendModel {
        SendModel(controller: controller ?? SendPaymentTests().make(SendPaymentTests.Backend(), SendPaymentTests.Storage()),
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

    /// RootView with the scene left inactive, so it never starts Face ID or a live foreground refresh.
    private func rootView(_ app: AppModel) -> some View {
        RootView().environment(app).environment(\.homeFixture, HomeFixture(summary: nil, walletError: nil))
            .environment(\.scenePhase, .inactive)
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
        let window = try mount(rootView(app))
        defer { window.isHidden = true; window.rootViewController = nil; app.appBecameInactive(); try? FileManager.default.removeItem(at: root) }
        // The wallet's tabs are up but the permission read hasn't answered: hold, don't hand Send the link.
        #expect(app.paymentLinkGate == .hold)
        app.paymentLink.receive(try #require(URL(string: Self.link)), gate: app.paymentLinkGate)
        try await settle()
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
        try await settle()
        #expect(app.paymentLink.pending == Self.link && model.destination.isEmpty)
        late.answer(.granted)
        await finishing.value
        try await SendPaymentTests.waitUntil { model.destination == Self.link && !model.busy }
        #expect(app.paymentLink.pending == nil && app.paymentLinkGate == .open)
        // Once: after the user moves on, another permission read brings nothing back.
        model.destination = "alice@example.com"
        await app.pushPermission.refresh()
        try await settle()
        #expect(model.destination == "alice@example.com" && app.paymentLinkGate == .open)
    }

    @Test func notificationsTurnedOffInSettingsDropAHeldLinkThatUnlockCameFirstFor() async throws {
        let late = LateStatus(), center = NotificationCenter()
        let (app, root) = try await lockedDevice(late, center)
        let window = try mount(rootView(app))
        defer { window.isHidden = true; window.rootViewController = nil; app.appBecameInactive(); try? FileManager.default.removeItem(at: root) }
        try await background(app, center)
        late.answer(.denied) // Turned off in Settings; Noah hasn't read it yet.
        app.paymentLink.receive(try #require(URL(string: Self.link)), gate: app.paymentLinkGate)
        // Back in Noah, RootView unlocks before it reads the permission again: the old grant releases nothing.
        unlock(app)
        #expect(app.paymentLinkGate == .hold)
        try await settle()
        #expect(app.paymentLink.pending == Self.link) // WalletTabs never took it.
        await app.pushPermission.refresh()
        #expect(app.paymentLinkGate == .ignore)
        try await SendPaymentTests.waitUntil { app.paymentLink.pending == nil } // Dropped with the tabs.
    }

    @Test func notificationsStillAllowedAfterTheBackgroundApplyAHeldLinkOnceAfterUnlock() async throws {
        let late = LateStatus(), center = NotificationCenter()
        let (app, root) = try await lockedDevice(late, center)
        let model = send()
        let window = try mount(tabs(app, send: model))
        defer { window.isHidden = true; window.rootViewController = nil; app.appBecameInactive(); try? FileManager.default.removeItem(at: root) }
        try await background(app, center)
        app.paymentLink.receive(try #require(URL(string: Self.link)), gate: app.paymentLinkGate)
        unlock(app)
        try await settle()
        #expect(app.paymentLinkGate == .hold && model.destination.isEmpty)
        await app.pushPermission.refresh()
        try await SendPaymentTests.waitUntil { model.destination == Self.link && !model.busy }
        // Once: another trip to the background and back brings nothing back.
        model.destination = "alice@example.com"
        try await background(app, center)
        unlock(app)
        await app.pushPermission.refresh()
        try await settle()
        #expect(app.paymentLinkGate == .open && model.destination == "alice@example.com")
    }

    @Test func failedPermissionReadsKeepALinkHeldUntilOneSucceeds() async throws {
        let late = LateStatus(), center = NotificationCenter()
        let (app, root, finishing) = try await walletApp(device(late, center))
        late.answer(.granted)
        await finishing.value
        let model = send()
        let window = try mount(tabs(app, send: model))
        defer { window.isHidden = true; window.rootViewController = nil; app.appBecameInactive(); try? FileManager.default.removeItem(at: root) }
        try await background(app, center)
        app.paymentLink.receive(try #require(URL(string: Self.link)), gate: app.paymentLinkGate)
        late.fail()
        for _ in 0..<3 { await app.pushPermission.refresh() }
        try await settle()
        #expect(app.paymentLinkGate == .hold && app.paymentLink.pending == Self.link && model.destination.isEmpty)
        late.answer(.granted)
        await app.pushPermission.refresh()
        try await SendPaymentTests.waitUntil { model.destination == Self.link }
        #expect(app.paymentLink.pending == nil)
    }

    @Test func appLockAndAPendingPermissionCheckEachHoldALink() async throws {
        let late = LateStatus(), center = NotificationCenter()
        let (app, root) = try await lockedDevice(late, center)
        let model = send()
        let window = try mount(tabs(app, send: model))
        defer { window.isHidden = true; window.rootViewController = nil; app.appBecameInactive(); try? FileManager.default.removeItem(at: root) }
        try await background(app, center)
        app.paymentLink.receive(try #require(URL(string: Self.link)), gate: app.paymentLinkGate)
        late.pend()
        let check = Task { await app.pushPermission.refresh() }
        unlock(app) // Unlocked, but the check is still out.
        try await settle()
        #expect(app.paymentLinkGate == .hold && model.destination.isEmpty)
        // A check that started before the next background doesn't count after it.
        try await background(app, center)
        late.answer(.granted)
        await check.value
        unlock(app)
        try await settle()
        #expect(app.paymentLinkGate == .hold && model.destination.isEmpty)
        app.walletLock.background() // Locked again, then a fresh check answers: the lock still holds it.
        await app.pushPermission.refresh()
        try await settle()
        #expect(app.paymentLinkGate == .hold && model.destination.isEmpty)
        unlock(app)
        try await SendPaymentTests.waitUntil { model.destination == Self.link }
    }

    @Test func aLinkClosesSendsScannerAcrossPoseChangesAndShowsTheRequest() async throws {
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
        // Rotations and the Duo opening keep the scanner up.
        for size in [CGSize(width: 874, height: 402), .init(width: 951, height: 669), .init(width: 402, height: 874)] {
            window.frame.size = size
            window.rootViewController?.view.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(200))
            #expect(model.scanning && covered(window.rootViewController), "\(size)")
        }
        app.paymentLink.receive(try #require(URL(string: Self.link)), gate: app.paymentLinkGate)
        try await SendPaymentTests.waitUntil { model.destination == Self.link }
        try await SendPaymentTests.waitUntil { !covered(window.rootViewController) }
        #expect(!model.scanning)
    }

    @Test func theScannerAfterAPaymentStillGivesWayToALink() async throws {
        let push = PushPermissionModel(dependencies: .init(isPhysicalDevice: false, status: { .granted }, request: {}))
        let (app, root, finishing) = try await walletApp(push)
        await finishing.value
        let backend = SendPaymentTests.Backend()
        let controller = SendPaymentTests().make(backend, SendPaymentTests.Storage())
        let model = send(controller)
        let window = try mount(tabs(app, send: model, tab: .send))
        defer { window.isHidden = true; window.rootViewController = nil; app.appBecameInactive(); try? FileManager.default.removeItem(at: root) }
        await backend.succeed()
        controller.submit(SendPaymentTests().payment, quote: SendPaymentTests().quote)
        try await SendPaymentTests.waitUntil { controller.state != .ready && controller.state != .submitting }
        // Done on the receipt.
        controller.acknowledge()
        model.reset()
        #expect(!controller.locked)
        model.scanning = true
        try await SendPaymentTests.waitUntil { covered(window.rootViewController) }
        app.paymentLink.receive(try #require(URL(string: Self.link)), gate: app.paymentLinkGate)
        try await SendPaymentTests.waitUntil { model.destination == Self.link }
        try await SendPaymentTests.waitUntil { !covered(window.rootViewController) }
        #expect(await backend.calls == 1)
    }
}
