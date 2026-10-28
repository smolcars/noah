@testable import Noah
import Testing

/// When Send asks the server for Noah Lightning addresses: React Native's
/// `useLightningAddressSuggestions` rules, and Android's `SendRecipientTest` cases.
struct SendSuggestionQueryTests {
    func query(_ value: String) -> String? { SendRecipientService.suggestionQuery(value, domain: "example.com") }

    @Test func asksForNamesAndOurPartialDomain() {
        #expect(query("al") == "al")
        #expect(query("  Al_ice.B-0 ") == "al_ice.b-0")
        #expect(query("lightning:alice") == "alice")
        #expect(query("LIGHTNING:Alice@Ex") == "alice@ex")
        #expect(query("alice@") == "alice@")
        #expect(query("alice@example.com") == "alice@example.com")
        #expect(query("a" + String(repeating: "b", count: 31)) == "a" + String(repeating: "b", count: 31))
        #expect(query("1alice@example.com") == "1alice@example.com")
    }

    @Test(arguments: [
        "", "a", "lightning:a", "@example.com", "1alice", "_alice", "alice!", "ali ce", "a" + String(repeating: "b", count: 32),
        "alice@other.com", "alice@example.com.evil", "alice@ex@ample", "alice@ex!",
        "lnbc1anything", "bc1qanything", "tb1qanything", "bcrt1qanything", "lntbanything", "lnbcrtanything",
        "arkanything", "tarkanything", "lno1anything",
    ])
    func skipsEverythingElse(_ value: String) {
        #expect(query(value) == nil)
    }
}
