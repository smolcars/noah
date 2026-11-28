import Foundation

struct SendRecipientRoute: Sendable {
    var destination: String
    var minMsat: UInt64
    var maxMsat: UInt64
    var commentAllowed: Int
    var arkAddress: String?
    var callback: URL?
    var metadata: String?
    var payerData: LnurlPayerData.Requirements = [:]
    /// The `bark` payment option, when the service offers one (lnurl/luds#303).
    var arkOption: SendArkOption?

    /// Whether Ark can pay this address at all, whatever the amount.
    var offersArk: Bool { arkOption != nil || arkAddress != nil }

    /// How Ark pays `sats` here (0: any amount): through the payment option while the amount fits it, else to the address
    /// discovery returned. Nil when neither can.
    func arkRail(sats: UInt64) -> SendArkRail? {
        if let option = arkOption {
            let (msat, overflow) = sats.multipliedReportingOverflow(by: 1000)
            if sats == 0 || (!overflow && msat >= option.minMsat && msat <= option.maxMsat) { return .option(option.id) }
        }
        return arkAddress.map(SendArkRail.address)
    }
}

/// How a Lightning address is paid over Ark.
enum SendArkRail: Equatable, Sendable {
    /// Through a `bark` payment option: its callback returns the address.
    case option(String)
    /// To the address discovery returned for `ark=`, Noah's older contract.
    case address(String)
}

/// The `bark` payment option Noah pays through (lnurl/luds#303, `apps/fixtures/lnurl-pay-options.json`), with the amount range
/// it allows.
struct SendArkOption: Equatable, Sendable {
    var id: String
    var minMsat: UInt64
    var maxMsat: UInt64

    /// A pay request's `paymentOptions`; anything but an array counts as none.
    struct Advertised: Decodable, Sendable {
        let entries: [SendPaymentOptionEntry]

        init(from decoder: any Decoder) {
            entries = (try? decoder.singleValueContainer().decode([SendPaymentOptionEntry].self)) ?? []
        }
    }

    /// The first usable `bark` entry, its own range narrowing the pay request's; nil when there is none. An entry naming an
    /// `operator` (Noah's extension, proposed on lnurl/luds#303) must name `serverKey`, the wallet's own Ark server.
    static func first(_ advertised: Advertised?, minMsat: UInt64, maxMsat: UInt64, serverKey: String? = nil) -> Self? {
        for entry in advertised?.entries ?? [] where entry.usable && entry.type == "bark" {
            guard let id = entry.id, !id.isEmpty else { continue }
            if let operatorKey = entry.operatorKey, operatorKey.lowercased() != serverKey?.lowercased() { continue }
            let low = max(minMsat, entry.minMsat ?? minMsat)
            let high = min(maxMsat, entry.maxMsat ?? maxMsat)
            guard low <= high else { continue }
            return .init(id: id, minMsat: low, maxMsat: high)
        }
        return nil
    }
}

/// One `paymentOptions` entry; `usable` is false when it isn't an object or a field it has is malformed.
struct SendPaymentOptionEntry: Decodable, Sendable {
    var id: String?
    var type: String?
    var minMsat: UInt64?
    var maxMsat: UInt64?
    /// The Ark server's public key the entry names, if any.
    var operatorKey: String?
    var usable = true

    init(from decoder: any Decoder) {
        guard let container = try? decoder.container(keyedBy: CodingKeys.self) else {
            usable = false
            return
        }
        id = try? container.decode(String.self, forKey: .id)
        type = try? container.decode(String.self, forKey: .type)
        if container.contains(.operatorKey) {
            operatorKey = try? container.decode(String.self, forKey: .operatorKey)
            if operatorKey == nil { usable = false }
        }
        if container.contains(.available), (try? container.decode(Bool.self, forKey: .available)) != true { usable = false }
        if container.contains(.minSendable) {
            minMsat = try? container.decode(UInt64.self, forKey: .minSendable)
            if minMsat == nil { usable = false }
        }
        if container.contains(.maxSendable) {
            maxMsat = try? container.decode(UInt64.self, forKey: .maxSendable)
            if maxMsat == nil { usable = false }
        }
    }

    private enum CodingKeys: String, CodingKey {
        case id, type, available, minSendable, maxSendable
        case operatorKey = "operator"
    }
}

/// The identity Noah can send as LUD-18 payer data: the profile's display name and the wallet's own Lightning address.
struct LnurlPayerIdentity: Equatable, Sendable {
    var name: String?
    var identifier: String?

