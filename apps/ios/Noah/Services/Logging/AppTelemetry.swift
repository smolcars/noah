import Foundation
import Sentry

enum AppTelemetry {
    #if DEBUG
    static let isDebug = true
    #else
    static let isDebug = false
    #endif

    static func start() {
        guard let options = options(variant: .current, isDebug: isDebug,
                                    info: Bundle.main.infoDictionary ?? [:]) else { return }
        SentrySDK.start(options: options)
        SentrySDK.configureScope { $0.setTag(value: AppVariant.current.rawValue, key: "app.variant") }
    }

    static func options(variant: AppVariant, isDebug: Bool, info: [String: Any]) -> Options? {
        guard !isDebug, variant != .regtest,
              let dsn = info["NoahSentryDSN"] as? String,
              !dsn.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        let options = Options()
        options.dsn = dsn
        options.environment = "production" // RN getDefaultEnvironment(), when !__DEV__.
        options.releaseName = "\(info["CFBundleIdentifier"] as? String ?? "")@\(info["CFBundleShortVersionString"] as? String ?? "")+\(info["CFBundleVersion"] as? String ?? "")"
        options.dist = info["CFBundleVersion"] as? String
        options.sendDefaultPii = true
        options.enableMetricKit = true
        options.enableAppHangTracking = false
        // RN leaves error sampling at 1 and tracing off. No logs or UI attachments.
        options.sampleRate = 1
        options.tracesSampleRate = nil
        options.enableLogs = false
        options.attachScreenshot = false
        options.attachViewHierarchy = false
        options.enableMetricKitRawPayload = false
        options.beforeSend = scrub
        options.beforeBreadcrumb = scrub
        return options
    }

    static func scrub(_ event: Event) -> Event? {
        if let message = event.message {
            let clean = SentryMessage(formatted: LogFile.redact(message.formatted))
            clean.message = message.message.map(LogFile.redact)
            clean.params = message.params?.map(LogFile.redact)
            event.message = clean
        }
        event.extra = event.extra.map(scrubFields)
        event.context = event.context?.mapValues(scrubFields)
        event.tags = event.tags.map { scrubFields($0).compactMapValues { $0 as? String } }
        event.breadcrumbs = event.breadcrumbs?.compactMap(scrub)
        for exception in event.exceptions ?? [] {
            exception.value = exception.value.map(LogFile.redact)
            exception.type = exception.type.map(LogFile.redact)
        }
        // Fail closed if a less common SDK field (request/user/stack frame/etc.)
        // still contains a secret. Keep diagnostic stacks on ordinary events.
        let serialized = event.serialize()
        guard NSDictionary(dictionary: serialized).isEqual(NSDictionary(dictionary: scrubFields(serialized))) else { return nil }
        return event
    }

    static func scrub(_ breadcrumb: Breadcrumb) -> Breadcrumb? {
        breadcrumb.message = breadcrumb.message.map(LogFile.redact)
        breadcrumb.category = LogFile.redact(breadcrumb.category)
        breadcrumb.type = breadcrumb.type.map(LogFile.redact)
        breadcrumb.origin = breadcrumb.origin.map(LogFile.redact)
        if let data = breadcrumb.data {
            let clean = scrubFields(data)
            for (key, value) in clean { breadcrumb.setData(value: value, key: key) }
            for key in data.keys where clean[key] == nil { breadcrumb.setData(value: nil, key: key) }
        }
        return breadcrumb
    }

    private static func scrubValue(_ value: Any) -> Any {
        if let string = value as? String { return LogFile.redact(string) }
        if let array = value as? [Any] { return array.map(scrubValue) }
        if let fields = value as? [String: Any] { return scrubFields(fields) }
        return value
    }

    private static func scrubFields(_ fields: [String: Any]) -> [String: Any] {
        fields.reduce(into: [String: Any]()) { result, field in
            let key = field.key.lowercased().filter(\.isLetter)
            let secret = ["mnemonic", "seed", "privatekey", "secretkey", "signature", "preimage",
                          "authorization", "password", "token", "xamz"].contains { key.contains($0) }
            result[LogFile.redact(field.key)] = secret ? "[redacted]" : scrubValue(field.value)
        }
    }
}
