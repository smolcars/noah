import UIKit
import UserNotifications

/// Push registration: notification permission, APNs device token, then
/// `register_push_token` on the Noah server, which replaces any Expo token the
/// RN app registered.
@MainActor
final class PushRegistration {
    static let shared = PushRegistration()

    enum Outcome: Equatable {
        case registered(pushToken: String)
        case permissionDenied
        /// Simulator: no real APNs token, like the RN app's `device_not_supported`.
        case deviceNotSupported

        func requireCompleted() throws {
            if case .permissionDenied = self { throw IdentityValidationError("Push notification registration did not complete.") }
        }
    }

    private var tokenWaiters: [CheckedContinuation<Data, Error>] = []
    private let log = AppLog("pushRegistration")

    func requestPermission() async -> Bool {
        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        if settings.authorizationStatus != .authorized {
            _ = try? await center.requestAuthorization(options: [.alert, .badge, .sound])
        }
        return await center.notificationSettings().authorizationStatus == .authorized
    }

    func register(services: AppServices = .shared) async throws -> Outcome {
        #if targetEnvironment(simulator)
            return .deviceNotSupported
        #else
            guard await requestPermission() else { return .permissionDenied }
            let body = Self.body(deviceToken: try await deviceToken(), sandbox: ApnsEnvironment.isDevelopment())
            _ = try await services.client.registerPushToken(body: .json(body)).ok
            log.i("Registered push token with the server")
            return .registered(pushToken: body.pushToken)
        #endif
    }

    nonisolated static func body(deviceToken: Data, sandbox: Bool) -> Components.Schemas.RegisterPushToken {
        .init(
            apnsSandbox: sandbox, provider: .apns,
            pushToken: deviceToken.map { String(format: "%02x", $0) }.joined())
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
