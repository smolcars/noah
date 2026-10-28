import Foundation
@testable import Noah
import Testing

@Suite(.serialized)
struct TranslationsTests {
    @MainActor @Test func welcomeLanguageSelectionNeedsNoWallet() throws {
        let previous = UserDefaults.standard.string(forKey: LegacyLocale.key)
        defer { UserDefaults.standard.set(previous, forKey: LegacyLocale.key) }
        let root = FileManager.default.temporaryDirectory.appending(path: "welcome-language-\(UUID())")
        defer { try? FileManager.default.removeItem(at: root) }
        let model = AppModel(settings: try SettingsStorage(rootPath: root.path))
        model.setLanguage("es")
        #expect(model.localeIdentifier == "es")
        #expect(LegacyLocale.read() == "es")
        #expect(model.summary == nil)
        #expect(model.phase == .launching)
    }

    struct Input: Decodable { let locale: String; let source: String; let args: [String] }
    struct Platform: Decodable { let used: [String] }
    struct Coverage: Decodable {
        let locales: [String]
        let coverage: [String: Platform]
    }
    struct PluralEntry: Decodable { let source: String; let translations: [String: [String: String]] }
    struct Plurals: Decodable {
        let plurals: [PluralEntry]
    }

    @Test func everyTypedDeletePromptAsksForTheWordTheCheckAccepts() {
        #expect(DeleteChecklist.confirms(DeleteChecklist.word))
        for language in LegacyLocale.supported {
            #expect(L10n.text("Type delete to confirm", locale: language).contains(DeleteChecklist.word), "\(language)")
        }
    }

