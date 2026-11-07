import Foundation
@testable import Noah
import Testing

/// Siri's phrases (`NoahAppShortcuts`) and their hand translations (`Resources/AppShortcuts.xcstrings`) stay in step:
/// every phrase in every locale, each keeping the app's name, none said twice. General Translation never sees them.
struct AppShortcutPhraseTests {
    private struct Catalog: Decodable {
        let sourceLanguage: String
        let strings: [String: Entry]
    }
    private struct Entry: Decodable { let localizations: [String: Localization] }
    private struct Localization: Decodable { let stringUnit: Unit }
    private struct Unit: Decodable { let state: String; let value: String }

    private static let name = "${applicationName}"
    private static let ios = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()

    /// The phrases in source order, as catalog keys.
    private static func phrases() throws -> [String] {
        let source = try String(contentsOf: ios.appending(path: "Noah/Services/AppIntents/NoahAppShortcuts.swift"), encoding: .utf8)
        return source.components(separatedBy: .newlines).filter { $0.contains(#"\(.applicationName)"#) }.map {
            $0.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "\","))
                .replacingOccurrences(of: #"\(.applicationName)"#, with: name)
        }
    }

    private static func catalog() throws -> Catalog {
        try JSONDecoder().decode(Catalog.self, from: Data(contentsOf: ios.appending(path: "Noah/Resources/AppShortcuts.xcstrings")))
    }

    @Test func everyPhraseIsTranslatedIntoEveryLocale() throws {
        let phrases = try Self.phrases(), catalog = try Self.catalog()
        #expect(phrases.count > 90)
        #expect(Set(phrases).count == phrases.count, "A phrase Siri hears for two shortcuts")
        #expect(catalog.sourceLanguage == "en")
        #expect(Set(catalog.strings.keys) == Set(phrases), "Stale or missing catalog keys")
        for phrase in phrases { #expect(phrase.components(separatedBy: Self.name).count == 2, "\(phrase)") }
        for locale in LegacyLocale.supported where locale != "en" {
            var seen = Set<String>()
            for phrase in phrases {
                let unit = try #require(catalog.strings[phrase]?.localizations[locale]?.stringUnit, "\(locale): \(phrase)")
                #expect(unit.state == "translated", "\(locale): \(phrase)")
                #expect(unit.value.components(separatedBy: Self.name).count == 2, "\(locale): \(unit.value)")
                #expect(seen.insert(unit.value).inserted, "\(locale) says two phrases the same way: \(unit.value)")
            }
        }
    }

    @Test func providerKeepsRoomForPay() throws {
        let source = try String(contentsOf: Self.ios.appending(path: "Noah/Services/AppIntents/NoahAppShortcuts.swift"), encoding: .utf8)
        // Apple allows 10 App Shortcuts; Phase 2's Pay someone needs one.
        #expect(source.components(separatedBy: "AppShortcut(\n").count - 1 <= 9)
    }

    @Test func settingsShowsPhrasesAsSiriHearsThem() throws {
        let phrases = try Self.phrases(), catalog = try Self.catalog()
        #expect(Set(SiriExamplePhrase.all).isSubset(of: Set(phrases)))
        let displayName = try #require(Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String)
        for phrase in SiriExamplePhrase.all {
            let shown = SiriPage.spoken(phrase)
            #expect(shown.contains(displayName) && !shown.contains("${"), "\(shown)")
        }
        // The compiled catalog ships in the app.
        let japanese = L10n.localizedBundle("ja").localizedString(forKey: SiriExamplePhrase.scan, value: nil, table: "AppShortcuts")
        #expect(japanese == catalog.strings[SiriExamplePhrase.scan]?.localizations["ja"]?.stringUnit.value)
    }
}
