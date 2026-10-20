import Foundation

/// Language the user picked in the React Native app. gt-react-native stored
/// it in `UserDefaults.standard` under its locale cookie name.
enum LegacyLocale {
    static let key = "generaltranslation.locale"

    static func read(from defaults: UserDefaults = .standard) -> String? {
        defaults.string(forKey: key)
    }

    static let supported = ["en", "es", "ja", "ko", "sv"]

    static func resolve(preference: String?, system: [String]) -> String {
        for candidate in [preference].compactMap({ $0 }) + system {
            let language = candidate.replacingOccurrences(of: "_", with: "-").split(separator: "-").first.map(String.init)?.lowercased() ?? ""
            if supported.contains(language) { return language }
        }
        return "en"
    }

    static func resolved(from defaults: UserDefaults = .standard) -> String {
        resolve(preference: read(from: defaults), system: [Locale.current.identifier] + Locale.preferredLanguages)
    }
}
