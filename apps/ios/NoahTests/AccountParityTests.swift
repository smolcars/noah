import Foundation
import Testing
@testable import Noah

@MainActor struct AccountParityTests {
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
