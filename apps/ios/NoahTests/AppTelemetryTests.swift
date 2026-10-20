import Foundation
import Sentry
import Testing

@testable import Noah

struct AppTelemetryTests {
    private var info: [String: Any] {
        ["NoahSentryDSN": "https://public@example.invalid/1",
         "CFBundleIdentifier": "com.noahwallet.signet",
         "CFBundleShortVersionString": "0.2.0", "CFBundleVersion": "100"]
    }

    @Test func sharedSecretsAreScrubbedAtEveryTelemetryBoundary() throws {
        for fixture in try Fixtures.load("log-redaction", input: String.self, expected: String.self)
            where !fixture.name.hasPrefix("address-") {
            let event = Event(level: .error)
            event.message = SentryMessage(formatted: fixture.input)
            event.exceptions = [Exception(value: fixture.input, type: "WalletError")]
            event.context = ["nested": ["array": [["text": fixture.input]]]]
            let crumb = Breadcrumb(level: .info, category: "test")
            crumb.message = fixture.input
            crumb.setData(value: ["text": fixture.input], key: "nested")
            event.breadcrumbs = [crumb]
            let clean = try #require(AppTelemetry.scrub(event), "\(fixture.name)")
            #expect(clean.message?.formatted == fixture.expected)
            #expect(clean.exceptions?.first?.value == fixture.expected)
            #expect(clean.breadcrumbs?.first?.message == fixture.expected)
            let unchecked = Event(level: .error)
            unchecked.transaction = fixture.input
            #expect((AppTelemetry.scrub(unchecked) == nil) == (fixture.input != fixture.expected), "final check \(fixture.name)")
        }
        let words = Array(repeating: "ABANDON", count: 11) + ["ABOUT"]
        let event = Event(level: .error)
        event.extra = ["nested": ["words": words, "access_token": "fake", "clientSecret": "fake"]]
        let clean = try #require(AppTelemetry.scrub(event))
        let nested = try #require(clean.extra?["nested"] as? [String: Any])
        #expect(nested.values.allSatisfy { $0 as? String == "[redacted]" })
        let unchecked = Event(level: .error)
        unchecked.transaction = String(decoding: try JSONSerialization.data(withJSONObject: words), as: UTF8.self)
        #expect(AppTelemetry.scrub(unchecked) == nil)
    }

    @Test func addressesAndNestedIdentityFieldsAreScrubbed() throws {
        let text = "Sending 1000 sat to lightning address alice@example.com"
        let event = Event(level: .error)
        event.message = SentryMessage(formatted: text)
        event.extra = ["nested": [["user": ["email": "fake"], "lightningAddress": "fake"]]]
        event.user = User(userId: "test")
        event.user?.email = "alice@example.com"
        event.user?.data = ["lightning_address": "fake"]
        event.exceptions = [Exception(value: text, type: "WalletError")]
        let crumb = Breadcrumb(level: .info, category: "test")
        crumb.message = text
        crumb.setData(value: ["email": "fake", "lightningAddress": "fake"], key: "nested")
        event.breadcrumbs = [crumb]
        let clean = try #require(AppTelemetry.scrub(event))
        let serialized = String(decoding: try JSONSerialization.data(withJSONObject: clean.serialize()), as: UTF8.self)
        #expect(!serialized.contains("alice@example.com"))
        #expect(!serialized.contains("fake"))
        #expect(clean.user?.email == "[redacted]")
        #expect(clean.message?.formatted == "Sending 1000 sat to lightning address [redacted]")
        let unchecked = Event(level: .error)
        unchecked.transaction = text
        #expect(AppTelemetry.scrub(unchecked) == nil)
    }

    @Test func disabledInDebugAndRegtest() {
        for variant in AppVariant.allCases {
            #expect(AppTelemetry.options(variant: variant, isDebug: true, info: info) == nil)
        }
        #expect(AppTelemetry.options(variant: .regtest, isDebug: false, info: info) == nil)
        #if DEBUG
        #expect(AppTelemetry.isDebug)
        #else
        #expect(!AppTelemetry.isDebug)
        #endif
    }

