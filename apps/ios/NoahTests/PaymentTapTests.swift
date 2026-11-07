import Foundation
@testable import Noah
import Testing
import UserNotifications

/// apps/fixtures/notification-tap.json: which payment a "Payment received" notification opens.
struct PaymentTapTests {
    struct Row: Decodable {
        let id: String
        let paymentHash: String?
        let outputVtxos: [String]
    }

    struct Input: Decodable {
        let data: String
        let rows: [Row]
    }

    struct Expected: Decodable {
        let tap: Bool
        let opens: String?
    }

    @Test func sharedCases() throws {
        let cases = try Fixtures.load("notification-tap", input: Input.self, expected: Expected.self)
        #expect(!cases.isEmpty)
        for fixture in cases {
            let tap = PaymentTap.of(data: fixture.input.data)
            #expect((tap != nil) == fixture.expected.tap, "\(fixture.name)")
            let opens = fixture.input.rows.first { tap?.matches(paymentHash: $0.paymentHash, outputVtxos: $0.outputVtxos) == true }?.id
            #expect(opens == fixture.expected.opens, "\(fixture.name)")
        }
    }

    /// APNs puts the push's data in `body` as a JSON string, Expo as an object; the app's own Lightning notification
    /// puts the fields at the top level.
    @Test func everyNotificationShapeNamesItsPayment() {
        let ark = #"{"notification_type":"ark_payment_received","vtxo_ids":["aa:0"]}"#
        let expected = PaymentTap(vtxoIDs: ["aa:0"])
        #expect(PaymentTap.of(userInfo: ["aps": ["alert": ["title": "Payment received"]], "body": ark]) == expected)
        #expect(PaymentTap.of(userInfo: ["body": ["notification_type": "ark_payment_received", "vtxo_ids": ["aa:0"]]]) == expected)
        #expect(PaymentTap.of(userInfo: ["aps": [:], "body": "{}"]) == nil)
        let content = PushJobs.receivedContent(sats: 1, usd: nil, unit: .sats, paymentHash: "0f0f")
        #expect(PaymentTap.of(userInfo: content.userInfo) == PaymentTap(paymentHash: "0f0f"))
        // A recurring notification goes to its schedules, never to a payment.
        #expect(PaymentTap.of(userInfo: ["notification_type": "recurring_payment_result"]) == nil)
    }
}
