import Foundation
@testable import Noah
import Testing

@MainActor struct AccountParityTests {
    @Test func profileNameLimitMatchesRN() throws {
        for item in try Fixtures.load("profile-name", input: String.self, expected: String.self) {
            #expect(ProfileView.limitedName(item.input) == item.expected, "\(item.name)")
        }
    }
}
