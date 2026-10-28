import Foundation
@testable import Noah
import Testing

@Suite(.serialized)
struct WidgetLocalizationTests {
    /// The extension looks strings up in its own bundle, so it must ship the app's catalog.
    @Test func widgetExtensionShipsTheCatalog() throws {
        let url = try #require(Bundle.main.builtInPlugInsURL?.appending(path: "NoahWidget.appex"))
        let widget = try #require(Bundle(url: url))
        for language in LegacyLocale.supported {
            let strings = try #require(widget.path(forResource: language, ofType: "lproj").flatMap(Bundle.init(path:)), "\(language)")
            #expect(strings.localizedString(forKey: "Renew", value: nil, table: nil) == L10n.text("Renew", locale: language))
            let hours = strings.localizedString(forKey: "Expires in {n} hours", value: nil, table: nil)
            #expect(String(format: hours, locale: Locale(identifier: language), Int64(1)) == L10n.plural("Expires in {n} hours", count: 1, locale: language))
        }
        let spanish = try #require(widget.path(forResource: "es", ofType: "lproj").flatMap(Bundle.init(path:)))
        #expect(spanish.localizedString(forKey: "Receive", value: nil, table: nil) == "Recibir")
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

    /// After an upgrade the choice is only in the app's own defaults, and a suspended wallet never
    /// writes a balance, so startup itself must hand the language to the widget.
    @MainActor @Test func startupPublishesTheLanguageWhileTheWalletIsSuspended() async throws {
        let group = try #require(UserDefaults(suiteName: AppVariant.current.appGroup))
        let preference = UserDefaults.standard.string(forKey: LegacyLocale.key)
        let mirrored = group.string(forKey: LegacyLocale.key)
        let root = FileManager.default.temporaryDirectory.appending(path: "widget-language-\(UUID())")
        defer {
            UserDefaults.standard.set(preference, forKey: LegacyLocale.key)
            group.set(mirrored, forKey: LegacyLocale.key)
            try? FileManager.default.removeItem(at: root)
        }
        UserDefaults.standard.set("es", forKey: LegacyLocale.key)
        group.removeObject(forKey: LegacyLocale.key)
        let settings = try SettingsStorage(rootPath: root.path)
        try settings.setPersistedState(PersistedWalletState(isInitialized: true, isWalletSuspended: true),
                                       key: PersistedWalletState.key, version: PersistedWalletState.version)
        let model = AppModel(settings: settings, readLaunchState: { .existingWallet }, settleRestore: { .nothingPending })
        await model.start()
        #expect(model.isWalletSuspended)
        #expect(group.string(forKey: LegacyLocale.key) == "es")
    }
}
