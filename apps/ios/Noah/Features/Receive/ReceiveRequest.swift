import Foundation

struct ReceiveRequest: Equatable, Sendable {
    let arkAddress: String
    let onchainAddress: String
    var amountSat: Int64?
    var description = ""
    var lightningInvoice: String?
    var paymentHash: String?

    enum ValidationError: Error { case invalidAmount, descriptionTooLong }

    static func validate(amountSat: Int64, description: String = "") throws {
        guard amountSat > 0, amountSat <= 9_007_199_254_740_991 else { throw ValidationError.invalidAmount }
        guard description.trimmingCharacters(in: .whitespacesAndNewlines).utf16.count <= 639 else {
            throw ValidationError.descriptionTooLong
        }
    }

    /// The Payment request (BIP-321): the on-chain address, your Lightning address as an LNURL-pay code in
    /// `lightning` (LUD-01's fallback key; Noah is Lightning address first) and the Ark address. It never carries
    /// an amount or an invoice: those belong to the Lightning invoice format.
    func uri(lightningAddress: String?) -> String {
        var parameters: [String] = []
        if let lnurl = lightningAddress.flatMap(LightningIdentity.lnurl(address:)) { parameters.append("lightning=\(Self.encode(lnurl))") }
        parameters.append("ark=\(Self.encode(arkAddress))")
        return "bitcoin:\(onchainAddress)?" + parameters.joined(separator: "&")
    }

    private static func encode(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: CharacterSet(charactersIn:
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()")) ?? value
    }
}
