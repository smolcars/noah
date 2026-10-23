import BarkBoltFFI
import Foundation
import Testing

@testable import Noah

struct DebugJSONTests {
    @Test func importsArePrettyJSONAndMissingArkInfoIsNull() throws {
        #expect(try DebugJSON.imported(["id\"1", "id2"]) == "[\n  \"id\\\"1\",\n  \"id2\"\n]")
        #expect(try DebugJSON.ark(nil) == "null")
    }
    @Test func decodedResultsKeepNestedFieldsAndUnsignedAmounts() throws {
        let vtxo = Vtxo(serverPubkey: "public", exitDelta: 12, anchorPoint: .init(txid: "anchor", vout: 1),
            point: .init(txid: "point", vout: 2), id: "id", amountSats: UInt64.max, expiryHeight: 100,
            kind: "pubkey", state: .unknown, exitDepth: 1, exitTxWeightWu: 42, registered: false)
        let text = try DebugJSON.decoded(vtxo)
        let result = try #require(JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any])
        #expect(text.contains("\n  \"amountSats\" : 18446744073709551615"))
        #expect((result["point"] as? [String: Any])?["txid"] as? String == "point")
        #expect(result["state"] as? String == "unknown")
    }
    @Test func debugInfoShowsExactlyTheThreeSupportIdentifiers() throws {
        let text = try DebugJSON.debugInfo(.init(network: .regtest, mailboxId: "mailbox", vtxoXpub: "tpub-test"))
        let result = try #require(JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: String])
        #expect(result == ["network": "regtest", "mailboxId": "mailbox", "vtxoXpub": "tpub-test"])
    }
    @Test func errorsKeepTheExistingSafeDescriptionBoundary() {
        #expect(SafeErrorDescription.of(BarkError(message: "token=fake-test-token")) == "[redacted]")
        #expect(SafeErrorDescription.of(NSError(domain: "private request", code: 1, userInfo: [NSLocalizedDescriptionKey: "secret input"])) == LogFile.redact("NSError"))
    }
}