    @Test func mixedNetworkURIIsRejectedInEveryLanguageAndErrorsRerender() throws {
        let defaults = UserDefaults.standard
        let preference = defaults.string(forKey: LegacyLocale.key)
        defer { defaults.set(preference, forKey: LegacyLocale.key) }
        let fixture = try #require(Fixtures.load("send-utils", input: SendFixtureTests.ParseInput.self,
                                                 expected: SendFixtureTests.ParseExpected.self).first { $0.name == "destination 5" })
        let uri = "bitcoin:1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa?lightning=\(fixture.input.destination)"
        let expected = SendDestinationError.invalidMethods([.networkMismatch(expected: "mainnet", actual: "testnet")])
        for language in LegacyLocale.supported {
            defaults.set(language, forKey: LegacyLocale.key)
            let parsed = SendDestination.parse(uri, network: "mainnet")
            #expect(parsed.destinationType == nil && parsed.bip321 == nil)
            #expect(parsed.error == expected)
            let cached = SendDestination.parse(fixture.input.destination, network: "mainnet")
            defaults.set("en", forKey: LegacyLocale.key)
            #expect(cached.error?.message() == "Network mismatch: expected mainnet, got testnet")
            defaults.set(language, forKey: LegacyLocale.key)
            #expect(cached.error?.message() == L10n.text("Network mismatch: expected {expected}, got {actual}", "mainnet", "testnet", locale: language))
            if language != "en" { #expect(cached.error?.message() != "Network mismatch: expected mainnet, got testnet") }
        }
    }

    @MainActor @Test func sendModelRetainsFailureArgumentsForDisplay() async {
        let model = SendModel(controller: SendPaymentTests().make(SendPaymentTests.Backend(), SendPaymentTests.Storage()))
        model.destination = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
        await model.acceptRecipient()
        let error = SendDestinationError.networkMismatch(expected: AppVariant.current.rawValue, actual: "mainnet")
        #expect(model.destinationError == error)
        #expect(!model.progress.recipientConfirmed)
        for language in LegacyLocale.supported {
            #expect(model.destinationError?.message(locale: language) == error.message(locale: language))
        }
    }

    @Test func vtxoStatusIdentifiersPreserveDisplayAndUnknownStates() {
        let base = VtxoModel(id: "id", amount: 1000, expiry: 101, state: "Spendable", kind: "", exitDepth: 0,
                             exitWeight: 0, registered: true, serverPubkey: "", exitDelta: 0, anchorPoint: "", point: "", expiring: false, tip: 100)
        #expect(base.statusID == .active && base.status == "Active")
        var value = base
        value.expiring = true
        #expect(value.statusID == .expiring && value.status == "Expiring")
        value.expiry = 100
        #expect(value.statusID == .expired && value.status == "Expired")
        value.state = "Locked"
        #expect(value.statusID == .locked && value.status == "Locked")
        value = base; value.state = "FutureSDKState"
        #expect(value.statusID == nil && value.status == "FutureSDKState")
    }

    @Test func selectedLanguageControlsAmountsWithAnEnglishDevice() {
        let selected = LegacyLocale.resolve(preference: "es", system: ["en-US"])
        let locale = Locale(identifier: selected)
        #expect(BitcoinAmount.format(sats: 1_234_567, unit: .sats, locale: locale) == "1.234.567 sats")
        #expect(FiatCurrency.format(1_234_567.89, currency: "USD", locale: locale).contains("1.234.567,89"))
        #expect(BtcMapDistance.format(12.3, locale: locale) == "12,3 km (7,6 mi)")
    }

    @Test func everyUsedNativeKeyResolvesInEveryLocale() throws {
        let fixture = try Fixtures.decode("translations-coverage", as: Coverage.self)
        let sources = try #require(fixture.coverage["ios"]?.used)
        #expect(sources.count > 500)
        for locale in fixture.locales {
            let bundle = L10n.localizedBundle(locale)
            var keys = Set<String>()
            for type in ["strings", "stringsdict"] {
                if let url = bundle.url(forResource: "Localizable", withExtension: type) {
                    let data = try Data(contentsOf: url)
                    let values = try PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any] ?? [:]
                    keys.formUnion(values.keys)
                }
            }
            for source in sources { #expect(keys.contains(source), "\(locale): \(source)") }
        }
    }

    @Test func templatesMatchTheRNResolver() throws {
        let cases = try Fixtures.load("translations", input: Input.self, expected: String.self)
        #expect(cases.count == 25)
        for item in cases {
            let template = L10n.localizedBundle(item.input.locale).localizedString(forKey: item.input.source, value: nil, table: nil)
            let actual = item.input.args.isEmpty ? template : String(
                format: template,
                locale: Locale(identifier: item.input.locale),
                arguments: item.input.args)
            #expect(actual == item.expected, "\(item.name)")
        }
    }

    @Test func pluralCategoriesAndArgumentsSurviveResourceCompilation() throws {
        let fixture = try Fixtures.decode("translations", as: Plurals.self)
        let coverage = try Fixtures.decode("translations-coverage", as: Coverage.self)
        let used = Set(coverage.coverage["ios"]?.used ?? [])
        let plurals = fixture.plurals.filter { used.contains($0.source) }
        #expect(plurals.count == 1) // The old exit summary used the other; the redesign words staleness itself.
        for locale in LegacyLocale.supported {
            for entry in plurals {
                let forms = try #require(entry.translations[locale])
                for count in [1, 2, 5] {
                    let category = ["en", "es", "sv"].contains(locale) && count == 1 ? "one" : "other"
                    let expected = try #require(forms[category] ?? forms["other"])
                        .replacingOccurrences(of: "{_gt_value_4}", with: String(count))
                        .replacingOccurrences(of: "{_gt_value_3}", with: " · suffix")
                    let actual = entry.source.contains("{_gt_value_3}")
                        ? L10n.plural(entry.source, count: count, " · suffix", locale: locale)
                        : L10n.plural(entry.source, count: count, locale: locale)
                    #expect(actual == expected, "\(locale)/\(count)/\(entry.source)")
                }
            }
        }
    }

    @Test func persistedPreferenceAndSystemFallbackMatchRN() throws {
        #expect(LegacyLocale.resolve(preference: nil, system: ["de-DE", "ja-JP"]) == "ja")
        #expect(LegacyLocale.resolve(preference: "ko-KR", system: ["es-ES"]) == "ko")
        #expect(LegacyLocale.resolve(preference: "unsupported", system: ["sv_SE"]) == "sv")
        #expect(LegacyLocale.resolve(preference: nil, system: ["de-DE"]) == "en")
        let defaults = try #require(UserDefaults(suiteName: "translations-test"))
        defer { defaults.removePersistentDomain(forName: "translations-test") }
        defaults.set("es", forKey: LegacyLocale.key)
        #expect(L10n.text("Cancel", locale: LegacyLocale.resolved(from: defaults)) == "Cancelar")
        defaults.set("ja", forKey: LegacyLocale.key)
        #expect(L10n.text("Cancel", locale: LegacyLocale.resolved(from: defaults)) == "キャンセル")
        #expect(L10n.text("Native-only 100% copy", locale: "es") == "Native-only 100% copy")
        let source = "Automatic payment to {recipient} was skipped. Open the schedule to review its fee and pay manually."
        for locale in LegacyLocale.supported where locale != "en" {
            let translated = L10n.text(source, "Alice", locale: locale)
            #expect(translated.contains("Alice"))
            #expect(!translated.contains("{recipient}") && !translated.contains("%1$"))
            #expect(translated != "Automatic payment to Alice was skipped. Open the schedule to review its fee and pay manually.")
        }
    }

    @Test func backgroundCopyRereadsPersistedLanguageWithoutAnAppModel() throws {
        let defaults = try #require(UserDefaults(suiteName: "background-translations-test"))
        defer { defaults.removePersistentDomain(forName: "background-translations-test") }
        defaults.set("es", forKey: LegacyLocale.key)
        #expect(NotificationCopy.received(sats: 1234, usd: "1.23", unit: .bip177, defaults: defaults).body == "Has recibido ₿\u{00a0}1234 (≈ US$1.23)")
        defaults.set("ja", forKey: LegacyLocale.key)
        #expect(NotificationCopy.received(sats: 1234, usd: nil, unit: .bip177, defaults: defaults).body == "₿\u{00a0}1,234を受け取りました")
        for locale in LegacyLocale.supported {
            defaults.set(locale, forKey: LegacyLocale.key)
            #expect(NotificationCopy.reminder(amount: "1,234 sats", recipient: "Alice", upcoming: true, defaults: defaults).body == L10n.text(
                "{amount} to {recipient} will be sent tomorrow.",
                "1,234 sats",
                "Alice",
                locale: locale))
            #expect(NotificationCopy.reminder(amount: "1,234 sats", recipient: "Alice", upcoming: false, defaults: defaults).body == L10n.text(
                "Open Noah to send {amount} to {recipient}.",
                "1,234 sats",
                "Alice",
                locale: locale))
        }
    }

    @Test func lightningReceiptKeepsLocalizedCopySoundAndTapMetadata() throws {
        let name = "receipt-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: name))
        defer { defaults.removePersistentDomain(forName: name) }
        for locale in LegacyLocale.supported {
            defaults.set(locale, forKey: LegacyLocale.key)
            let content = PushJobs.receivedContent(sats: 1234, usd: "1.23", unit: .sats, defaults: defaults)
            let expected = NotificationCopy.received(sats: 1234, usd: "1.23", unit: .sats, defaults: defaults)
            #expect(content.title == expected.title && content.body == expected.body)
            #expect(content.sound != nil)
            #expect(content.userInfo["notification_type"] as? String == "lightning_payment_received")
            #expect(!SettingsRoute.opensSchedules(content.userInfo))
        }
    }
}
