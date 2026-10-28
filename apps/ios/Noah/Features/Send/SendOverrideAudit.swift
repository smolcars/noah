import Foundation

struct SendOverrideAudit: Codable, Sendable {
    static let titleText = "You could pay twice"
    static let confirmationText = "Start a new payment anyway"
    // swiftlint:disable:next line_length - one literal: translations.mjs reads copy as a single string
    static let warningText = "Forgetting this attempt does not cancel Bark’s stored transaction or its future broadcast retries. This payment may still complete. Check your full wallet history and confirm with the recipient first. Sending again may spend twice and cannot undo the first payment."
    var overriddenAt = Date()
    var txid: String?
    var walletIdentity: SendWalletIdentity?
    // swiftlint:disable discouraged_optional_boolean discouraged_optional_collection - persisted audits written before these fields lack them
    var ownershipUnverified: Bool?
    var candidates: [SendCandidate]?
    var possibleTxids: [String]?
    // swiftlint:enable discouraged_optional_boolean discouraged_optional_collection
    var title = Self.titleText
    var warning: String
    var confirmation = Self.confirmationText
    var statusMessage: String?

    static func append(_ audit: Self, to file: URL) throws {
        var audits: [Self] = []
        if FileManager.default.fileExists(atPath: file.path) {
            do {
                audits = try JSONDecoder().decode([Self].self, from: Data(contentsOf: file))
            } catch {
                var quarantine = file.appendingPathExtension("unreadable-\(UUID().uuidString)")
                try FileManager.default.moveItem(at: file, to: quarantine)
                var values = URLResourceValues()
                values.isExcludedFromBackup = true
                try quarantine.setResourceValues(values)
            }
        }
        audits.append(audit)
        try JSONEncoder().encode(Array(audits.suffix(100))).write(
            to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        var excluded = file
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try excluded.setResourceValues(values)
    }
}
