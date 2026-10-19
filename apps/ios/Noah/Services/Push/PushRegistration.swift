import UIKit
import UserNotifications

/// Push registration, ported from `registerForPushNotificationsAsync` and
/// `registerPushTokenWithServer`: notification permission, APNs device
/// token, Expo token (same installation ID as the RN app, so normally the
/// same token), then `register_push_token` on the Noah server.
@MainActor
final class PushRegistration {
    static let shared = PushRegistration()

    enum Outcome: Equatable {
        case registered(expoToken: String)
        case permissionDenied
        /// Simulator: no real APNs token, like the RN app's `device_not_supported`.
        case deviceNotSupported
    }

    private var tokenWaiters: [CheckedContinuation<Data, Error>] = []
    private let log = AppLog("pushRegistration")

    func requestPermission() async -> Bool {
        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral:
            return true
        case .notDetermined:
            return (try? await center.requestAuthorization(options: [.alert, .badge, .sound])) ?? false
        default:
            return false
        }
    }

    func register(services: AppServices = .shared) async throws -> Outcome {
        guard await requestPermission() else { return .permissionDenied }
        #if targetEnvironment(simulator)
            return .deviceNotSupported
        #else
            let deviceToken = try await deviceToken()
            let request = ExpoPushToken.request(
                installationID: try ExpoInstallationID.getOrCreate(),
                deviceToken: deviceToken,
                appID: Bundle.main.bundleIdentifier ?? "",
                development: ApnsEnvironment.isDevelopment())
            let expoToken = try await ExpoPushToken().fetch(request)
            _ = try await services.client.registerPushToken(body: .json(.init(pushToken: expoToken))).ok
            log.i("Registered push token with the server")
            return .registered(expoToken: expoToken)
        #endif
    }

    private func deviceToken() async throws -> Data {
        try await withCheckedThrowingContinuation { continuation in
            tokenWaiters.append(continuation)
            UIApplication.shared.registerForRemoteNotifications()
        }
    }

    /// Called from the app delegate's APNs callbacks.
    func deliver(_ result: Result<Data, Error>) {
        let waiters = tokenWaiters
        tokenWaiters.removeAll()
        for waiter in waiters { waiter.resume(with: result) }
    }
}
