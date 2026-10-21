import BarkBoltFFI
import Foundation
import Sentry
import Testing

@testable import Noah

struct EndpointPrivacyTests {
    @Test func equivalentAuthoritiesUseSharedFixturesAtEveryBoundary() throws {
        struct Case: Decodable { let endpoint: String; let input: String; let redacted: Bool; let expected: String }
        let cases = try Fixtures.decode("esplora-log-authorities", as: [Case].self)
        cases.forEach { EndpointLogPrivacy.register($0.endpoint) }
        let directory = FileManager.default.temporaryDirectory.appending(path: "authority-\(UUID())")
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = LogFile(directory: directory)
        for test in cases {
            let input = test.input
            let safe = LogFile.redact(input)
            #expect(safe == test.expected, "\(input)")
            #expect(SafeErrorDescription.of(BarkError(message: input)) == safe)
            let event = Event(level: .error); event.message = SentryMessage(formatted: input)
            event.exceptions = [Exception(value: input, type: "FakeEndpointError")]
            let clean = try #require(AppTelemetry.scrub(event))
            #expect(clean.message?.formatted == safe)
            #expect(clean.exceptions?.first?.value == safe)
            #expect(clean.exceptions?.first?.type == "FakeEndpointError")
            let crumb = Breadcrumb(level: .info, category: "esplora"); crumb.message = input
            #expect(try #require(AppTelemetry.scrub(crumb)).message == safe)
            AppLog("authority", file: file).e(input)
        }
        let lines = file.lines()
        #expect(lines.count == cases.count)
        for (line, test) in zip(lines, cases) { #expect(line.hasSuffix(LogFile.redact(test.input).trimmingCharacters(in: .whitespacesAndNewlines))) }
        try cases.filter(\.redacted).map(\.input).joined(separator: "\n").write(to: directory.appending(path: "noah.log.1"), atomically: true, encoding: .utf8)
        #expect(!file.lines().joined().contains("bücher.example)"))
        #expect(!file.lines().joined().contains("[0:0::1]"))
    }

    @Test func secretTokensUseSharedFixturesAtEveryBoundary() throws {
        struct Case: Decodable { let endpoint: String; let input: String; let expected: String }
        let cases = try Fixtures.decode("esplora-log-tokens", as: [Case].self)
        cases.forEach { EndpointLogPrivacy.register($0.endpoint) }
        let directory = FileManager.default.temporaryDirectory.appending(path: "tokens-\(UUID())")
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = LogFile(directory: directory)
        for test in cases {
            #expect(LogFile.redact(test.input) == test.expected, "\(test.input)")
            #expect(SafeErrorDescription.of(BarkError(message: test.input)) == test.expected)
            let event = Event(level: .error); event.message = SentryMessage(formatted: test.input)
            event.exceptions = [Exception(value: test.input, type: "FakeEndpointError")]
            let clean = try #require(AppTelemetry.scrub(event))
            #expect(clean.message?.formatted == test.expected)
            #expect(clean.exceptions?.first?.value == test.expected)
            let crumb = Breadcrumb(level: .info, category: "esplora"); crumb.message = test.input
            #expect(try #require(AppTelemetry.scrub(crumb)).message == test.expected)
            AppLog("tokens", file: file).e(test.input)
        }
        #expect(file.lines().count == cases.count)
        for (line, test) in zip(file.lines(), cases) { #expect(line.hasSuffix(test.expected)) }
        for test in cases {
            try test.input.write(to: directory.appending(path: "noah.log.1"), atomically: true, encoding: .utf8)
            #expect(file.lines().first?.hasSuffix(test.expected) == true)
        }
    }

    @Test func combiningMarksCannotHideRawTokens() throws { try round8Tokens("combining") }
    @Test func idna2008UnicodeFormsAreRegistered() throws { try round8Tokens("IDNA2008") }

    private func round8Tokens(_ family: String) throws {
        struct Case: Decodable { let name: String; let endpoint: String; let input: String; let expected: String }
        for test in try Fixtures.decode("esplora-log-tokens", as: [Case].self) where test.name.hasPrefix(family) {
            EndpointLogPrivacy.register(test.endpoint)
            #expect(LogFile.redact(test.input) == test.expected, "\(test.name)")
        }
    }

    @Test func tokenAutomatonFollowsFailureLinksAndProtectsProviderSummaries() throws {
        let matcher = EndpointLogPrivacy.SecretMatcher(["he", "sheq", "hers", "his", "fake😀Secret"])
        for input in ["she", "ushers", "hish", "FAKE😀SECRET"] { #expect(matcher.contains(input)) }
        #expect(!matcher.contains("ordinary diagnostic"))
        #expect(EndpointLogPrivacy.SecretMatcher(["fakeStraßeSecret"]).contains("FAKESTRAẞESECRET"))
        let unicode = "fakeΐSecret761"
        let normalized = try #require(EndpointLogPrivacy.SecretMatcher.normalize(unicode))
        #expect(EndpointLogPrivacy.SecretMatcher.normalize(normalized) == normalized)
        #expect(EndpointLogPrivacy.SecretMatcher([normalized]).contains(unicode))
        let provider = EndpointLogPrivacy.SecretMatcher(["HttpResponse"])
        for input in ["HttpResponse { status: 503 }", "HttpResponse unknown", #"HttpResponse \uD800"#] {
            #expect(EndpointLogPrivacy.sanitize(input, registered: ["https://fake-provider.example"], matcher: provider) == "[custom-esplora]")
        }
    }

    @Test func hostCredentialsAndEncodedIdentitiesNeverReachSinks() throws {
        let origin = "https://fakehostcredential73.example.com:8443"
        EndpointLogPrivacy.register(origin + "/api")
        EndpointLogPrivacy.register("https://fakepunctcredential92.example.com/api")
        let inputs = ["https://fakepunctcredential92.example.com:443)", origin, origin + "/api/blocks/tip/height", origin.uppercased(), origin + ")", origin + ",", origin + ".",
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
        #expect(LogFile.redact("prefix" + origin) == "[custom-esplora]")
        #expect(LogFile.redact("TransportError HTTP 503: fakehostcredential73.example.com:invalid") == "TransportError HTTP 503: [custom-esplora]")
    }

    @Test func unknownHTMLEntitiesAndUnterminatedNumericEntitiesCannotExposeHosts() {
        EndpointLogPrivacy.register("https://fakeentitycredential91.example.com/api")
        let unknown = "https://fakeentitycredential91&period;example&period;com/api"
        #expect(LogFile.redact(unknown) == "[redacted]")
        #expect(LogFile.redactForTelemetry(unknown) == nil)
        let numeric = "https:&#47&#47fakeentitycredential91.example.com/api"
        #expect(LogFile.redact(numeric) == "[custom-esplora]")
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

    @Test func configuredOriginsAndDiagnosticsRemainVisibleInPrivacyMode() {
        EndpointLogPrivacy.register("https://route-privacy.example/api")
        for input in ["https://mempool.space/api/blocks/tip/height", "HTTP 404: /blocks/tip/height failed", "API v1 request completed"] {
            #expect(LogFile.redact(input) == input)
        }
        for input in ["https://.", "https://route-privacy.example.other/api", "https://route-privacy.example:8443/api"] {
            #expect(LogFile.redact(input) == "[custom-esplora]")
        }
    }

    @Test func cleanInstallAndUserEnteredDefaultsUseSeparatePolicies() throws {
        struct Case: Decodable { let input: String }
        for test in try Fixtures.decode("esplora-log-authorities", as: [Case].self) {
            #expect(EndpointLogPrivacy.sanitize(test.input, registered: []) == test.input)
        }
        for variant in AppVariant.allCases {
            let config = WalletConfiguration.base(for: variant)
            for endpoint in [ServerEndpoint.baseURL(for: variant).absoluteString, config.arkServer, config.esplora].compactMap({ $0 }) {
                let url = try #require(URLComponents(string: endpoint))
                let origin = url.scheme! + "://" + url.host! + (url.port.map { ":" + String($0) } ?? "")
                #expect(EndpointLogPrivacy.sanitize(endpoint, registered: ["https://fake-custom.example"]) == endpoint)
                #expect(EndpointLogPrivacy.sanitize(endpoint, registered: [origin]) == "[custom-esplora]")
            }
        }
        let hosts: Set<String> = ["abandon", "mnemonic", "preimage", "password"]
        let registered = Set(hosts.map { "https://" + $0 })
        for test in try Fixtures.load("log-redaction", input: String.self, expected: String.self)
            where ["mnemonic-lower", "label-mnemonic", "label-preimage", "label-password"].contains(test.name) {
            #expect(EndpointLogPrivacy.sanitize(test.input, registered: registered, hosts: hosts) == test.expected)
            #expect(EndpointLogPrivacy.sanitize("TransportError HTTP 503: " + test.input, registered: registered, hosts: hosts) == "TransportError HTTP 503: " + test.expected)
        }

    }
}
