import Foundation
import Testing
@testable import Noah

@MainActor struct AccountParityTests {
    @Test func settingsSummariesIncludeCurrentValuesAndAmountExamples() {
        let english = Locale(identifier: "en")
        #expect(PreferenceView.summary(kind: .language, code: "es", locale: english) == "Español")
        #expect(PreferenceView.summary(kind: .currency, code: "EUR", locale: english) == "EUR · Euro")
        let spanish = Locale(identifier: "es")
        #expect(PreferenceView.summary(kind: .currency, code: "USD", locale: spanish) == "USD · \(spanish.localizedString(forCurrencyCode: "USD")!)")
        #expect(PreferenceView.summary(kind: .unit, code: "bip177", locale: english) == "Bitcoin (BIP-177) · ₿\u{00a0}1,234")
        #expect(PreferenceView.summary(kind: .unit, code: "sats", locale: spanish) == "Satoshis · 1234 sats")
    }

    @Test func profileNameLimitMatchesRN() throws {
        for item in try Fixtures.load("profile-name", input: String.self, expected: String.self) {
            #expect(ProfileView.limitedName(item.input) == item.expected, "\(item.name)")
        }
    }

    @Test func verifiedReplacementExplainsTheOldAddressStaysActive() {
        #expect(EmergencyEmailView.explanation(fromOnboarding: false, verified: true, email: "old@example.com", locale: "en") ==
            "Your current email, old@example.com, will stay active until the new email is verified.")
        #expect(EmergencyEmailView.explanation(fromOnboarding: false, verified: true, email: nil, locale: "en") ==
            "Your current email will stay active until the new email is verified.")
        for (onboarding, verified) in [(true, true), (true, false), (false, false)] {
            #expect(EmergencyEmailView.explanation(fromOnboarding: onboarding, verified: verified, email: "old@example.com", locale: "en").hasPrefix("Email is optional."))
        }
        #expect(EmergencyEmailView.explanation(fromOnboarding: false, verified: true, email: "old@example.com", locale: "es") ==
            L10n.text("Your current email, {email}, will stay active until the new email is verified.", "old@example.com", locale: "es"))
    }
}
