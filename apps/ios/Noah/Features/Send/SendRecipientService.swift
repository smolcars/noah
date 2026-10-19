import Foundation

struct SendRecipientRoute: Sendable {
    var minMsat: UInt64
    var maxMsat: UInt64
    var commentAllowed: Int
    var arkAddress: String?
}
enum SendRecipientService {
    static func resolve(_ destination: String, session: WalletSession = .shared) async throws -> SendRecipientRoute {
        var endpoint: URLComponents?
        if SendDestination.isLightningAddress(destination) {
            let parts = destination.lowercased().split(separator: "@")
            endpoint = URLComponents(string: "https://\(parts[1])/.well-known/lnurlp/\(parts[0])")
        } else if let decoded = PaymentEncoding.bech32(destination), decoded.prefix == "lnurl",
            let bytes = PaymentEncoding.bytes(decoded.words)
        {
            endpoint = URLComponents(string: String(decoding: bytes, as: UTF8.self))
        }
        guard endpoint?.scheme == "https", endpoint?.host != nil else { throw SendFailure.invalid }
        if let key = try? await session.sendArkServerKey() {
            let items = endpoint?.queryItems ?? []
            endpoint?.queryItems = items + [URLQueryItem(name: "ark", value: key)]
        }
        guard let url = endpoint?.url else { throw SendFailure.invalid }
        let (data, response) = try await URLSession.shared.data(for: URLRequest(url: url, timeoutInterval: 20))
        struct Response: Decodable {
            let tag: String
            let callback: String
            let minSendable: UInt64
            let maxSendable: UInt64
            let commentAllowed: Int?
            let ark: String?
        }
        guard (response as? HTTPURLResponse)?.statusCode == 200, data.count <= 65536 else { throw SendFailure.invalid }
        let decoded = try JSONDecoder().decode(Response.self, from: data)
        guard decoded.tag == "payRequest", let callback = URL(string: decoded.callback), callback.scheme == "https",
            decoded.minSendable <= decoded.maxSendable
        else { throw SendFailure.invalid }
        var ark: String?
        if let address = decoded.ark, (try? await session.validateSendArkAddress(address)) == true { ark = address }
        return SendRecipientRoute(
            minMsat: decoded.minSendable, maxMsat: decoded.maxSendable,
            commentAllowed: max(0, decoded.commentAllowed ?? 0), arkAddress: ark)
    }
    static func suggestionQuery(_ destination: String, domain: String) -> String? {
        let value = SendDestination.cleaned(destination).lowercased()
        let parts = value.split(separator: "@", omittingEmptySubsequences: false)
        guard (1...2).contains(parts.count), let username = parts.first, username.count >= 2,
            username.range(of: "^[a-z0-9_.-]+$", options: .regularExpression) != nil
        else { return nil }
        if parts.count == 1 {
            guard username.count <= 32, username.first?.isLetter == true,
                !["bc1", "tb1", "bcrt1", "lnbc", "lntb", "lnbcrt", "ark", "tark", "lno"].contains(
                    where: username.hasPrefix)
            else { return nil }
        } else if !domain.hasPrefix(parts[1]) {
            return nil
        }
        return value
    }
}
