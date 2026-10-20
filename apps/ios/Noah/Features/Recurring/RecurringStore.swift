import CoreFoundation
import Foundation

/// Keeps RN's version-0 envelope and unknown fields. Corruption is never treated as an empty store.
final class RecurringStore: @unchecked Sendable {
    static let key = "recurring-payment-storage"
    struct Binding: Codable, Equatable, Sendable {
        var wallet: SendWalletIdentity
        var instance: UUID
    }
    struct State: Sendable {
        var schedules: [String: RecurringPayment]
        var pending: Bool
        var binding: Binding?
    }
    private let settings: SettingsStorage
    private let write: @Sendable (String) throws -> Void
    private let lock = NSRecursiveLock()
    private var writeFailed = false
    init(settings: SettingsStorage = .shared, write: (@Sendable (String) throws -> Void)? = nil) {
        self.settings = settings
        self.write = write ?? { try settings.setDurably($0, forKey: Self.key) }
    }
    func checkAutomationAllowed() throws {
        try lock.withLock {
            guard !writeFailed, WalletLifecycle.automationAllowed(settings: settings) else {
                throw RecurringError.message("Recurring payment storage failed. Restart Noah; sending is stopped.")
            }
        }
    }

    private func envelope() throws -> [String: Any] {
        guard let raw = settings.string(forKey: Self.key) else {
            return ["version": 0, "state": ["schedules": [String: Any](), "serverSyncPending": false]]
        }
        guard let value = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any],
              value["version"] == nil || (value["version"] as? NSNumber).map({ CFGetTypeID($0) != CFBooleanGetTypeID() && $0 == 0 }) == true,
              let state = value["state"] as? [String: Any], state["schedules"] is [String: Any]
        else { throw RecurringError.message("Recurring payment data couldn't be read. Your saved schedules have been preserved. Sending is stopped.") }
        return value
    }
    func load() throws -> State {
        try lock.withLock {
            guard !writeFailed else { throw RecurringError.message("Recurring payment storage failed. Restart Noah; sending is stopped.") }
            let state = try envelope()["state"] as! [String: Any]
            let raw = state["schedules"] as! [String: Any]
            var schedules = try JSONDecoder().decode([String: RecurringPayment].self, from: JSONSerialization.data(withJSONObject: raw))
            guard schedules.allSatisfy({ $0.key == $0.value.id && $0.value.validPolicy }) else {
                throw RecurringError.message("A saved recurring payment is invalid. Schedules have been preserved; sending is stopped.")
            }
            // RN can persist amounts above the native limit. Keep every policy visible and stopped.
            for (id, var schedule) in schedules where schedule.amountSat > RecurringPayment.maximumAmount {
                schedule.status = "needs_attention"
                schedule.lastError = RecurringPayment.amountLimitMessage
                schedules[id] = schedule
            }
            let binding = try state["nativeWalletBinding"].map {
                try JSONDecoder().decode(Binding.self, from: JSONSerialization.data(withJSONObject: $0))
            }
            return State(schedules: schedules, pending: state["serverSyncPending"] as? Bool ?? true, binding: binding)
        }
    }
    private func change(_ mutate: (inout [String: Any]) throws -> Void) throws {
        try lock.withLock {
            _ = try load()
            var envelope = try envelope()
            var state = envelope["state"] as! [String: Any]
            try mutate(&state)
            envelope["state"] = state
            let raw = String(decoding: try JSONSerialization.data(withJSONObject: envelope, options: [.sortedKeys]), as: UTF8.self)
            do { try write(raw) }
            catch { writeFailed = true; throw error }
        }
    }
    func bind(_ binding: Binding) throws {
        try change { state in
            state["nativeWalletBinding"] = try JSONSerialization.jsonObject(with: JSONEncoder().encode(binding))
            state["serverSyncPending"] = true
        }
    }
    func upsert(_ schedule: RecurringPayment) throws {
        try change { state in
            var schedules = state["schedules"] as! [String: Any]
            // Merge known fields, retaining unknown RN/native fields on both the envelope and schedule.
            var value = schedules[schedule.id] as? [String: Any] ?? [:]
            let fields = try JSONSerialization.jsonObject(with: JSONEncoder().encode(schedule)) as! [String: Any]
            for key in ["endAt", "maxOccurrences", "nextRunAt", "lastError", "retryNotBefore", "inFlight", "nativeRevision"] { value[key] = NSNull() }
            value.merge(fields) { _, new in new }
            schedules[schedule.id] = value
            state["schedules"] = schedules
            state["serverSyncPending"] = true
        }
    }
    func remove(_ id: String) throws {
        try change { state in
            var schedules = state["schedules"] as! [String: Any]
            schedules.removeValue(forKey: id)
            state["schedules"] = schedules; state["serverSyncPending"] = true
        }
    }
    func setPending(_ pending: Bool) throws { try change { $0["serverSyncPending"] = pending } }

    /// Legacy RN policies have no wallet identity. Before native creation/restore, seal them
    /// to an unverified old instance so a replacement can never silently adopt them.
    func protectLegacyBeforeReplacement() throws {
        let state = try load()
        if state.binding == nil, !state.schedules.isEmpty {
            try bind(.init(wallet: .init(fingerprint: "unverified-previous-wallet", network: "unknown"), instance: UUID()))
        }
    }

    /// A restore replaces the datadir, including this backup-excluded token. Even a same-seed
    /// restore cannot inherit approval to spend from the previous local wallet instance.
    static func binding(location: WalletDataLocation) throws -> Binding {
        guard !location.restorePending, !FileManager.default.fileExists(atPath: location.deletionMarker.path) else { throw WalletSession.OpenError.restorePending }
        let properties = try WalletDatabase.properties(at: location.database)
        let file = location.directory.appending(path: ".noah-recurring-instance")
        let id: UUID
        if FileManager.default.fileExists(atPath: file.path) {
            guard let saved = UUID(uuidString: try String(contentsOf: file, encoding: .utf8)) else { throw RecurringError.message("Recurring wallet identity couldn't be read. Sending is stopped.") }
            id = saved
        } else {
            id = UUID()
            try durableWrite(Data(id.uuidString.utf8), to: file)
        }
        return .init(wallet: .init(fingerprint: properties.fingerprint, network: properties.network), instance: id)
    }
    static func durableWrite(_ data: Data, to file: URL) throws {
        try data.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        var excluded = file
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try excluded.setResourceValues(values)
        try RestoreTransaction.fullSync(file)
        try RestoreTransaction.fullSync(file.deletingLastPathComponent())
    }
}
