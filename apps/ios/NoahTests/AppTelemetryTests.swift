import Foundation
import Sentry
import Testing

@testable import Noah

struct AppTelemetryTests {
    @Test func mnemonicDictionaryKeysMustNotReachTelemetry() throws {
        let phrase = "ozone drill grab fiber curtain grace pudding thank cruise elder eight picnic"
        #expect(try StoredSeed.normalized(phrase) == phrase)
        let event = Event(level: .error)
        event.extra = ["restore": phrase.split(separator: " ").enumerated().map { [String($0.element): $0.offset] }]
        let clean = try #require(AppTelemetry.scrub(event))
        #expect(clean.extra == nil)
        let json = String(decoding: try JSONSerialization.data(withJSONObject: clean.serialize()), as: UTF8.self)
        #expect(!json.contains("ozone"))
    }

    @Test func allowlistDropsUnapprovedFieldsAndNestedPayloads() throws {
        let event = Event(level: .error)
        event.extra = ["records": Array(repeating: "arbitrary payload", count: 100_000)]
        event.context = ["wallet": ["state": "arbitrary payload"],
                         "device": ["model": "iPhone", "name": "personal device", "arbitrary": "payload"],
                         "app": ["app_version": "0.2.0", "device_app_hash": "personal identifier"],
                         "os": ["name": "iOS", "version": "27.0", "arbitrary": "payload"]]
        event.user = User(userId: "personal identifier")
        event.request = SentryRequest()
        event.request?.headers = ["Authorization": "fake-token"]
        event.transaction = "arbitrary payload"
        event.fingerprint = ["arbitrary payload"]
        event.tags = ["app.variant": "signet", "arbitrary": "payload"]
        let frame = Frame()
        frame.function = "syncWallet"
        frame.module = "Noah"
        frame.fileName = "Sync.swift"
        frame.lineNumber = 42
        frame.vars = ["arbitrary": "payload"]
        frame.contextLine = "arbitrary payload"
        frame.preContext = ["arbitrary payload"]
        frame.postContext = ["arbitrary payload"]
        event.stacktrace = SentryStacktrace(frames: [frame], registers: ["arbitrary": "payload"])
        let clean = try #require(AppTelemetry.scrub(event))
        #expect(clean.extra == nil && clean.user == nil && clean.request == nil)
        #expect(clean.transaction == nil && clean.fingerprint == nil)
        #expect(clean.context?["wallet"] == nil)
        #expect(clean.context?["device"]?["name"] == nil)
        #expect(clean.context?["device"]?["model"] as? String == "iPhone")
        #expect(clean.context?["app"]?["device_app_hash"] == nil)
        #expect(clean.context?["os"]?["version"] as? String == "27.0")
        #expect(clean.tags == ["app.variant": "signet"])
        #expect(clean.stacktrace?.frames.first?.function == "syncWallet")
        #expect(clean.stacktrace?.frames.first?.vars == nil)
        #expect(clean.stacktrace?.frames.first?.contextLine == nil)
        #expect(clean.stacktrace?.registers.isEmpty == true)
        let json = String(decoding: try JSONSerialization.data(withJSONObject: clean.serialize()), as: UTF8.self)
        #expect(!json.contains("payload") && !json.contains("personal"))
    }

    @Test func escapedJSONMustNotReachTelemetry() throws {
        let value = String(repeating: "ab", count: 32)
        let object = try JSONSerialization.data(withJSONObject: ["preimage": value])
        let wrapped = try JSONSerialization.data(withJSONObject: [String(decoding: object, as: UTF8.self)])
        let event = Event(level: .error)
        event.message = SentryMessage(formatted: String(decoding: wrapped, as: UTF8.self))
        let clean = try #require(AppTelemetry.scrub(event))
        #expect(clean.message?.formatted == "[redacted]")
    }

    @Test func uncertainMessagesAreOmitted() throws {
        let event = Event(level: .error)
        let uncertain = "invalid escape: \\uD800"
        event.message = SentryMessage(formatted: uncertain)
        event.exceptions = [Exception(value: uncertain, type: "WalletError")]
        let crumb = Breadcrumb(level: .info, category: "diagnostics")
        crumb.message = uncertain
        event.breadcrumbs = [crumb]
        let clean = try #require(AppTelemetry.scrub(event))
        #expect(clean.message == nil)
        #expect(clean.exceptions?.first?.value == nil)
        #expect(clean.breadcrumbs?.first?.message == nil)
        #expect(LogFile.redact(uncertain) == "[redacted]")
    }

