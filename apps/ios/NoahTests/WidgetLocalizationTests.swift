import Foundation
import Testing
@testable import Noah

@Suite(.serialized)
struct WidgetLocalizationTests {
    /// The extension looks strings up in its own bundle, so it must ship the app's catalog.
    @Test func widgetExtensionShipsTheCatalog() throws {
        let url = try #require(Bundle.main.builtInPlugInsURL?.appending(path: "NoahWidget.appex"))
        let widget = try #require(Bundle(url: url))
        for language in LegacyLocale.supported {
            let strings = try #require(widget.path(forResource: language, ofType: "lproj").flatMap(Bundle.init(path:)), "\(language)")
            #expect(strings.localizedString(forKey: "Offchain", value: nil, table: nil) == L10n.text("Offchain", locale: language))
            let blocks = strings.localizedString(forKey: "{n} blocks", value: nil, table: nil)
            #expect(String(format: blocks, locale: Locale(identifier: language), Int64(1)) == L10n.plural("{n} blocks", count: 1, locale: language))
        }
        let spanish = try #require(widget.path(forResource: "es", ofType: "lproj").flatMap(Bundle.init(path:)))
        #expect(spanish.localizedString(forKey: "Onchain", value: nil, table: nil) == "En la cadena")
    }

    @Test func languageChoiceReachesTheWidget() throws {
        let group = try #require(UserDefaults(suiteName: AppVariant.regtest.appGroup))
        let preference = UserDefaults.standard.string(forKey: LegacyLocale.key)
        let mirrored = group.string(forKey: LegacyLocale.key)
        defer {
            UserDefaults.standard.set(preference, forKey: LegacyLocale.key)
            group.set(mirrored, forKey: LegacyLocale.key)
        }
        UserDefaults.standard.set("ja", forKey: LegacyLocale.key)
        WidgetDataWriter.publishLanguage(variant: .regtest)
        #expect(LegacyLocale.resolved(from: group) == "ja")
        UserDefaults.standard.removeObject(forKey: LegacyLocale.key)
        WidgetDataWriter.publishLanguage(variant: .regtest)
        #expect(group.string(forKey: LegacyLocale.key) == nil)
    }
}
