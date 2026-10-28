import BarkBoltFFI
import Foundation
@testable import Noah
import Testing

struct DebugJSONTests {
    @Test func debugInfoShowsExactlyTheThreeSupportIdentifiers() throws {
        let text = try DebugJSON.debugInfo(.init(network: .regtest, mailboxId: "mailbox", vtxoXpub: "tpub-test"))
        let result = try #require(JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: String])
        #expect(result == ["network": "regtest", "mailboxId": "mailbox", "vtxoXpub": "tpub-test"])
    }
    @Test func errorsKeepTheExistingSafeDescriptionBoundary() {
        #expect(SafeErrorDescription.of(BarkError(message: "token=fake-test-token")) == "[redacted]")
        #expect(SafeErrorDescription.of(NSError(domain: "private request", code: 1, userInfo: [NSLocalizedDescriptionKey: "secret input"])) == LogFile
            .redact("NSError"))
    }
}
