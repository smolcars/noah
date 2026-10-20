import BarkBoltFFI
import Foundation
import Sentry
import Testing

@testable import Noah

struct EndpointPrivacyTests {
    @Test func hostCredentialsAndEncodedIdentitiesNeverReachSinks() throws {
        let origin = "https://fakehostcredential73.example.com:8443"
        EndpointLogPrivacy.register(origin + "/api")
        let inputs = [origin, origin + "/api/blocks/tip/height", origin.uppercased(),
            origin.replacingOccurrences(of: "fake", with: #"\u0066ake"#),
            origin.replacingOccurrences(of: ":", with: "%3A").replacingOccurrences(of: "/", with: "%2F"),
            origin.replacingOccurrences(of: "/", with: "%2F"),
            origin.replacingOccurrences(of: "/", with: #"\/"#),
            origin.replacingOccurrences(of: ":", with: "&#58;").replacingOccurrences(of: "/", with: "&#x2f;")]
        for input in inputs {
            #expect(LogFile.redact(input) == "[custom-esplora]")
            let event = Event(level: .error); event.message = SentryMessage(formatted: input)
            #expect(try #require(AppTelemetry.scrub(event)).message?.formatted == "[custom-esplora]")
            let crumb = Breadcrumb(level: .info, category: "esplora"); crumb.message = input
            #expect(try #require(AppTelemetry.scrub(crumb)).message == "[custom-esplora]")
            event.releaseName = input
            #expect(AppTelemetry.scrub(event) == nil)
        }
    }

    @Test func providerBodiesAreRemovedAtEveryBoundaryAndHistoricalExport() throws {
        let input = #"HttpResponse { status: 404, message: "Account: &#x66;akeBodyCredential83" }"#
        let expected = "HttpResponse { status: 404 }"
        for response in [input, #"HttpResponse { status: 404, message: "truncated fake"#,
            #"HttpResponse { status: 404, message: "bad \uD800 escape" }"#,
            input.replacingOccurrences(of: "{", with: "&#123;").replacingOccurrences(of: "}", with: "&#125;")] {
            #expect(LogFile.redact(response) == expected)
            #expect(LogFile.redactForTelemetry(response) == expected)
        }
        #expect(LogFile.redact(#"HttpResponse { message: "fakeUnknownBody" }"#) == "HttpResponse")
        #expect(SafeErrorDescription.of(BarkError(message: input)) == expected)
        let event = Event(level: .error); event.message = SentryMessage(formatted: input)
        #expect(try #require(AppTelemetry.scrub(event)).message?.formatted == expected)
        let directory = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try input.write(to: directory.appending(path: "noah.log.1"), atomically: true, encoding: .utf8)
        let file = LogFile(directory: directory)
        AppLog("wallet", file: file).e(SafeErrorDescription.forLogging(BarkError(message: input)))
        #expect(file.lines().allSatisfy { $0.hasSuffix(expected) })
    }

    @Test func routeOnlyEndpointsPreserveOtherOriginsAndDiagnostics() {
        EndpointLogPrivacy.register("https://route-privacy.example/api")
        for input in ["https://mempool.space/api/blocks/tip/height", "HTTP 404: /blocks/tip/height failed", "API v1 request completed"] {
            #expect(LogFile.redact(input) == input)
        }
    }
}
