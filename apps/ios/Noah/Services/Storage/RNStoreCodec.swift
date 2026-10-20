import CoreFoundation
import Foundation

/// RN's eight persisted stores. Unknown fields stay as raw JSON tokens, so
/// an unrelated settings change cannot round numbers or erase newer data.
enum RNStoreCodec {
    enum Failure: Error { case invalid, unsupportedVersion }
    enum Kind { case bool, string, number }
    struct Schema {
        var version = 0
        var fields: [String: Kind]
    }
    static var storeKeys: [String] { Array(schemas.keys) + [RecurringStore.key] }
    static let schemas: [String: Schema] = [
        "wallet-storage": .init(fields: ["isInitialized": .bool, "isWalletLoaded": .bool,
            "staticVtxoPubkey": .string, "isBiometricsEnabled": .bool, "isDebugModeEnabled": .bool,
            "isWalletSuspended": .bool, "hasSeenBatteryOptimizationPrompt": .bool]),
        "profile-storage": .init(fields: ["displayName": .string, "preferredCurrency": .string, "bitcoinAmountUnit": .string]),
        "privacy-storage": .init(fields: ["isHomeBalanceHidden": .bool]),
        "transaction-storage": .init(version: 1, fields: ["isAutoBoardingEnabled": .bool, "hasAttemptedAutoBoarding": .bool]),
        "server-storage": .init(fields: ["isRegisteredWithServer": .bool, "lightningAddress": .string,
            "nostrPubkey": .string, "isBackupEnabled": .bool, "emailAddress": .string,
            "isEmailVerified": .bool, "isEmailPromptDismissed": .bool, "mailboxAuthorizationExpiry": .number,
            "isMailboxAuthorizationEnabled": .bool, "lastReportedDeviceFingerprint": .string, "lastDeviceReportAt": .number]),
        "backup-storage": .init(fields: ["backupPending": .bool, "lastBackupAt": .number,
            "lastBackupAttemptAt": .number, "lastBackupStatus": .string, "lastBackupError": .string,
            "lastBackupId": .string, "lastUploadedSnapshotSha256": .string]),
        "esplora-storage": .init(fields: ["endpointOverride": .string]),
    ]

    static func decode(_ raw: String, key: String) throws -> RawObject {
        guard let schema = schemas[key] else { throw Failure.invalid }
        var envelope = try RawObject(raw)
        let version: Int
        if let token = envelope.fields["version"] {
            let exact = token.trimmingCharacters(in: .whitespacesAndNewlines)
            guard exact == "0" || (schema.version == 1 && exact == "1") else { throw Failure.unsupportedVersion }
            version = exact == "1" ? 1 : 0
        } else { version = 0 }
        guard version <= schema.version else { throw Failure.unsupportedVersion }
        guard let rawState = envelope.fields["state"] else { throw Failure.invalid }
        var state = try RawObject(rawState)
        let values = try JSONSerialization.jsonObject(with: Data(rawState.utf8)) as! [String: Any]
        for (field, kind) in schema.fields {
            guard let value = values[field] else { continue }
            if value is NSNull {
                if case .bool = kind { throw Failure.invalid }
                if field == "lastBackupStatus" { throw Failure.invalid }
                continue
            }
            switch kind {
            case .bool:
                guard let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else { throw Failure.invalid }
            case .string:
                guard value is String else { throw Failure.invalid }
            case .number:
                guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(), number.doubleValue.isFinite else { throw Failure.invalid }
            }
        }
        if key == "backup-storage", let status = values["lastBackupStatus"] as? String,
           PersistedBackupState.Status(rawValue: status) == nil { throw Failure.invalid }
        if key == "transaction-storage", version < 1 { state.fields["hasAttemptedAutoBoarding"] = "false" }
        envelope.fields["state"] = try state.json()
        envelope.fields["version"] = String(schema.version)
        return envelope
    }

    static func merging(_ encoded: String, into previous: String?, key: String) throws -> String {
        let incoming = try decode(encoded, key: key)
        guard let previous else { return try incoming.json() }
        var envelope = try decode(previous, key: key)
        var state = try RawObject(envelope.fields["state"]!)
        let newState = try RawObject(incoming.fields["state"]!)
        for field in schemas[key]!.fields.keys { state.fields.removeValue(forKey: field) }
        state.fields.merge(newState.fields) { _, new in new }
        envelope.fields["state"] = try state.json()
        return try envelope.json()
    }
}

/// No iOS endpoint editor yet. Decode for parity, but do not activate an
/// endpoint override until its native validation/reload flow is implemented.
struct PersistedEsploraState: Codable, Equatable, Sendable {
    static let key = "esplora-storage"
    static let version = 0
    var endpointOverride: String?
}
