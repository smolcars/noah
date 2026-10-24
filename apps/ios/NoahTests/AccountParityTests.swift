import Foundation
import Testing
@testable import Noah

@MainActor struct AccountParityTests {
    @Test func languagesAreNamedInTheirOwnWords() {
        #expect(SettingsLanguageSheet.name("en") == "English")
        #expect(SettingsLanguageSheet.name("es") == "Español")
        #expect(SettingsLanguageSheet.name("sv-SE") == "Svenska")
        #expect(SettingsLanguageSheet.codes == ["en", "es", "ja", "ko", "sv"])
    }

    @Test func profileNameLimitMatchesRN() throws {
        for item in try Fixtures.load("profile-name", input: String.self, expected: String.self) {
            #expect(ProfileView.limitedName(item.input) == item.expected, "\(item.name)")
        }
    }
}