    private var info: [String: Any] {
        ["NoahSentryDSN": "https://public@example.invalid/1",
         "CFBundleIdentifier": "com.noahwallet.signet",
         "CFBundleShortVersionString": "0.2.0", "CFBundleVersion": "100"]
    }

    @Test func sharedSecretsAreScrubbedAtEveryTelemetryBoundary() throws {
        for fixture in try Fixtures.load("log-redaction", input: String.self, expected: String.self)
            {
            // Isolate each boundary: duplicating an 11-word excerpt across the event
            // now intentionally constitutes a complete aggregate mnemonic run.
            for boundary in ["message", "exception", "context", "breadcrumb"] {
                let event = Event(level: .error)
                switch boundary {
                case "message": event.message = SentryMessage(formatted: fixture.input)
                case "exception": event.exceptions = [Exception(value: fixture.input, type: "WalletError")]
                case "context": event.context = ["nested": ["text": fixture.input]]
                default:
                    let crumb = Breadcrumb(level: .info, category: "diagnostics")
                    crumb.message = fixture.input
                    event.breadcrumbs = [crumb]
                }
                let clean = try #require(AppTelemetry.scrub(event), "\(boundary) \(fixture.name)")
                let text: String?
                switch boundary {
                case "message": text = clean.message?.formatted
                case "exception": text = clean.exceptions?.first?.value
                case "context": text = clean.context?["nested"]?["text"] as? String
                default: text = clean.breadcrumbs?.first?.message
                }
                #expect(text == (boundary == "context" ? nil : LogFile.redact(fixture.input)), "\(boundary) \(fixture.name)")
            }
            let unchecked = Event(level: .error)
            unchecked.releaseName = fixture.input
            #expect((AppTelemetry.scrub(unchecked) == nil) == (fixture.input != LogFile.redact(fixture.input)), "final check \(fixture.name)")
        }
        let words = Array(repeating: "ABANDON", count: 11) + ["ABOUT"]
        let event = Event(level: .error)
        event.extra = ["nested": ["words": words, "access_token": "fake", "clientSecret": "fake"]]
        let clean = try #require(AppTelemetry.scrub(event))
        #expect(clean.extra == nil)
        let unchecked = Event(level: .error)
        unchecked.releaseName = String(decoding: try JSONSerialization.data(withJSONObject: words), as: UTF8.self)
        #expect(AppTelemetry.scrub(unchecked) == nil)
    }

    @Test(arguments: ["extra", "context", "tags", "breadcrumb", "exceptions", "wholeEvent"])
    func detectsMnemonicLeavesAcrossNestedContainers(boundary: String) throws {
        let words = Array(repeating: "abandon", count: 11) + ["about"]
        let nested: [Any] = words.enumerated().map {
            ["position": $0.offset + 1, "word": ["value": $0.element]] as [String: Any]
        }
        let event = Event(level: .error)
        switch boundary {
        case "extra": event.extra = ["restore": nested]
        case "context": event.context = ["restore": ["entries": nested]]
        case "tags": event.tags = Dictionary(uniqueKeysWithValues: words.enumerated().map { ("part\($0.offset)", $0.element) })
        case "breadcrumb":
            let crumb = Breadcrumb(level: .info, category: "test")
            crumb.setData(value: nested, key: "restore")
            event.breadcrumbs = [crumb]
        case "exceptions": event.exceptions = words.map { Exception(value: $0, type: "WalletError") }
        default:
            event.extra = ["partA": Array(nested.prefix(6))]
            event.context = ["partB": ["entries": Array(nested.suffix(6))]]
        }
        guard let clean = AppTelemetry.scrub(event) else { return }
        let json = try JSONSerialization.data(withJSONObject: clean.serialize())
        #expect(!String(decoding: json, as: UTF8.self).contains("abandon"))
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
        #expect(clean.user == nil)
        #expect(clean.message?.formatted == "Sending 1000 sat to lightning address [redacted]")
        let unchecked = Event(level: .error)
        unchecked.releaseName = text
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
            #expect(!options.sendDefaultPii)
            #expect(options.enableMetricKit)
            #expect(!options.enableAppHangTracking)
            #expect(!options.enableLogs && !options.attachScreenshot && !options.attachViewHierarchy)
            #expect(!options.enableMetricKitRawPayload)
            #expect(options.enableAutoSessionTracking)
            #expect(options.beforeSendLog?(SentryLog(level: .info, body: "synthetic")) == nil)
            #expect(options.beforeSendMetric != nil)
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
        #expect(crumb.data == nil)

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
        #expect(clean.message?.params == nil && clean.message?.message == nil)
        #expect(clean.context?["wallet"] == nil)
        #expect(clean.tags?["payment_preimage"] == nil)
        #expect(clean.exceptions?.first?.value == "[redacted]")
        #expect(clean.context?["wallet"]?["status"] == nil)
        let json = try JSONSerialization.data(withJSONObject: clean.serialize())
        #expect(!String(decoding: json, as: UTF8.self).contains("synthetic"))
    }

