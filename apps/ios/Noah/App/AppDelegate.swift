import UIKit
import UserNotifications

/// Hosts the UIKit callbacks SwiftUI does not expose (APNs registration,
/// background remote notifications). Push handling lands in Phase 0.6.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        SettingsStorage.bootstrap()
        return true
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
        let work = Task { try await jobs.handle(notification) }
        let deadline = Task {
            try await Task.sleep(for: .seconds(25))
            work.cancel()
        }
        defer { deadline.cancel() }
        do {
            try await work.value
            return .newData
        } catch {
            AppLog("push").w("Background job did not complete")
            return .failed
        }
    }
}
