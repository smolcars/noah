import Observation
import UIKit
import UserNotifications

/// Expo's general permission status maps provisional/ephemeral to undetermined.
/// RN gates only denied, and never gates a simulator or unfinished onboarding.
@MainActor
@Observable
final class PushPermissionModel {
    enum Status: String, Decodable, Sendable {
        case checking, undetermined, denied, granted

        init(_ status: UNAuthorizationStatus) {
            switch status {
            case .authorized: self = .granted
            case .denied: self = .denied
            default: self = .undetermined
            }
        }
    }

    struct Dependencies {
        var isPhysicalDevice: Bool
        var status: () async throws -> Status
        var request: () async throws -> Void

        static var live: Self {
            #if targetEnvironment(simulator)
                let physical = false
            #else
                let physical = true
            #endif
            return Self(
                isPhysicalDevice: physical,
                status: { Status(await UNUserNotificationCenter.current().notificationSettings().authorizationStatus) },
                request: { _ = try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) })
        }
    }

    private(set) var status: Status = .checking
    private(set) var isRequesting = false
    /// For payment links, not the UI: a status read that started and finished since the app last
    /// left the background. A grant read before Settings changed it mustn't release a held link.
    private var answeredSinceBackground = false
    @ObservationIgnored private var backgrounds = 0
    private let dependencies: Dependencies
    private var generation = 0
    @ObservationIgnored private var backgroundObserver: (any NSObjectProtocol)?

    init(dependencies: Dependencies = .live, center: NotificationCenter = .default) {
        self.dependencies = dependencies
        backgroundObserver = center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.backgrounds += 1
                self?.answeredSinceBackground = false
            }
        }
    }

    func blocksWallet(initialized: Bool) -> Bool {
        initialized && dependencies.isPhysicalDevice && status == .denied
    }

    /// `blocksWallet` has a current answer: always on a simulator; on a device, once read since the background.
    var settled: Bool { !dependencies.isPhysicalDevice || answeredSinceBackground }

    enum OnboardingStep: Equatable {
        case proceed
        /// Denied: only Settings can turn notifications back on.
        case required
        /// Not settled yet (still checking, or the request failed): ask again.
        case ask
    }

    /// Onboarding's required Notifications step. Simulators are exempt, as from
    /// `blocksWallet`; a physical device continues only once notifications are
    /// granted, never on `checking` or `undetermined`.
    var onboardingStep: OnboardingStep {
        guard dependencies.isPhysicalDevice else { return .proceed }
        switch status {
        case .granted: return .proceed
        case .denied: return .required
        case .checking, .undetermined: return .ask
        }
    }

    func refresh() async {
        generation += 1
        let current = generation, since = backgrounds
        guard dependencies.isPhysicalDevice else { status = .checking; return }
        if let value = try? await dependencies.status(), current == generation {
            status = value
            if since == backgrounds { answeredSinceBackground = true }
        }
    }

    func retry(request: Bool) async {
        guard !isRequesting else { return }
        isRequesting = true
        defer { isRequesting = false }
        if request && dependencies.isPhysicalDevice {
            do { try await dependencies.request() }
            catch { return } // RN keeps the last permission state on request failure.
        }
        await refresh()
    }
}
