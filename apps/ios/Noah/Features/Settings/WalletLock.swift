import LocalAuthentication
import Observation
import SwiftUI

/// Inactive alone (Control Center, system prompts) does not lock, like RN.
struct BiometricLockPolicy: Equatable {
    var enabled: Bool
    private(set) var locked: Bool
    private(set) var generation = 0

    init(enabled: Bool) { self.enabled = enabled; locked = enabled }
    mutating func background() {
        generation += 1
        if enabled { locked = true }
    }
    mutating func authenticated(generation: Int) {
        if generation == self.generation { locked = false }
    }
    mutating func setEnabled(_ value: Bool) {
        enabled = value
        // The toggle itself has already authenticated.
        locked = false
    }
}

@MainActor
@Observable
final class WalletLock {
    var policy: BiometricLockPolicy
    private(set) var authenticating = false
    private(set) var error: String?
    private var context: LAContext?

    init() {
        policy = .init(enabled: SettingsStorage.shared.persistedState(
            PersistedWalletState.self, key: PersistedWalletState.key)?.isBiometricsEnabled == true)
    }

    static var available: Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
    }

    func authenticate(_ reason: String) async -> Bool {
        guard !authenticating else { return false }
        authenticating = true
        error = nil
        let context = LAContext()
        self.context = context
        defer { authenticating = false; self.context = nil; context.invalidate() }
        do {
            return try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)
        } catch {
            self.error = String(localized: "Authentication failed. Please try again.")
            return false
        }
    }

    func unlock() async {
        let generation = policy.generation
        if await authenticate(String(localized: "Authenticate to unlock Noah")) {
            policy.authenticated(generation: generation)
        }
    }

    func background() {
        policy.background()
        // A real background transition must invalidate even an outstanding prompt.
        context?.invalidate()
    }

    func setEnabled(_ enabled: Bool) async throws {
        let generation = policy.generation
        guard await authenticate(enabled ? String(localized: "Authenticate to enable biometrics")
                                 : String(localized: "Authenticate to disable biometrics")) else { return }
        guard generation == policy.generation else { return }
        var wallet = SettingsStorage.shared.persistedState(PersistedWalletState.self, key: PersistedWalletState.key) ?? .init()
        wallet.isBiometricsEnabled = enabled
        try SettingsStorage.shared.setPersistedState(wallet, key: PersistedWalletState.key, version: PersistedWalletState.version)
        policy.setEnabled(enabled)
    }
}

struct WalletLockView: View {
    @Environment(WalletLock.self) private var lock
    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: "lock.fill").font(.largeTitle)
            Text("Noah is Locked").font(NoahFont.title)
            Text("Authenticate to access your wallet").foregroundStyle(NoahColor.mutedForeground)
            if let error = lock.error { Text(error).foregroundStyle(NoahColor.destructive) }
            NoahButton(title: "Unlock", isLoading: lock.authenticating) { Task { await lock.unlock() } }
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(NoahColor.background.ignoresSafeArea())
    }
}

/// Separate scene window covers presented sheets as well as the tab hierarchy.
struct WalletLockOverlay: UIViewRepresentable {
    let lock: WalletLock
    let visible: Bool

    func makeUIView(context: Context) -> UIView { UIView() }
    func makeCoordinator() -> Coordinator { Coordinator() }
    func updateUIView(_ view: UIView, context: Context) {
        let coordinator = context.coordinator
        // The representable joins its window after its first update.
        DispatchQueue.main.async {
            guard let scene = view.window?.windowScene else { return }
            if visible {
                if coordinator.window == nil {
                    let window = UIWindow(windowScene: scene)
                    window.windowLevel = .alert + 1
                    window.rootViewController = UIHostingController(rootView: WalletLockView().environment(lock))
                    coordinator.window = window
                }
                coordinator.window?.isHidden = false
            } else {
                coordinator.window?.isHidden = true
                coordinator.window = nil
            }
        }
    }
    static func dismantleUIView(_ uiView: UIView, coordinator: Coordinator) {
        coordinator.window?.isHidden = true
        coordinator.window = nil
    }
    @MainActor final class Coordinator { var window: UIWindow? }
}
