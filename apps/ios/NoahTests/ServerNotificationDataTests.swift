import Foundation
import Testing

@testable import Noah

/// Decodes the push payloads the server actually produces
/// (`apps/fixtures/server-notification-data.json`, written by
/// `server/src/openapi.rs`) with the client generated from `api/openapi.json`.
struct ServerNotificationDataTests {
    private typealias NotificationData = Components.Schemas.NotificationData

    private struct Case {
        let name: String
        let input: Data
        let expected: String
    }

    private func cases() throws -> [Case] {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appending(path: "fixtures/server-notification-data.json")
        let root = try #require(
            try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        let entries = try #require(root["cases"] as? [[String: Any]])
        return try entries.map { entry in
            Case(
                name: try #require(entry["name"] as? String),
                input: try JSONSerialization.data(withJSONObject: try #require(entry["input"])),
                expected: try #require(entry["expected"] as? String))
        }
    }

    private func notificationType(_ value: NotificationData) -> String {
        switch value {
        case .maintenance: "maintenance"
        case .lightningInvoiceRequest: "lightning_invoice_request"
        case .lightningClaimRequest: "lightning_claim_request"
        case .backupTrigger: "backup_trigger"
        case .heartbeat: "heartbeat"
        case .recurringPaymentDue: "recurring_payment_due"
        }
    }

    @Test func decodesAndRoundTripsEveryServerVariant() throws {
        let all = try cases()
        #expect(all.count == 7)
        for testCase in all {
            let decoded = try JSONDecoder().decode(NotificationData.self, from: testCase.input)
            #expect(notificationType(decoded) == testCase.expected, "\(testCase.name)")

            let reencoded = try JSONEncoder().encode(decoded)
            let original = try JSONSerialization.jsonObject(with: testCase.input) as? NSDictionary
            let roundTripped = try JSONSerialization.jsonObject(with: reencoded) as? NSDictionary
            #expect(original == roundTripped, "round trip: \(testCase.name)")
        }
    }

    @Test func keepsLargeAmountsAndOptionalUSD() throws {
        let all = try cases()
        let withUSD = try #require(all.first { $0.name == "lightning claim request with usd" })
        guard case .lightningClaimRequest(let claim) = try JSONDecoder().decode(NotificationData.self, from: withUSD.input)
        else {
            Issue.record("wrong variant")
            return
        }
        #expect(claim.amountSat == 2_100_000_000_000_000)
        #expect(claim.amountUsd == "12.34")

        let withoutUSD = try #require(all.first { $0.name == "lightning claim request without usd" })
        guard case .lightningClaimRequest(let bare) = try JSONDecoder().decode(NotificationData.self, from: withoutUSD.input)
        else {
            Issue.record("wrong variant")
            return
        }
        #expect(bare.amountUsd == nil)
    }
}
