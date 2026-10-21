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
        options.environment = "production"
        options.releaseName = "\(info["CFBundleIdentifier"] as? String ?? "")@\(info["CFBundleShortVersionString"] as? String ?? "")+\(info["CFBundleVersion"] as? String ?? "")"
        options.dist = info["CFBundleVersion"] as? String
        options.sendDefaultPii = false
        options.enableMetricKit = true
        options.enableAppHangTracking = false
        options.sampleRate = 1
        options.tracesSampleRate = nil
        options.enableLogs = false
        // Manual SDK logs and metrics bypass beforeSend and nothing here uses them: drop both.
        options.beforeSendLog = { _ in nil }
        options.beforeSendMetric = { _ in nil }
        // Release Health sessions stay on (2026-10-20): random install ID, release, OS and status only.
        options.enableAutoSessionTracking = true
        options.attachScreenshot = false
        options.attachViewHierarchy = false
        options.enableMetricKitRawPayload = false
        options.maxAttachmentSize = 0
        options.beforeSend = scrub
        // The hint's list is authoritative, including crash/scope attachments.
        options.beforeSendWithHint = { event, hint in
            hint.attachments = []
            return scrub(event)
        }
        options.beforeBreadcrumb = scrub
        return options
    }

    static func scrub(_ event: Event) -> Event? {
        // Construct a fresh event: new SDK fields and arbitrary payloads have no admission path.
        let clean = Event(level: event.level)
        clean.eventId = event.eventId
        clean.timestamp = event.timestamp
        clean.platform = event.platform
        clean.releaseName = event.releaseName
        clean.dist = event.dist
        clean.environment = event.environment
        clean.message = event.message.flatMap { message in
            LogFile.redactForTelemetry(message.formatted).map { SentryMessage(formatted: $0) }
        }
        clean.sdk = event.sdk.map { sdk in
            var result = scalars(sdk, keys: ["name", "version"])
            for key in ["integrations", "features"] {
                if let values = sdk[key] as? [String] { result[key] = values }
            }
            if let packages = sdk["packages"] as? [[String: Any]] {
                result["packages"] = packages.map { scalars($0, keys: ["name", "version"]) }
            }
            return result
        }
        clean.context = event.context.map { contexts in
            var result: [String: [String: Any]] = [:]
            for (name, keys) in contextKeys {
                if let fields = contexts[name] { result[name] = scalars(fields, keys: keys) }
            }
            return result
        }
        clean.tags = event.tags?.filter { key, value in
            key == "app.variant" && ["mainnet", "signet", "regtest"].contains(value)
        }
        clean.stacktrace = event.stacktrace.map(stacktrace)
        clean.threads = event.threads?.map { thread in
            let result = SentryThread(threadId: thread.threadId)
            result.name = thread.name
            result.crashed = thread.crashed
            result.current = thread.current
            result.isMain = thread.isMain
            result.stacktrace = thread.stacktrace.map(stacktrace)
            return result
        }
        clean.exceptions = event.exceptions?.compactMap { exception in
            let value = exception.value.flatMap(LogFile.redactForTelemetry)
            guard value != nil || exception.type != nil else { return nil }
            let result = Exception(value: value, type: exception.type)
            result.module = exception.module
            result.threadId = exception.threadId
            result.stacktrace = exception.stacktrace.map(stacktrace)
            if let mechanism = exception.mechanism {
                let safe = Mechanism(type: mechanism.type)
                safe.handled = mechanism.handled
                safe.synthetic = mechanism.synthetic
                result.mechanism = safe
            }
            return result
        }
        clean.breadcrumbs = event.breadcrumbs?.compactMap(scrub)
        guard isSafePayload(clean.serialize()) else { return nil }
        return clean
    }

    static func scrub(_ breadcrumb: Breadcrumb) -> Breadcrumb? {
        let clean = Breadcrumb(level: breadcrumb.level, category: breadcrumb.category)
        clean.timestamp = breadcrumb.timestamp
        clean.message = breadcrumb.message.flatMap(LogFile.redactForTelemetry)
        guard isSafePayload(clean.serialize()) else { return nil }
        return clean
    }

    private static let contextKeys: [String: Set<String>] = [
        "app": ["type", "app_identifier", "app_version", "app_build", "app_start_time",
                "app_memory", "in_foreground", "build_type"],
        "device": ["type", "family", "model", "model_id", "arch", "battery_level", "charging",
                   "simulator", "memory_size", "free_memory", "usable_memory", "storage_size",
                   "free_storage", "boot_time", "processor_count", "cpu_description",
                   "screen_width_pixels", "screen_height_pixels", "screen_density", "screen_dpi",
                   "orientation", "thermal_state", "low_memory"],
        "os": ["type", "name", "version", "build", "kernel_version", "rooted"],
    ]

    private static func scalars(_ fields: [String: Any], keys: Set<String>) -> [String: Any] {
        fields.filter { key, value in keys.contains(key) && (value is String || value is NSNumber) }
    }

    private static func stacktrace(_ trace: SentryStacktrace) -> SentryStacktrace {
        SentryStacktrace(frames: trace.frames.map { frame in
            let clean = Frame()
            clean.function = frame.function
            clean.module = frame.module
            clean.fileName = frame.fileName
            clean.lineNumber = frame.lineNumber
            return clean
        }, registers: [:])
    }

    static func isSafePayload(_ payload: [String: Any]) -> Bool {
        guard let leaves = stringLeaves(payload),
              (leaves.keys + leaves.values).allSatisfy({ LogFile.redact($0) == $0 }) else { return false }
        // Aggregate keys too, separately from values: fixed schema names must not
        // complete an otherwise harmless 11-word excerpt. The severity is typed metadata.
        guard let content = stringLeaves(payload.filter { $0.key != "level" }) else { return false }
        guard !LogFile.containsMnemonic(in: content.keys), !LogFile.containsMnemonic(in: content.values) else { return false }
        guard EndpointLogPrivacy.requiresSerializedGuard else { return true }
        guard let data = try? JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys, .withoutEscapingSlashes]) else { return false }
        // Inspect the wire form too: numeric fields and joins are not string leaves.
        let json = String(decoding: data, as: UTF8.self)
        return EndpointLogPrivacy.redactRecord(json) == json
    }

    private static func stringLeaves(_ value: Any, depth: Int = 0) -> (keys: [String], values: [String])? {
        guard depth < 32 else { return nil }
        if let string = value as? String { return ([], [string]) }
        if value is NSNumber || value is NSNull { return ([], []) }
        let children: [Any]
        var result: (keys: [String], values: [String]) = ([], [])
        if let array = value as? [Any] { children = array }
        else if let fields = value as? [String: Any] {
            result.keys = fields.keys.sorted()
            children = result.keys.map { fields[$0]! }
        } else { return nil }
        for child in children {
            guard let leaves = stringLeaves(child, depth: depth + 1) else { return nil }
            result.keys.append(contentsOf: leaves.keys)
            result.values.append(contentsOf: leaves.values)
        }
        return result
    }
}
