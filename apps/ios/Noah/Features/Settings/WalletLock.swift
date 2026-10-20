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

enum RecoveryPhraseAuthentication: Equatable {
    case authenticated, noScreenLock, denied

    static func unavailable(_ error: NSError?) -> Self {
        error?.domain == LAError.errorDomain && error?.code == LAError.passcodeNotSet.rawValue
            ? .noScreenLock : .denied
    }
}

@MainActor
@Observable
final class WalletLock {
    var policy: BiometricLockPolicy
    private(set) var authenticating = false
    private(set) var error: String?
    private var context: LAContext?

    init(settings: SettingsStorage = .shared) {
        policy = .init(enabled: settings.persistedState(
            PersistedWalletState.self, key: PersistedWalletState.key)?.isBiometricsEnabled == true)
    }

    static var available: Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
    }

    static var hasNoScreenLock: Bool {
        var error: NSError?
        return !LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: &error)
            && RecoveryPhraseAuthentication.unavailable(error) == .noScreenLock
    }

    func authenticate(_ reason: String) async -> Bool {
        await authenticateRecoveryPhrase(reason) == .authenticated
    }

    func authenticateRecoveryPhrase(_ reason: String) async -> RecoveryPhraseAuthentication {
        guard !authenticating else { return .denied }
        authenticating = true
        error = nil
        let context = LAContext()
        self.context = context
        defer { authenticating = false; self.context = nil; context.invalidate() }
        var unavailable: NSError?
        if !context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &unavailable),
           RecoveryPhraseAuthentication.unavailable(unavailable) == .noScreenLock { return .noScreenLock }
        do {
            return try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) ? .authenticated : .denied
        } catch {
            if RecoveryPhraseAuthentication.unavailable(error as NSError) == .noScreenLock { return .noScreenLock }
            self.error = L10n.text("Authentication failed. Please try again.")
            return .denied
        }
    }

    func unlock() async {
        let generation = policy.generation
        if await authenticate(L10n.text("Authenticate to unlock Noah")) {
            policy.authenticated(generation: generation)
        }
    }

    func background() {
        policy.background()
        // A real background transition must invalidate even an outstanding prompt.
        context?.invalidate()
    }

    func reset() {
        context?.invalidate()
        policy.background() // Invalidate any authentication started for the deleted wallet.
        policy.setEnabled(false)
        error = nil
    }

    func setEnabled(_ enabled: Bool) async throws {
        let generation = policy.generation
        guard await authenticate(enabled ? L10n.text("Authenticate to enable biometrics")
                                 : L10n.text("Authenticate to disable biometrics")) else { return }
        guard generation == policy.generation else { return }
        var wallet = SettingsStorage.shared.persistedState(PersistedWalletState.self, key: PersistedWalletState.key) ?? .init()
        wallet.isBiometricsEnabled = enabled
        try SettingsStorage.shared.setPersistedState(wallet, key: PersistedWalletState.key, version: PersistedWalletState.version)
        policy.setEnabled(enabled)
    }
}

/// Always retain the subtree, including navigation paths and drafts, while covering it.
struct WalletContentProtection: ViewModifier {
    let covered: Bool
    func body(content: Content) -> some View {
        content
            .opacity(covered ? 0 : 1)
            .allowsHitTesting(!covered)
            .accessibilityHidden(covered)
    }
}

struct WalletLockView: View {
    @Environment(WalletLock.self) private var lock
    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: "lock.fill").font(.largeTitle)
            Text("Noah is Locked").font(NoahFont.title)
            Text("Authenticate to access your wallet").foregroundStyle(NoahColor.mutedForeground)
            if let error = lock.error { Text(LocalizedStringKey(error)).foregroundStyle(NoahColor.destructive) }
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
    var protectOnBackground = false

    func makeUIView(context: Context) -> SourceView {
        let view = SourceView()
        view.onWindow = { [weak coordinator = context.coordinator, weak view] in
            coordinator?.update(source: view?.window)
        }
        return view
    }
    func makeCoordinator() -> Coordinator { Coordinator() }
    func updateUIView(_ view: SourceView, context: Context) {
        context.coordinator.lock = lock
        context.coordinator.visible = visible
        context.coordinator.protectOnBackground = protectOnBackground
        context.coordinator.update(source: view.window)
    }
    static func dismantleUIView(_ uiView: SourceView, coordinator: Coordinator) { coordinator.uncover() }

    final class SourceView: UIView {
        var onWindow: (() -> Void)?
        override func didMoveToWindow() { super.didMoveToWindow(); onWindow?() }
    }

    @MainActor final class Coordinator: NSObject {
        var window: UIWindow?
        var lock: WalletLock?
        var visible = false
        var protectOnBackground = false
        private weak var source: UIWindow?
        private weak var coveredWindow: UIWindow?
        private var wasAccessibilityHidden = false
        private var wasInteractive = true

        override init() {
            super.init()
            NotificationCenter.default.addObserver(self, selector: #selector(background), name: UIScene.didEnterBackgroundNotification, object: nil)
        }

        @objc private func background(_ notification: Notification) {
            guard protectOnBackground, let scene = notification.object as? UIWindowScene,
                  scene === source?.windowScene else { return }
            // Cover synchronously before UIKit takes its app-switcher snapshot.
            show()
        }

        func update(source: UIWindow?) {
            self.source = source
            if visible { show() } else { uncover() }
        }

        private func show() {
            guard let source, let scene = source.windowScene, let lock else { return }
            cover(source)
            let newlyShown = window == nil
            if window == nil {
                source.endEditing(true)
                let window = UIWindow(windowScene: scene)
                window.windowLevel = .alert + 1
                window.backgroundColor = UIColor(NoahColor.background)
                window.isOpaque = true
                window.rootViewController = UIHostingController(rootView: WalletLockView().environment(lock))
                window.rootViewController?.view.accessibilityViewIsModal = true
                self.window = window
            }
            window?.isHidden = false
            if newlyShown { UIAccessibility.post(notification: .screenChanged, argument: window?.rootViewController?.view) }
        }

        func cover(_ source: UIWindow?) {
            guard coveredWindow == nil, let source else { return }
            coveredWindow = source
            wasAccessibilityHidden = source.accessibilityElementsHidden
            wasInteractive = source.isUserInteractionEnabled
            source.accessibilityElementsHidden = true
            source.isUserInteractionEnabled = false
        }

        func uncover() {
            window?.isHidden = true
            window = nil
            coveredWindow?.accessibilityElementsHidden = wasAccessibilityHidden
            coveredWindow?.isUserInteractionEnabled = wasInteractive
            coveredWindow = nil
        }
    }
}
