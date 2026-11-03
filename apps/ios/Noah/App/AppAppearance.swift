import UIKit

/// Settings → Display → Appearance (docs/native-rewrite/design/appearance.md): follow the phone, or keep Noah
/// light or dark. A phone preference like the language, so it lives beside it in `UserDefaults.standard` and
/// survives Delete wallet. Android stores the same key and strings.
enum AppAppearance: String, CaseIterable {
    case automatic, light, dark

    static let key = "appearance"

    /// Missing or unknown is Automatic, so wallets from before the setting open as they always did.
    init(stored: String?) {
        self = stored.flatMap(Self.init(rawValue:)) ?? .automatic
    }

    static func read(from defaults: UserDefaults = .standard) -> Self {
        Self(stored: defaults.string(forKey: key))
    }

    func save(to defaults: UserDefaults = .standard) {
        defaults.set(rawValue, forKey: Self.key)
    }

    /// The windows' `overrideUserInterfaceStyle`.
    var style: UIUserInterfaceStyle {
        switch self {
        case .automatic: .unspecified
        case .light: .light
        case .dark: .dark
        }
    }

    /// What Noah shows while the phone is on `system`.
    func resolved(system: UIUserInterfaceStyle) -> UIUserInterfaceStyle {
        self == .automatic ? (system == .dark ? .dark : .light) : style
    }
}

/// Puts the choice on every window: SwiftUI's `preferredColorScheme` would miss alerts, presented
/// controllers and the keyboard.
@MainActor
enum AppearanceWindows {
    /// At launch, before any window exists.
    static func start(defaults: UserDefaults = .standard) {
        style = AppAppearance.read(from: defaults).style
    }

    /// Each window takes the choice as it becomes visible, before its first frame, so a relaunch in Dark never
    /// flashes light. That covers new scenes and the windows Noah makes (the lock screen's).
    static func adopt(_ window: UIWindow) {
        window.overrideUserInterfaceStyle = style
    }

    static func apply(_ appearance: AppAppearance) {
        style = appearance.style
        for case let scene as UIWindowScene in UIApplication.shared.connectedScenes {
            for window in scene.windows { window.overrideUserInterfaceStyle = style }
        }
    }

    private static var style = UIUserInterfaceStyle.unspecified
}
