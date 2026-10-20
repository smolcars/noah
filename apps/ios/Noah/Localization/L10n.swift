import Foundation

/// Source-keyed GT messages, including callers that run without SwiftUI (push/background jobs).
enum L10n {
    static let bundle = Bundle(for: BundleMarker.self)
    private final class BundleMarker {}

    static func text(_ source: String, _ arguments: Any..., locale: String = LegacyLocale.resolved()) -> String {
        let english = localizedBundle("en").localizedString(forKey: source, value: source, table: nil)
        let candidate = localizedBundle(locale).localizedString(forKey: source, value: english, table: nil)
        // Xcode emits the raw source key for untranslated target-language catalog entries.
        let localized = candidate == source ? english : candidate
        // Avoid treating a native-only string containing a percent sign as a format string.
        guard !arguments.isEmpty else { return localized }
        return String(format: localized, locale: Locale(identifier: locale), arguments: arguments.map { String(describing: $0) })
    }

    static func plural(_ source: String, count: Int, _ arguments: String..., locale: String = LegacyLocale.resolved()) -> String {
        let template = localizedBundle(locale).localizedString(forKey: source, value: source, table: nil)
        return String(format: template, locale: Locale(identifier: locale), arguments: [Int64(count)] + arguments.map { $0 as CVarArg })
    }

    static func localizedBundle(_ locale: String) -> Bundle {
        guard let path = bundle.path(forResource: locale, ofType: "lproj"), let translated = Bundle(path: path) else { return bundle }
        return translated
    }
}
