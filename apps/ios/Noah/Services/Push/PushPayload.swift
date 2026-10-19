import Foundation

/// Finds Noah's `NotificationData` in a remote notification, accepting the
/// same shapes as `extractNotificationPayload` in pushNotifications.ts:
/// Expo's `body` (JSON string or object), `dataString`, nested `data`, the
/// launch-options wrapper, or the fields at the top level. Unknown types
/// are ignored.
enum PushPayload {
    typealias NotificationData = Components.Schemas.NotificationData

    static let knownTypes: Set<String> = [
        "maintenance", "lightning_invoice_request", "lightning_claim_request",
        "backup_trigger", "heartbeat", "recurring_payment_due",
    ]

    static func parse(_ userInfo: [AnyHashable: Any]) -> NotificationData? {
        guard let raw = extract(from: userInfo) else { return nil }
        let object: Any
        if let string = raw as? String {
            guard let parsed = try? JSONSerialization.jsonObject(with: Data(string.utf8)) else { return nil }
            object = parsed
        } else {
            object = raw
        }
        guard let record = object as? [String: Any],
            let type = record["notification_type"] as? String, knownTypes.contains(type),
            let data = try? JSONSerialization.data(withJSONObject: record)
        else { return nil }
        return try? JSONDecoder().decode(NotificationData.self, from: data)
    }

    private static func extract(from userInfo: [AnyHashable: Any]) -> Any? {
        var record: [String: Any] = [:]
        for (key, value) in userInfo {
            if let key = key as? String { record[key] = value }
        }
        if let type = record["notification_type"] as? String, knownTypes.contains(type) { return record }
        if let body = record["body"] { return body }
        if let dataString = record["dataString"] as? String { return dataString }
        if let nested = record["data"] as? [String: Any] {
            if let type = nested["notification_type"] as? String, knownTypes.contains(type) { return nested }
            if let body = nested["body"] { return body }
            if let dataString = nested["dataString"] as? String { return dataString }
            if let launch = nested["UIApplicationLaunchOptionsRemoteNotificationKey"] as? [String: Any],
                let body = launch["body"]
            {
                return body
            }
        }
        if let launch = record["UIApplicationLaunchOptionsRemoteNotificationKey"] as? [String: Any],
            let body = launch["body"]
        {
            return body
        }
        return nil
    }
}
