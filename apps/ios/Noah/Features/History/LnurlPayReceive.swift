import Foundation

/// Who paid an LNURL-pay receive and what they wrote, as the payer sent it (unverified): the movement's
/// `noah.lnurl_pay` metadata (docs/lnurl_pay.md, apps/fixtures/lnurl-pay-receive-metadata.json). Each value is
/// shown only when it is safe.
struct LnurlPayReceive: Codable, Hashable, Sendable {
    static let schemaVersion = 1

    var name: String?
    var identifier: String?
    var comment: String?

    /// `Name (⚡ identifier)`, or whichever is known; nil when the payer named nobody.
    var payerLabel: String? {
        if let name, let identifier { return "\(name) (⚡ \(identifier))" }
        return name ?? identifier
    }

    /// From the server's record or a push, with each value checked again.
    static func checked(name: String?, identifier: String?, comment: String?) -> Self {
        .init(name: safe(name, max: 80), identifier: safe(identifier, max: 320), comment: safe(comment, max: 280))
    }

    /// The JSON merge patch that stores this on a movement, next to bark's and Noah's other keys.
    func patch() throws -> String {
        var payer: [String: String] = [:]
        payer["name"] = name
        payer["identifier"] = identifier
        var entry: [String: Any] = ["schema_version": Self.schemaVersion, "payer_data": payer]
        if let comment { entry["comment"] = comment }
        let root = ["noah": ["lnurl_pay": entry]]
        return try String(json: JSONSerialization.data(withJSONObject: root, options: [.sortedKeys, .withoutEscapingSlashes]))
    }

    /// The stored entry in a movement's metadata, or nil when there is none of this version.
    static func parse(_ metadataJSON: String?) -> Self? {
        guard let metadataJSON,
              let root = try? JSONSerialization.jsonObject(with: Data(metadataJSON.utf8)) as? [String: Any],
              let entry = (root["noah"] as? [String: Any])?["lnurl_pay"] as? [String: Any],
              (entry["schema_version"] as? Int) == schemaVersion
        else { return nil }
        let payer = entry["payer_data"] as? [String: Any]
        return checked(name: payer?["name"] as? String, identifier: payer?["identifier"] as? String,
                       comment: entry["comment"] as? String)
    }

    /// Trimmed; nil when empty, longer than `max` characters, or holding control or bidirectional controls.
    private static func safe(_ value: String?, max: Int) -> String? {
        guard let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty,
              trimmed.unicodeScalars.count <= max, !trimmed.unicodeScalars.contains(where: unsafe)
        else { return nil }
        return trimmed
    }

    private static func unsafe(_ scalar: Unicode.Scalar) -> Bool {
        let value = scalar.value
        return value <= 0x1F || (0x7F...0x9F).contains(value) || value == 0x61C || (0x200E...0x200F).contains(value)
            || (0x2028...0x202E).contains(value) || (0x2066...0x2069).contains(value)
    }
}
