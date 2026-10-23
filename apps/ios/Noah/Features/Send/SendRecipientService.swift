import CryptoKit
import Foundation

struct SendRecipientRoute: Sendable {
    var destination: String
    var minMsat: UInt64
    var maxMsat: UInt64
    var commentAllowed: Int
    var arkAddress: String?
    var callback: URL?
    var metadata: String?
}
enum SendRecipientService {
    typealias Fetch = @Sendable (URLRequest) async throws -> (Data, URLResponse)
    /// LNURL's HTTP client, as Android's: no redirects (a 3xx fails the lookup), nothing cached, 20 s in total, and at most
    /// 64 KiB read, refused from `Content-Length` before the body or as soon as the stream passes it.
    static func fetch(_ request: URLRequest) async throws -> (Data, URLResponse) {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForResource = 20
        let session = URLSession(configuration: configuration, delegate: RefuseRedirects(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let (bytes, response) = try await session.bytes(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200, response.expectedContentLength <= 65536 else { throw SendFailure.invalid }
        var data = Data()
        for try await byte in bytes {
            guard data.count < 65536 else { throw SendFailure.invalid }
            data.append(byte)
        }
        return (data, response)
    }
    private final class RefuseRedirects: NSObject, URLSessionTaskDelegate, Sendable {
        func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest) async -> URLRequest? { nil }
    }
    static func resolve(_ destination: String, session: WalletSession = .shared,
                        fetch: Fetch = fetch) async throws -> SendRecipientRoute {
        let value = SendDestination.cleaned(destination).lowercased()
        var endpoint: URLComponents?
        if SendDestination.isLightningAddress(value), let at = value.unicodeScalars.lastIndex(of: "@") {
            // The name is one path segment, whatever it contains; a domain that isn't a host fails here.
            if let host = SendDestination.host(String(value.unicodeScalars[value.unicodeScalars.index(after: at)...])),
               let name = String(value.unicodeScalars[..<at]).addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(["/"])) {
                endpoint = URLComponents(string: "https://\(host)/.well-known/lnurlp/\(name)")
            }
        } else if let decoded = PaymentEncoding.bech32(value), decoded.prefix == "lnurl",
            let bytes = PaymentEncoding.bytes(decoded.words)
        {
            endpoint = SendDestination.urlText(String(decoding: bytes, as: UTF8.self)).flatMap(URLComponents.init(string:))
            // As Android's HttpUrl requests it: dot segments resolved.
            if let path = endpoint?.percentEncodedPath { endpoint?.percentEncodedPath = SendDestination.removingDotSegments(path) }
        }
        guard endpoint.map(SendDestination.allowsLnurl) == true else { throw SendFailure.invalid }
        if let key = try? await session.sendArkServerKey() {
            let items = endpoint?.queryItems ?? []
            endpoint?.queryItems = items + [URLQueryItem(name: "ark", value: key)]
        }
        guard let url = endpoint?.url else { throw SendFailure.invalid }
        let (data, response) = try await fetch(URLRequest(url: url, timeoutInterval: 20))
        struct Response: Decodable {
            let tag: String
            let callback: String
            let minSendable: UInt64
            let maxSendable: UInt64
            let commentAllowed: Int?
            let ark: String?
            let metadata: String?
        }
        guard (response as? HTTPURLResponse)?.statusCode == 200, data.count <= 65536 else { throw SendFailure.invalid }
        let decoded = try JSONDecoder().decode(Response.self, from: data)
        guard decoded.tag == "payRequest", let callback = URL(string: decoded.callback),
            URLComponents(url: callback, resolvingAgainstBaseURL: false).map(SendDestination.allowsLnurl) == true,
            decoded.minSendable <= decoded.maxSendable
        else { throw SendFailure.invalid }
        var ark: String?
        if let address = decoded.ark, (try? await session.validateSendArkAddress(address)) == true { ark = address }
        return SendRecipientRoute(
            destination: value, minMsat: decoded.minSendable, maxMsat: decoded.maxSendable,
            commentAllowed: max(0, decoded.commentAllowed ?? 0), arkAddress: ark,
            callback: callback, metadata: decoded.metadata)
    }
    /// Resolve without paying: Bark's payLnurl/payLightningAddress can resume an old checkpoint.
    static func invoice(_ payment: SendPayment, session: WalletSession = .shared,
                        fetch: Fetch = fetch) async throws -> String {
        let route = try await resolve(payment.destination, session: session, fetch: fetch)
        let (msat, overflow) = payment.amount.multipliedReportingOverflow(by: 1000)
        guard !overflow, msat >= route.minMsat, msat <= route.maxMsat,
              let callback = route.callback,
              var components = URLComponents(url: callback, resolvingAgainstBaseURL: false)
        else { throw SendFailure.invalid }
        var items = (components.queryItems ?? []).filter { !["amount", "comment"].contains($0.name) }
        items.append(URLQueryItem(name: "amount", value: String(msat)))
        // Like RN/Bark, forward the comment; the provider enforces commentAllowed.
        items.append(URLQueryItem(name: "comment", value: payment.comment))
        components.queryItems = items
        guard let url = components.url else { throw SendFailure.invalid }
        let (data, response) = try await fetch(URLRequest(url: url, timeoutInterval: 20))
        struct Response: Decodable { let pr: String }
        guard (response as? HTTPURLResponse)?.statusCode == 200, data.count <= 65536 else { throw SendFailure.invalid }
        let invoice = try JSONDecoder().decode(Response.self, from: data).pr
        try validateInvoice(invoice, amount: payment.amount, metadata: route.metadata)
        return invoice
    }
    static func validateInvoice(_ value: String, amount: UInt64, metadata: String?) throws {
        let parsed = SendDestination.parse(value)
        guard parsed.destinationType == "lightning", parsed.error == nil,
              let invoice = PaymentEncoding.invoice(value), invoice.sats == Decimal(amount)
        else { throw SendFailure.invalid }
        guard invoice.expires > Date() else { throw SendFailure.expired }
        // Pinned Bark treats metadata binding as a warning (LUD-06); keep RN parity.
        if let metadata, invoice.descriptionHash != SHA256.hash(data: Data(metadata.utf8)).map({ String(format: "%02x", $0) }).joined() {
            AppLog("send").w("LNURL invoice description hash does not commit to endpoint metadata")
        }
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