    static func stored(_ storage: SettingsStorage = .shared) -> Self {
        .init(name: storage.persistedState(PersistedProfileState.self, key: PersistedProfileState.key)?.displayName,
              identifier: storage.persistedState(PersistedServerState.self, key: PersistedServerState.key)?.lightningAddress)
    }

    /// The saved values Noah can send, trimmed; an empty one counts as unset.
    var fields: [String: String] {
        ["name": name, "identifier": identifier].compactMapValues { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.value.isEmpty }
    }
}

/// LUD-18 payer data (`apps/fixtures/lnurl-pay-payer-data.json`). Noah sends only the fields a pay request asks for.
enum LnurlPayerData {
    struct Field: Equatable, Sendable { let mandatory: Bool }
    /// Each field a pay request asks for. Empty when it asks for none.
    typealias Requirements = [String: Field]
    /// A pay request's `payerData`, read leniently: a malformed object or field counts as not asked for.
    struct Advertised: Decodable, Sendable {
        let requirements: Requirements

        init(from decoder: any Decoder) {
            requirements = ((try? decoder.singleValueContainer().decode([String: AdvertisedPayerDataField].self)) ?? [:]).compactMapValues(\.field)
        }
    }
    /// What To's notice says Noah shares.
    enum Share: Equatable, Sendable {
        case nameOnly, addressOnly, nameAndAddress

        init?(_ shared: [String: String]) {
            switch (shared["name"] != nil, shared["identifier"] != nil) {
            case (true, true): self = .nameAndAddress
            case (true, false): self = .nameOnly
            case (false, true): self = .addressOnly
            case (false, false): return nil
            }
        }
    }

    static let supported: Set = ["name", "identifier"]

    /// The fields to send: those asked for among `available` (the saved identity on To, what To showed at the quote).
    /// Throws when a field Noah doesn't send is mandatory, then when a mandatory field Noah sends isn't available.
    static func build(_ requirements: Requirements, available: [String: String]) throws -> [String: String] {
        if requirements.contains(where: { !supported.contains($0.key) && $0.value.mandatory }) { throw SendFailure.payerDataUnsupported }
        if requirements.contains(where: { supported.contains($0.key) && $0.value.mandatory && available[$0.key] == nil }) {
            throw SendFailure.payerDataMissing
        }
        return available.filter { supported.contains($0.key) && requirements[$0.key] != nil }
    }

    static func json(_ shared: [String: String]) throws -> String {
        try String(json: JSONSerialization.data(withJSONObject: shared, options: [.sortedKeys, .withoutEscapingSlashes]))
    }
}

/// One advertised `payerData` field; `field` is nil when it's malformed (not an object, or `mandatory` isn't a boolean).
private struct AdvertisedPayerDataField: Decodable {
    let field: LnurlPayerData.Field?

    init(from decoder: any Decoder) {
        field = (try? decoder.container(keyedBy: CodingKeys.self).decode(Bool.self, forKey: .mandatory)).map(LnurlPayerData.Field.init(mandatory:))
    }

