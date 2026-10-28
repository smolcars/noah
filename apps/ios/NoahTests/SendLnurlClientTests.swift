import Foundation
@testable import Noah
import Testing

/// The LNURL client against a loopback server, as Android's: no redirects, and 64 KiB at most, enforced while reading.
struct SendLnurlClientTests {
    let payRequest = #"{"tag":"payRequest","callback":"https://example.com/callback","minSendable":1000,"maxSendable":2000}"#
    let lookup = "GET /.well-known/lnurlp/alice HTTP/1.1"

    /// Resolution only builds HTTPS URLs and the loopback server is cleartext, so only scheme, host and port change; the
    /// real client still makes the request.
    func loopback(_ server: LocalEsploraServer) -> SendRecipientService.Fetch {
        { request in
            var components = try #require(request.url.flatMap { URLComponents(url: $0, resolvingAgainstBaseURL: false) })
            components.scheme = "http"
            components.host = "127.0.0.1"
            components.port = Int(server.port)
            var local = request
            local.url = components.url
            return try await SendRecipientService.fetch(local)
        }
    }
    func resolve(_ server: LocalEsploraServer) async throws -> SendRecipientRoute {
        // Never opened, so no Ark server key is added.
        try await SendRecipientService.resolve("alice@example.com", session: WalletSession(variant: .regtest), fetch: loopback(server))
    }
    func fetch(_ server: LocalEsploraServer) async throws -> Data {
        try await SendRecipientService.fetch(URLRequest(url: try #require(URL(string: server.url)), timeoutInterval: 20)).0
    }

    @Test(.timeLimit(.minutes(1))) func aNormalLookupStillWorks() async throws {
        let server = try LocalEsploraServer(.raw(
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: \(payRequest.utf8.count)\r\nConnection: close\r\n\r\n\(payRequest)",
            close: true))
        defer { server.stop() }
        let route = try await resolve(server)
        #expect(route.minMsat == 1000 && route.maxMsat == 2000 && route.callback?.absoluteString == "https://example.com/callback")
        #expect(server.requests == [lookup])
    }

    @Test(.timeLimit(.minutes(1))) func aCallbackOnAnOnionHostOrWithUserinfoIsNeverRequested() async throws {
        for callback in ["https://abc.onion/cb", "https://ABC.ONION./cb", "https://user:pass@example.com/cb", "https://user@example.com/cb"] {
            let body = #"{"tag":"payRequest","callback":"\#(callback)","minSendable":1000,"maxSendable":2000}"#
            let server = try LocalEsploraServer(.raw(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: \(body.utf8.count)\r\nConnection: close\r\n\r\n\(body)",
                close: true))
            defer { server.stop() }
            await #expect(throws: SendFailure.invalid, "\(callback)") {
                try await SendRecipientService.invoice(.init(destination: "alice@example.com", method: "lnurl", amount: 1),
                                                       session: WalletSession(variant: .regtest), fetch: loopback(server))
            }
            #expect(server.requests == [lookup], "\(callback)")
        }
    }

    @Test(.timeLimit(.minutes(1))) func aRedirectFailsTheLookupWithoutASecondRequest() async throws {
        let server = try LocalEsploraServer(.raw(
            "HTTP/1.1 302 Found\r\nLocation: /elsewhere\r\nContent-Length: 0\r\nConnection: close\r\n\r\n", close: true))
        defer { server.stop() }
        await #expect(throws: SendFailure.invalid) { try await resolve(server) }
        try await Task.sleep(for: .milliseconds(300)) // A followed redirect would arrive by now.
        #expect(server.requests == [lookup])
    }

    @Test(.timeLimit(.minutes(1))) func anOversizedBodyWithoutContentLengthStopsAtTheCap() async throws {
        // One byte over and the connection stays open: reading to the end would wait for the 20 s timeout instead.
        let over = try LocalEsploraServer(.raw("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n" + String(repeating: "x", count: 65537), close: false))
        defer { over.stop() }
        let started = ContinuousClock.now
        await #expect(throws: SendFailure.invalid) { try await fetch(over) }
        #expect(ContinuousClock.now - started < .seconds(10))
        // Exactly 64 KiB is still read.
        let exact = try LocalEsploraServer(.raw("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n" + String(repeating: "x", count: 65536), close: true))
        defer { exact.stop() }
        #expect(try await fetch(exact).count == 65536)
    }

    @Test(.timeLimit(.minutes(1))) func anOversizedContentLengthIsRefusedBeforeTheBody() async throws {
        // The connection stays open after these bytes: reading the declared body (or up to the cap) would hit the 20 s
        // timeout instead. URLSession hands over the response only once body bytes arrive (one with a Content-Type, up to
        // 512 for sniffing without), so each reply sends a few; the client refuses on the declared length alone.
        for reply in ["HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 65537\r\n\r\n{",
                      "HTTP/1.1 200 OK\r\nContent-Length: 10000000\r\n\r\n" + String(repeating: "x", count: 600)] {
            let server = try LocalEsploraServer(.raw(reply, close: false))
            defer { server.stop() }
            let started = ContinuousClock.now
            await #expect(throws: SendFailure.invalid) { try await fetch(server) }
            #expect(ContinuousClock.now - started < .seconds(10))
        }
    }
}
