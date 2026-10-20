import AppIntents
import UIKit
import UserNotifications

/// Hosts the UIKit callbacks SwiftUI does not expose (APNs registration,
/// background remote notifications). Push handling lands in Phase 0.6.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        BarkLog.install()
        AppTelemetry.start()
        SettingsStorage.bootstrap()
        BackgroundScheduler.shared.register()
        NoahAppShortcuts.updateAppShortcutParameters()
        Task { await BackgroundScheduler.shared.schedule() }
        UNUserNotificationCenter.current().delegate = self
        // Before any push handler can start a backup: plaintext left by a
        // backup or export that died with the app.
        ScratchSpace.shared.sweepAbandoned()
        NotificationCenter.default.addObserver(self, selector: #selector(timeZoneChanged),
                                               name: .NSSystemTimeZoneDidChange, object: nil)
        return true
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        Task { await BackgroundScheduler.shared.schedule() }
    }

    func applicationWillEnterForeground(_ application: UIApplication) {
        BackgroundExecution.foregroundWillEnter()
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard RecurringNotificationRoute.opensSchedules(response.notification.request.content.userInfo) else { return }
        await MainActor.run { RecurringNotificationRoute.shared.presented = true }
    }

    func applicationSignificantTimeChange(_ application: UIApplication) {
        Task { await RecurringExecutor.shared.timeChanged() }
    }

    @objc private func timeZoneChanged() {
        Task { await RecurringExecutor.shared.timeChanged() }
    }

    /// Restore words stay visible for RN parity. System VoiceOver can read visible content;
    /// iOS has no third-party accessibility services, but keyboard extensions could log it.
    /// Block those app-wide so a non-secure recovery field never hands them keystrokes.
    func application(_ application: UIApplication, shouldAllowExtensionPointIdentifier identifier: UIApplication.ExtensionPointIdentifier) -> Bool {
        identifier != .keyboard
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        PushRegistration.shared.deliver(.success(deviceToken))
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        PushRegistration.shared.deliver(.failure(error))
    }

    /// Silent pushes from the Noah server (via Expo). iOS allows ~30 s; jobs
    /// get 25 s so the completion handler is always called in time.
    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any]
    ) async -> UIBackgroundFetchResult {
        guard let notification = PushPayload.parse(userInfo) else { return .noData }
        let jobs = PushJobs(dependencies: .live(.shared))
        return await Self.completeWithin(.seconds(25)) { try await jobs.handle(notification) }
    }

    /// Unstructured workers may settle native work after this callback has returned.
    /// A task group would wait for that work even after cancelling it.
    static func completeWithin(_ timeout: Duration, operation: @escaping @Sendable () async throws -> Void) async -> UIBackgroundFetchResult {
        await withCheckedContinuation { continuation in
            _ = BackgroundExecution(timeout: timeout, completion: {
                continuation.resume(returning: $0 ? .newData : .failed)
            }, operation: operation)
        }
    }
}

/// Monotonic and inherited by executor/controller/session owned tasks. Caller
/// cancellation cannot stop Rust, but expiry can still forbid a new submission.
enum RecurringExecutionBudget {
    @TaskLocal static var deadline: ContinuousClock.Instant?
    static var available: Bool { deadline.map { ContinuousClock.now < $0 } ?? true }
}
