import Foundation
@testable import Noah
import Testing

struct PushPayloadTests {
    private struct ServerPayload {
        let type: String
        let json: String
        let object: [String: Any]
    }

    /// Server-serialized NotificationData (apps/fixtures/server-notification-data.json).
    private func serverPayloads() throws -> [ServerPayload] {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appending(path: "fixtures/server-notification-data.json")
        let root = try #require(try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        return try #require(root["cases"] as? [[String: Any]]).map { entry in
            let object = try #require(entry["input"] as? [String: Any])
            let json = try #require(String(bytes: JSONSerialization.data(withJSONObject: object), encoding: .utf8))
            return try ServerPayload(type: #require(entry["expected"] as? String), json: json, object: object)
        }
    }

    private func type(of data: PushPayload.NotificationData) -> String {
        switch data {
        case .maintenance: "maintenance"
        case .lightningInvoiceRequest: "lightning_invoice_request"
        case .lightningClaimRequest: "lightning_claim_request"
        case .backupTrigger: "backup_trigger"
        case .heartbeat: "heartbeat"
        case .recurringPaymentDue: "recurring_payment_due"
        }
    }

    @Test func parsesEveryEnvelopeShape() throws {
        for payload in try serverPayloads() {
            let shapes: [[AnyHashable: Any]] = [
                ["aps": ["content-available": 1], "body": payload.json], // Expo APNs
                ["body": payload.object],
                ["dataString": payload.json],
                ["data": ["body": payload.json]],
                ["data": payload.object],
                ["data": ["UIApplicationLaunchOptionsRemoteNotificationKey": ["body": payload.json]]],
                ["UIApplicationLaunchOptionsRemoteNotificationKey": ["body": payload.json]],
                payload.object.reduce(into: [AnyHashable: Any]()) { $0[$1.key] = $1.value },
            ]
            for shape in shapes {
                let parsed = try #require(PushPayload.parse(shape), "\(payload.type) in \(shape.keys)")
                #expect(type(of: parsed) == payload.type)
            }
        }
    }

    @Test func ignoresUnknownOrBrokenPayloads() {
        #expect(PushPayload.parse(["body": #"{"notification_type":"something_new"}"#]) == nil)
        #expect(PushPayload.parse(["body": "not json"]) == nil)
        #expect(PushPayload.parse(["aps": ["alert": "hi"]]) == nil)
        #expect(PushPayload.parse(["body": #"{"notification_type":"heartbeat"}"#]) == nil, "missing fields")
    }
}

struct ExpoPushTokenTests {
    @Test func requestMatchesExpoNotifications() throws {
        let request = ExpoPushToken.request(
            installationID: "0C2A6F0E-1B8D-4C7A-9E55-3F1D2B4A6C8E",
            deviceToken: Data([0x0A, 0xFF, 0x01]), appID: "com.noahwallet.regtest", development: true)
        let json = try #require(
            try JSONSerialization.jsonObject(with: JSONEncoder().encode(request)) as? [String: Any])
        #expect(json["type"] as? String == "apns")
        #expect(json["deviceId"] as? String == "0c2a6f0e-1b8d-4c7a-9e55-3f1d2b4a6c8e")
        #expect(json["deviceToken"] as? String == "0aff01")
        #expect(json["appId"] as? String == "com.noahwallet.regtest")
        #expect(json["development"] as? Bool == true)
        #expect(json["projectId"] as? String == "6e79dffb-dcd4-4f3d-b596-638b16377eb0")
    }

    @Test func apnsEnvironmentFromProvisioningProfile() {
        func profile(_ environment: String?) -> Data {
            let entitlements = environment.map { "<key>aps-environment</key><string>\($0)</string>" } ?? ""
            let xml = """
            <?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict><key>Entitlements</key><dict>\(
                entitlements)</dict></dict></plist>
            """
            return Data([0x30, 0x82, 0x01]) + Data(xml.utf8) + Data([0x00, 0xA0]) // CMS bytes around it
        }
        #expect(ApnsEnvironment.isDevelopment(profile: profile("development")))
        #expect(!ApnsEnvironment.isDevelopment(profile: profile("production")))
        #expect(!ApnsEnvironment.isDevelopment(profile: profile(nil)))
        #expect(!ApnsEnvironment.isDevelopment(profile: nil), "no profile (App Store, simulator)")
    }
}
