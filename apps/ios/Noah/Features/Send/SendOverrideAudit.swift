import Foundation

struct SendOverrideAudit: Codable, Sendable {
    static let titleText = "You could pay twice"
    static let confirmationText = "Start a new payment anyway"
    static let warningText = "Forgetting this attempt does not cancel Bark’s stored transaction or its future broadcast retries. This payment may still complete. Check your full wallet history and confirm with the recipient first. Sending again may spend twice and cannot undo the first payment."
    var overriddenAt = Date()
    var txid: String?
    var walletIdentity: SendWalletIdentity?
    var title = Self.titleText
    var warning: String
    var confirmation = Self.confirmationText
    var statusMessage: String?

    static func append(_ audit: Self, to file: URL) throws {
        var audits = FileManager.default.fileExists(atPath: file.path)
            ? try JSONDecoder().decode([Self].self, from: Data(contentsOf: file)) : []
        audits.append(audit)
        try JSONEncoder().encode(Array(audits.suffix(100))).write(
            to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        var excluded = file
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try excluded.setResourceValues(values)
    }
}