    private enum CodingKeys: String, CodingKey { case mandatory }
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
        func urlSession(_: URLSession, task _: URLSessionTask, willPerformHTTPRedirection _: HTTPURLResponse,
                        // swiftlint:disable:next async_without_await - URLSession's async delegate requirement; a sync one is never called
                        newRequest _: URLRequest) async
            -> URLRequest? { nil }
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
                  let bytes = PaymentEncoding.bytes(decoded.words) {
            endpoint = String(bytes: bytes, encoding: .utf8).flatMap(SendDestination.urlText).flatMap(URLComponents.init(string:))
            // As Android's HttpUrl requests it: dot segments resolved.
            if let path = endpoint?.percentEncodedPath { endpoint?.percentEncodedPath = SendDestination.removingDotSegments(path) }
        }
        guard endpoint.map(SendDestination.allowsLnurl) == true else { throw SendFailure.invalid }
        let serverKey = try? await session.sendArkServerKey()
        if let key = serverKey {
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
            let payerData: LnurlPayerData.Advertised?
            let paymentOptions: SendArkOption.Advertised?
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
            callback: callback, metadata: decoded.metadata, payerData: decoded.payerData?.requirements ?? [:],
            arkOption: SendArkOption.first(decoded.paymentOptions, minMsat: decoded.minSendable, maxMsat: decoded.maxSendable,
                                           serverKey: serverKey))
    }
    /// The Ark address to pay: discovery's own, or the one a payment option's callback returns, checked as discovery's is.
    /// Sends no comment or payer data: an Ark payment can't carry them to the recipient yet.
    static func arkAddress(_ rail: SendArkRail, route: SendRecipientRoute, sats: UInt64, session: WalletSession = .shared,
                           fetch: Fetch = fetch) async throws -> String {
        let option: String
        switch rail {
        case .address(let address): return address
        case .option(let id): option = id
        }
        let (msat, overflow) = sats.multipliedReportingOverflow(by: 1000)
        guard !overflow, let callback = route.callback else { throw SendFailure.invalid }
        let url = try callbackURL(callback, msat: msat, comment: "", payerData: [:], paymentOption: option)
        let (data, response) = try await fetch(URLRequest(url: url, timeoutInterval: 20))
        struct Response: Decodable { let paymentDestination: String }
        guard (response as? HTTPURLResponse)?.statusCode == 200, data.count <= 65536 else { throw SendFailure.invalid }
        let address = try JSONDecoder().decode(Response.self, from: data).paymentDestination
        guard (try? await session.validateSendArkAddress(address)) == true else { throw SendFailure.invalid }
        return address
    }
    /// Resolve without paying: Bark's payLnurl/payLightningAddress can resume an old checkpoint. Sends only the payer data
    /// the payment carries (what To showed), as far as this lookup still asks for it.
    static func invoice(_ payment: SendPayment, session: WalletSession = .shared, fetch: Fetch = fetch) async throws -> String {
        let route = try await resolve(payment.destination, session: session, fetch: fetch)
        let (msat, overflow) = payment.amount.multipliedReportingOverflow(by: 1000)
        guard !overflow, msat >= route.minMsat, msat <= route.maxMsat, let callback = route.callback else { throw SendFailure.invalid }
        let url = try callbackURL(callback, msat: msat, comment: payment.comment,
                                  payerData: LnurlPayerData.build(route.payerData, available: payment.payerData ?? [:]))
        let (data, response) = try await fetch(URLRequest(url: url, timeoutInterval: 20))
        struct Response: Decodable { let pr: String }
        guard (response as? HTTPURLResponse)?.statusCode == 200, data.count <= 65536 else { throw SendFailure.invalid }
        let invoice = try JSONDecoder().decode(Response.self, from: data).pr
        try validateInvoice(invoice, amount: payment.amount)
        return invoice
    }
    /// The callback with this payment's `amount`, its note as `comment`, LUD-18 `payerdata` and its `paymentOption`
    /// (lnurl/luds#303), each only when there is one (`apps/fixtures/lnurl-pay-callback.json`). The service's own query stays
    /// as it was. The provider enforces commentAllowed.
    static func callbackURL(_ callback: URL, msat: UInt64, comment: String, payerData: [String: String],
                            paymentOption: String? = nil) throws -> URL {
        guard var components = URLComponents(url: callback, resolvingAgainstBaseURL: false) else { throw SendFailure.invalid }
        var items = (components.percentEncodedQueryItems ?? []).filter {
            !["amount", "comment", "payerdata", "paymentOption"].contains($0.name)
        }
        items.append(URLQueryItem(name: "amount", value: String(msat)))
        if !comment.isEmpty { items.append(URLQueryItem(name: "comment", value: queryValue(comment))) }
        if !payerData.isEmpty { try items.append(URLQueryItem(name: "payerdata", value: queryValue(LnurlPayerData.json(payerData)))) }
        if let paymentOption { items.append(URLQueryItem(name: "paymentOption", value: queryValue(paymentOption))) }
        components.percentEncodedQueryItems = items
        guard let url = components.url else { throw SendFailure.invalid }
        return url
    }
    /// Everything but RFC 3986's unreserved characters percent-encoded. `URLComponents.queryItems` leaves `+` as is, which a
    /// form decoder reads as a space.
    private static func queryValue(_ value: String) -> String { value.addingPercentEncoding(withAllowedCharacters: unreserved) ?? "" }
    private static let unreserved = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
    /// LNURL-pay no longer binds the invoice's description hash to the metadata (lnurl/luds#234), so it isn't checked.
    static func validateInvoice(_ value: String, amount: UInt64) throws {
        let parsed = SendDestination.parse(value)
        guard parsed.destinationType == "lightning", parsed.error == nil,
              let invoice = PaymentEncoding.invoice(value), invoice.sats == Decimal(amount)
        else { throw SendFailure.invalid }
        guard invoice.expires > Date() else { throw SendFailure.expired }
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
