import AppIntents
import UIKit
import UserNotifications

/// Hosts the UIKit callbacks SwiftUI does not expose (APNs registration,
/// background remote notifications). Push handling lands in Phase 0.6.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    private let logFile: LogFile
    private var lifecycleDrain: BackgroundExecution?

    override init() {
        logFile = .shared
        super.init()
    }

    init(logFile: LogFile) {
        self.logFile = logFile
        super.init()
    }

    func application(
        _: UIApplication,
        // swiftlint:disable:next discouraged_optional_collection - UIApplicationDelegate's signature
        didFinishLaunchingWithOptions _: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        do { try EsploraSettings().restoreLogPrivacy() }
        catch { EndpointLogPrivacy.failClosed() }
        BarkLog.install()
        AppTelemetry.start()
        SettingsStorage.bootstrap()
        BackgroundScheduler.shared.register()
        NoahAppShortcuts.updateAppShortcutParameters()
        Task { await BackgroundScheduler.shared.schedule() }
        UNUserNotificationCenter.current().delegate = self
        // Secret shields hold after a capture that ended before any of them appeared.
        NotificationCenter.default.addObserver(self, selector: #selector(sceneWillConnect),
                                               name: UIScene.willConnectNotification, object: nil)
        // Before any push handler can start a backup: plaintext left by a
        // backup or export that died with the app.
        ScratchSpace.shared.sweepAbandoned()
        NotificationCenter.default.addObserver(self, selector: #selector(timeZoneChanged),
                                               name: .NSSystemTimeZoneDidChange, object: nil)
        // SwiftUI scenes do not deliver every UIApplicationDelegate lifecycle callback.
        for name in [UIScene.willDeactivateNotification, UIScene.didEnterBackgroundNotification] {
            NotificationCenter.default.addObserver(self, selector: #selector(drainLifecycleLogs), name: name, object: nil)
        }
        return true
    }

    @objc func sceneWillConnect(_ notification: Notification) {
        if let scene = notification.object as? UIWindowScene { _ = SceneCapture.of(scene) }
    }

    @objc func drainLifecycleLogs() {
        beginLifecycleLogDrain(UIApplication.shared)
    }

    private func beginLifecycleLogDrain(_ application: UIApplication) {
        guard lifecycleDrain == nil else { return }
        let identifier = application.beginBackgroundTask(withName: "Log drain") { [weak self] in
            self?.lifecycleDrain?.expire()
        }
        guard identifier != .invalid else { return }
        lifecycleDrain = BackgroundExecution(timeout: .seconds(min(logFile.drainLimit, application.backgroundTimeRemaining)),
                                             logFile: logFile, completion: { [weak self] _ in
                                                 application.endBackgroundTask(identifier)
                                                 self?.lifecycleDrain = nil
                                             }, operation: {})
    }

    func applicationWillResignActive(_ application: UIApplication) {
        beginLifecycleLogDrain(application)
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        Task { await BackgroundScheduler.shared.schedule() }
        beginLifecycleLogDrain(application)
    }

    func applicationWillEnterForeground(_: UIApplication) {
        BackgroundExecution.foregroundWillEnter()
    }

    nonisolated func userNotificationCenter(_: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard SettingsRoute.opensSchedules(response.notification.request.content.userInfo) else { return }
        await MainActor.run { SettingsRoute.shared.page = .recurring }
    }

    // swiftlint:disable:next async_without_await - UNUserNotificationCenterDelegate's async requirement; a sync one is never called
    nonisolated func userNotificationCenter(_: UNUserNotificationCenter, willPresent _: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound, .badge]
    }

    func applicationSignificantTimeChange(_: UIApplication) {
        Task { await RecurringExecutor.shared.timeChanged() }
    }

    @objc private func timeZoneChanged() {
        Task { await RecurringExecutor.shared.timeChanged() }
    }

    /// Restore words stay visible for RN parity. System VoiceOver can read visible content;
    /// iOS has no third-party accessibility services, but keyboard extensions could log it.
    /// Block those app-wide so a non-secure recovery field never hands them keystrokes.
    func application(_: UIApplication, shouldAllowExtensionPointIdentifier identifier: UIApplication.ExtensionPointIdentifier) -> Bool {
        identifier != .keyboard
    }

    func application(_: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        PushRegistration.shared.deliver(.success(deviceToken))
    }

    func application(_: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        PushRegistration.shared.deliver(.failure(error))
    }

    /// Silent pushes from the Noah server (via Expo). iOS allows ~30 s; jobs
    /// get 25 s and a report under way at the deadline up to 3 s more, so the
    /// completion handler is always called in time.
    func application(
        _: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any]) async -> UIBackgroundFetchResult {
        guard let notification = PushPayload.parse(userInfo) else { return .noData }
        let jobs = PushJobs(dependencies: .live(.shared))
        return await Self.completeWithin(.seconds(25)) { try await jobs.handle(notification) }
    }

    /// Unstructured workers may settle native work after this callback has returned.
    /// A task group would wait for that work even after cancelling it.
    static func completeWithin(_ timeout: Duration, logFile: LogFile = .shared,
                               operation: @escaping @Sendable () async throws -> Void) async -> UIBackgroundFetchResult {
        await withCheckedContinuation { continuation in
            _ = BackgroundExecution(timeout: timeout, logFile: logFile, completion: {
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
