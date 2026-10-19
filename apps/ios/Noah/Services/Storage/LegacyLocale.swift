import Foundation

/// Language the user picked in the React Native app. gt-react-native stored
/// it in `UserDefaults.standard` under its locale cookie name.
enum LegacyLocale {
    static let key = "generaltranslation.locale"

    static func read(from defaults: UserDefaults = .standard) -> String? {
        defaults.string(forKey: key)
    }
}
