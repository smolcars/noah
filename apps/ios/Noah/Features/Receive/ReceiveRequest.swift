import Foundation

struct ReceiveRequest: Equatable, Sendable {
    let arkAddress: String
    let onchainAddress: String
    var amountSat: Int64?
    var description = ""
    var lightningInvoice: String?
    var paymentHash: String?

    enum ValidationError: Error { case invalidAmount, missingInvoice, descriptionTooLong }

    static func validate(amountSat: Int64, description: String = "") throws {
        guard amountSat > 0, amountSat <= 9_007_199_254_740_991 else { throw ValidationError.invalidAmount }
        guard description.trimmingCharacters(in: .whitespacesAndNewlines).utf16.count <= 639 else {
            throw ValidationError.descriptionTooLong
        }
    }

    func uri() throws -> String {
        var parameters: [String] = []
        if let amountSat {
            try Self.validate(amountSat: amountSat)
            guard let lightningInvoice else { throw ValidationError.missingInvoice }
            let whole = amountSat / 100_000_000
            let fraction = String(format: "%08lld", amountSat % 100_000_000)
                .replacingOccurrences(of: "0+$", with: "", options: .regularExpression)
            parameters.append("amount=\(whole)\(fraction.isEmpty ? "" : "." + fraction)")
            parameters.append("lightning=\(Self.encode(lightningInvoice))")
        }
        parameters.append("ark=\(Self.encode(arkAddress))")
        return "bitcoin:\(onchainAddress)?" + parameters.joined(separator: "&")
    }

    private static func encode(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: CharacterSet(charactersIn:
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()")) ?? value
    }
}
