import Foundation
@testable import Noah
import Testing

struct OnboardingIdentityTests {
    @Test func npubFixturesMatchReactNative() throws {
        for test in try Fixtures.load("nostr-npub", input: String?.self, expected: String.self) {
            #expect(Nostr.npub(from: test.input) == test.expected, "\(test.name)")
        }
    }

    @Test func normalizesUsernameAndOptionalPublicKey() throws {
        let payload = try LightningIdentity.payload(username: "  Alice.Test_1-2\n", npub: "  npub1test\n")
        #expect(payload.username == "alice.test_1-2")
        #expect(payload.nostrPubkey == "npub1test")
        #expect(try LightningIdentity.payload(username: "a", npub: " \n").nostrPubkey == nil)
        #expect(try LightningIdentity.payload(username: "0._-", npub: "").username == "0._-")
    }

    @Test func rejectsInvalidUsernamesAndPrivateKeys() {
        for username in ["", " \n", "two words", "alice@example.com", "é", "a/b"] {
            #expect(throws: IdentityValidationError.self) { try LightningIdentity.payload(username: username, npub: "") }
        }
        for key in [" nsec1test ", "NSEC1test", "b0635d6a", "note1test"] {
            #expect(throws: IdentityValidationError.self) { try LightningIdentity.payload(username: "alice", npub: key) }
        }
    }
}