    @Test func dropsEventsWithSecretsInUnhandledFields() throws {
        let options = try #require(AppTelemetry.options(variant: .mainnet, isDebug: false, info: info))
        let event = Event(level: .error)
        event.releaseName = "preimage=synthetic-sensitive-value"
        #expect(options.beforeSend?(event) == nil)
    }

    @Test func ordinaryEventsKeepDiagnosticMetadata() throws {
        let options = try #require(AppTelemetry.options(variant: .mainnet, isDebug: false, info: info))
        let event = Event(level: .error)
        event.message = SentryMessage(formatted: "Wallet sync failed")
        event.extra = ["attempt": 3]
        event.transaction = "wallet.sync"
        let clean = try #require(options.beforeSend?(event))
        #expect(clean.eventId == event.eventId && clean.timestamp == event.timestamp)
        #expect(clean.message?.formatted == "Wallet sync failed")
        #expect(clean.transaction == nil && clean.extra == nil)
    }
    @Test func finalGuardIncludesKeysAndFailsClosed() {
        let words = "ozone drill grab fiber curtain grace pudding thank cruise elder eight picnic".split(separator: " ")
        let keys = words.enumerated().map { [String($0.element): $0.offset] }
        #expect(!AppTelemetry.isSafePayload(["records": keys]))
        #expect(!AppTelemetry.isSafePayload(["preimage=fake": 0]))
        #expect(!AppTelemetry.isSafePayload(["opaque": UnexpectedValue()]))
        #expect(AppTelemetry.isSafePayload(["status": "sync failed", "attempt": 3]))
        var deep: [String: Any] = ["status": "sync failed"]
        for _ in 0..<33 { deep = ["nested": deep] }
        #expect(!AppTelemetry.isSafePayload(deep))
    }

    @Test func attachmentsAreRemovedByInstalledHook() throws {
        let options = try #require(AppTelemetry.options(variant: .mainnet, isDebug: false, info: info))
        let hint = Hint()
        hint.attachments = [Attachment(data: Data("fake attachment".utf8), filename: "fake.txt")]
        let event = Event(level: .error)
        event.message = SentryMessage(formatted: "Wallet sync failed")
        #expect(options.beforeSendWithHint?(event, hint) != nil)
        #expect(hint.attachments.isEmpty)
        #expect(options.maxAttachmentSize == 0)
    }

    final class UnexpectedValue: NSObject {
        override var description: String { "preimage=synthetic-private-value" }
    }

    @Test func unexpectedObjectAndRequestHeadersFailClosed() {
        let event = Event(level: .error)
        event.extra = ["unexpected": UnexpectedValue()]
        #expect(AppTelemetry.scrub(event)?.extra == nil)
        let requestEvent = Event(level: .error)
        requestEvent.request = SentryRequest()
        requestEvent.request?.headers = ["Authorization": "synthetic-credential"]
        #expect(AppTelemetry.scrub(requestEvent)?.request == nil)
    }

    @Test func nestedWordObjectsMustNotReachTelemetry() throws {
        let words = Array(repeating: "abandon", count: 11) + ["about"]
        let event = Event(level: .error)
        event.extra = ["restore": words.enumerated().map { ["position": $0.offset + 1, "word": $0.element] as [String: Any] }]
        guard let clean = AppTelemetry.scrub(event) else { return }
        let text = String(decoding: try JSONSerialization.data(withJSONObject: clean.serialize()), as: UTF8.self)
        #expect(!text.contains("abandon"))
    }


}
