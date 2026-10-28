import Foundation
@testable import Noah
import Testing

/// Native-only vectors for what Bark accepts and RN refused; the RN-generated send-utils vectors still run in SendFixtureTests.
struct SendDestinationNativeTests {
    struct Input: Decodable { let destination: String; let network: String }
    enum ExpectedKeys: CodingKey { case valid, destinationType, amount, isAmountEditable, error, bip321, url }
    struct Expected: Decodable {
        let valid: Bool
        let destinationType: String?
        let amount: Decimal?
        let isAmountEditable: Bool
        let error: String?
        let bip321: [String: String]? // swiftlint:disable:this discouraged_optional_collection - absent and empty differ in the vectors
        /// Only for `lnurl` results: the first URL resolution requests, or nil when it refuses before any request.
        let url: String??
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: ExpectedKeys.self)
            valid = try c.decode(Bool.self, forKey: .valid)
            destinationType = try c.decodeIfPresent(String.self, forKey: .destinationType)
            amount = try c.decodeIfPresent(Decimal.self, forKey: .amount)
            isAmountEditable = try c.decode(Bool.self, forKey: .isAmountEditable)
            error = try c.decodeIfPresent(String.self, forKey: .error)
            bip321 = try c.decodeIfPresent([String: String].self, forKey: .bip321)
            url = c.contains(.url) ? .some(try c.decodeIfPresent(String.self, forKey: .url)) : .none
        }
    }
    actor Requests {
        var urls: [URL] = []
        func fetch(_ request: URLRequest) throws -> (Data, URLResponse) {
            let url = try #require(request.url)
            urls.append(url)
            let body = #"{"tag":"payRequest","callback":"https://example.com/callback","minSendable":1000,"maxSendable":2000}"#
            return (Data(body.utf8), try #require(HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: nil)))
        }
    }

    @Test func matchesNativeVectors() async throws {
        let cases = try Fixtures.load("send-destination-native", input: Input.self, expected: Expected.self)
        #expect(cases.count == 282)
        for fixture in cases {
            let (input, expected) = (fixture.input, fixture.expected)
            let actual = SendDestination.parse(input.destination, network: input.network)
            #expect((actual.destinationType != nil) == expected.valid, "\(fixture.name)")
            #expect(actual.destinationType == expected.destinationType, "\(fixture.name)")
            #expect(actual.amount == expected.amount, "\(fixture.name)")
            #expect(actual.isAmountEditable == expected.isAmountEditable, "\(fixture.name)")
            #expect(actual.error?.message(locale: "en") == expected.error, "\(fixture.name)")
            #expect(actual.bip321 == expected.bip321, "\(fixture.name)")
            guard case .some(let url) = expected.url else { continue }
            // Resolution keeps today's client: HTTPS only, and the first request is the endpoint itself. The session is
            // never opened, so no Ark server key is added.
            let requests = Requests()
            let route = try? await SendRecipientService.resolve(
                SendDestination.cleaned(input.destination).lowercased(), session: WalletSession(variant: .regtest),
                fetch: { try await requests.fetch($0) })
            #expect(await requests.urls.first?.absoluteString == url, "\(fixture.name)")
            #expect((route != nil) == (url != nil), "\(fixture.name)")
        }
    }

    @Test func resolvesDotSegmentsAsOkHttpDoes() {
        // OkHttp HttpUrl's push/pop: the path keeps its trailing slash, never climbs above the root, and only exact dot
        // segments count. The shared vectors cover the links Send sees.
        let paths = [("", "/"), ("/", "/"), ("/a/..", "/"), ("/..", "/"), ("/a/../b/", "/b/"), ("/./", "/"), ("/a//b", "/a//b"),
                     ("/a/%2E", "/a/"), ("/a/.../b", "/a/.../b"), ("/a/%2e%2e%2e/b", "/a/%2e%2e%2e/b")]
        for (path, expected) in paths { #expect(SendDestination.removingDotSegments(path) == expected, "\(path)") }
    }

    @Test func encodesAnLnurlAsLnurlRsDoes() throws {
        // lnurl-rs 0.10's own encode_test vector.
        let url = "https://service.com/api?q=3fc3645b439ce8e7f2553a69e5267081d96dcd340693afabe04be7b0ccd178df"
        let encoded = PaymentEncoding.encodeBech32("lnurl", Array(url.utf8))
        #expect(encoded
            .uppercased() ==
            "LNURL1DP68GURN8GHJ7UM9WFMXJCM99E3K7MF0V9CXJ0M385EKVCENXC6R2C35XVUKXEFCV5MKVV34X5EKZD3EV56NYD3HXQURZE"
            + "PEXEJXXEPNXSCRVWFNV9NXZCN9XQ6XYEFHVGCXXCMYXYMNSERXFQ5FNS")
        let decoded = try #require(PaymentEncoding.bech32(encoded))
        #expect(PaymentEncoding.bytes(decoded.words).flatMap { String(bytes: $0, encoding: .utf8) } == url)
    }
}