    @Test func missingDSNDisablesTelemetry() {
        var missing = info
        missing.removeValue(forKey: "NoahSentryDSN")
        #expect(AppTelemetry.options(variant: .mainnet, isDebug: false, info: missing) == nil)
        missing["NoahSentryDSN"] = " \n"
        #expect(AppTelemetry.options(variant: .signet, isDebug: false, info: missing) == nil)
    }

    @Test func matchesRNReleaseEnvironmentAndSampling() throws {
        for variant in [AppVariant.mainnet, .signet] {
            var variantInfo = info
            variantInfo["CFBundleIdentifier"] = "com.noahwallet.\(variant.rawValue)"
            let options = try #require(AppTelemetry.options(variant: variant, isDebug: false, info: variantInfo))
            #expect(options.releaseName == "com.noahwallet.\(variant.rawValue)@0.2.0+100")
            #expect(options.environment == "production")
            #expect(options.dist == "100")
            #expect(options.sampleRate == 1)
            #expect(options.tracesSampleRate == nil)
            #expect(options.sendDefaultPii)
            #expect(options.enableMetricKit)
            #expect(!options.enableAppHangTracking)
            #expect(!options.enableLogs && !options.attachScreenshot && !options.attachViewHierarchy)
            #expect(!options.enableMetricKitRawPayload)
        }
    }

    @Test func installedHooksScrubEventsAndBreadcrumbs() throws {
        let options = try #require(AppTelemetry.options(variant: .signet, isDebug: false, info: info))
        let text = "preimage=synthetic-sensitive-value"
        let breadcrumb = Breadcrumb(level: .info, category: "wallet")
        breadcrumb.message = text
        breadcrumb.setData(value: ["private_key": [1, 2, 3], "safe": "kept"], key: "nested")
        let crumb = try #require(options.beforeBreadcrumb?(breadcrumb))
        #expect(crumb.message == LogFile.redact(text))
        let nested = try #require(crumb.data?["nested"] as? [String: Any])
        #expect(nested["private_key"] as? String == "[redacted]")
        #expect(nested["safe"] as? String == "kept")

        let event = Event(level: .error)
        let message = SentryMessage(formatted: text)
        message.message = "signature=synthetic-value"
        message.params = [text]
        event.message = message
        event.extra = ["payload": ["preimage": [1, 2, 3], "line": text]]
        event.context = ["wallet": ["mnemonic": ["synthetic", "words"], "status": "loaded"]]
        event.tags = ["payment_preimage": "synthetic", "variant": "signet"]
        event.breadcrumbs = [crumb]
        event.exceptions = [Exception(value: text, type: "WalletError")]
        let clean = try #require(options.beforeSend?(event))
        #expect(clean.message?.formatted == LogFile.redact(text))
        #expect(clean.message?.params == ["[redacted]"])
        #expect(clean.context?["wallet"]?["mnemonic"] as? String == "[redacted]")
        #expect(clean.tags?["payment_preimage"] == "[redacted]")
        #expect(clean.exceptions?.first?.value == "[redacted]")
        #expect(clean.context?["wallet"]?["status"] as? String == "loaded")
        let json = try JSONSerialization.data(withJSONObject: clean.serialize())
        #expect(!String(decoding: json, as: UTF8.self).contains("synthetic"))
    }

    @Test func dropsEventsWithSecretsInUnhandledFields() throws {
        let options = try #require(AppTelemetry.options(variant: .mainnet, isDebug: false, info: info))
        let event = Event(level: .error)
        event.transaction = "preimage=synthetic-sensitive-value"
        #expect(options.beforeSend?(event) == nil)
    }

    @Test func ordinaryEventsKeepDiagnosticMetadata() throws {
        let options = try #require(AppTelemetry.options(variant: .mainnet, isDebug: false, info: info))
        let event = Event(level: .error)
        event.message = SentryMessage(formatted: "Wallet sync failed")
        event.extra = ["attempt": 3]
        event.transaction = "wallet.sync"
        let clean = try #require(options.beforeSend?(event))
        #expect(clean === event)
        #expect(clean.transaction == "wallet.sync")
        #expect(clean.extra?["attempt"] as? Int == 3)
    }
}
