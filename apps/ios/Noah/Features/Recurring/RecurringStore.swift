import Darwin
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
    private var policyLedger: RecurringPolicyLedger?
    private var automation: RecurringAutomationAuthority?
    private var policyBinding: (@Sendable () throws -> Binding)?
    func protectPolicies(file: URL, sync: @escaping @Sendable (URL) throws -> Void = RestoreTransaction.fullSync, binding: @escaping @Sendable () throws -> Binding) {
        lock.withLock {
            if policyLedger == nil { policyLedger = RecurringPolicyLedger(file: file, sync: sync); policyBinding = binding; automation = RecurringAutomationAuthority(directory: file.deletingLastPathComponent(), sync: sync) }
        }
    }
    private func protected(_ state: State) -> State {
        guard let ledger = policyLedger else { return state }
        let stopped: Set<String>
        do { stopped = ledger.stopped(binding: try state.binding ?? policyBinding!(), schedules: state.schedules) }
        catch { AppLog("recurring").w(RecurringPolicyLedger.logMessage); stopped = Set(state.schedules.keys) }
        let automationStopped = automation?.allowed() == false
        var visible = state
        for id in state.schedules.keys where (stopped.contains(id) || automationStopped) && visible.schedules[id]?.unsupportedStorage == false {
            visible.schedules[id]?.status = "needs_attention"
            visible.schedules[id]?.lastError = stopped.contains(id) ? RecurringPolicyLedger.message : RecurringAutomationAuthority.message
        }
        return visible
    }
    init(settings: SettingsStorage = .shared, write: (@Sendable (String) throws -> Void)? = nil) {
        self.settings = settings
        self.write = write ?? { try settings.setDurably($0, forKey: Self.key) }
    }
    func automationGeneration() throws -> UUID? { try lock.withLock { try automation?.generation() } }
    func checkAutomationAllowed() throws {
        _ = try automationGeneration()
        try settings.requireHealthy()
        try lock.withLock {
            guard !writeFailed, WalletLifecycle.automationAllowed(settings: settings) else {
                throw RecurringError.message("Recurring payment storage failed. Restart Noah; sending is stopped.")
            }
        }
    }

    private func envelope() throws -> RawObject {
        let raw = settings.string(forKey: Self.key) ?? #"{"version":0,"state":{"schedules":{},"serverSyncPending":false}}"#
        return try Self.decodeEnvelope(raw)
    }
    static func decodeEnvelope(_ raw: String) throws -> RawObject {
        let value = try RawObject(raw)
        let version = value.fields["version"]?.trimmingCharacters(in: .whitespacesAndNewlines)
        guard version == nil || version == "0",
              let stateJSON = value.fields["state"], let state = try? RawObject(stateJSON),
              let schedules = state.fields["schedules"], (try? RawObject(schedules)) != nil
        else { throw RecurringError.message("Recurring payment data couldn't be read. Your saved schedules have been preserved. Sending is stopped.") }
        return value
    }
    private static func payment(id: String, raw: String) -> RecurringPayment {
        let decoded = try? JSONDecoder().decode(RecurringPayment.self, from: Data(raw.utf8))
        var schedule: RecurringPayment
        if let decoded, decoded.id == id, decoded.validPolicy {
            schedule = decoded
        } else {
            let fields = (try? JSONSerialization.jsonObject(with: Data(raw.utf8))) as? [String: Any]
            // Display only: the original row remains authoritative and is never encoded from this placeholder.
            schedule = decoded ?? RecurringPayment(id: id, label: fields?["label"] as? String ?? "Saved recurring payment",
                destination: fields?["destination"] as? String ?? "", destinationType: fields?["destinationType"] as? String ?? "",
                amountSat: (fields?["amountSat"] as? NSNumber)?.uint64Value ?? 0, comment: fields?["comment"] as? String ?? "",
                interval: .init(unit: "month", every: 1), startAt: 0, createdAt: 0, updatedAt: 0)
            schedule.id = id
            schedule.unsupportedStorage = true
            schedule.status = "needs_attention"
            schedule.lastError = RecurringPayment.unsupportedMessage
        }
        if !schedule.unsupportedStorage, schedule.amountSat > RecurringPayment.maximumAmount {
            schedule.status = "needs_attention"
            schedule.lastError = RecurringPayment.amountLimitMessage
        }
        return schedule
    }
    private func rawState() throws -> State {
        try settings.requireHealthy()
        return try lock.withLock {
            guard !writeFailed else { throw RecurringError.message("Recurring payment storage failed. Restart Noah; sending is stopped.") }
            let state = try RawObject(envelope().fields["state"]!)
            let rows = try RawObject(state.fields["schedules"]!)
            let schedules = Dictionary(uniqueKeysWithValues: rows.fields.map { (id, raw) in (id, Self.payment(id: id, raw: raw)) })
            let binding = try state.fields["nativeWalletBinding"].map {
                try JSONDecoder().decode(Binding.self, from: Data($0.utf8))
            }
            let pending = state.fields["serverSyncPending"].flatMap { try? JSONDecoder().decode(Bool.self, from: Data($0.utf8)) } ?? true
            return State(schedules: schedules, pending: pending, binding: binding)
        }
    }
    func load() throws -> State { try lock.withLock { protected(try rawState()) } }
    private func change(policyID: String? = nil, reapproval: Bool = false, _ mutate: (inout [String: String]) throws -> Void) throws {
        try lock.withLock {
            let before = try rawState()
            var envelope = try envelope()
            var state = try RawObject(envelope.fields["state"]!)
            try mutate(&state.fields)
            envelope.fields["state"] = try state.json()
            let raw = try envelope.json()
            let fields = try RawObject(state.fields["schedules"]!)
            let after = Dictionary(uniqueKeysWithValues: fields.fields.map { (id, raw) in (id, Self.payment(id: id, raw: raw)) })
            try policyLedger?.commit(binding: before.binding ?? policyBinding!(), before: before.schedules, after: after, policyID: policyID, reapproval: reapproval)
            do { try write(raw) }
            catch { writeFailed = true; throw error }
        }
    }
    func bind(_ binding: Binding) throws {
        try change { state in
            state["nativeWalletBinding"] = String(decoding: try JSONEncoder().encode(binding), as: UTF8.self)
            state["serverSyncPending"] = "true"
        }
    }
    func upsert(_ schedule: RecurringPayment, reapproval: Bool = false) throws {
        try change(policyID: schedule.unsupportedStorage ? nil : schedule.id, reapproval: reapproval) { state in
            var schedules = try RawObject(state["schedules"]!)
            let previous = schedules.fields[schedule.id]
            if schedule.unsupportedStorage || previous.map({ Self.payment(id: schedule.id, raw: $0).unsupportedStorage }) == true {
                guard previous != nil else { throw RecurringError.message(RecurringPayment.unsupportedMessage) }
                return
            }
            // Merge known fields, retaining unknown RN/native fields and their original JSON tokens.
            var value = try RawObject(previous ?? "{}")
            let fields = try RawObject(String(decoding: JSONEncoder().encode(schedule), as: UTF8.self))
            for key in ["endAt", "maxOccurrences", "nextRunAt", "lastError", "retryNotBefore", "inFlight", "nativeRevision"] { value.fields[key] = "null" }
            value.fields.merge(fields.fields) { _, new in new }
            schedules.fields[schedule.id] = try value.json()
            state["schedules"] = try schedules.json()
            state["serverSyncPending"] = "true"
        }
    }
    func remove(_ id: String) throws {
        try change(policyID: id) { state in
            var schedules = try RawObject(state["schedules"]!)
            schedules.fields.removeValue(forKey: id)
            state["schedules"] = try schedules.json(); state["serverSyncPending"] = "true"
        }
    }
    func setPending(_ pending: Bool) throws { try change { $0["serverSyncPending"] = pending ? "true" : "false" } }

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
    static func durableWrite(_ data: Data, to file: URL, sync: @Sendable (URL) throws -> Void = RestoreTransaction.fullSync) throws {
        let temporary = file.deletingLastPathComponent().appending(path: ".recurring-\(UUID())")
        defer { try? FileManager.default.removeItem(at: temporary) }
        try data.write(to: temporary, options: .completeFileProtectionUntilFirstUserAuthentication)
        var excluded = temporary
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try excluded.setResourceValues(values)
        try sync(temporary)
        guard Darwin.rename(temporary.path, file.path) == 0 else { throw RestoreTransaction.Failure.syncFailed(errno) }
        try sync(file.deletingLastPathComponent())
    }
}

/// Foundation validates the JSON; slicing values avoids rounding wide RN numbers on unrelated writes.
struct RawObject {
    var fields: [String: String] = [:]
    init(_ raw: String) throws {
        guard try JSONSerialization.jsonObject(with: Data(raw.utf8)) is [String: Any] else {
            throw RecurringError.message("Recurring payment data couldn't be read. Your saved schedules have been preserved. Sending is stopped.")
        }
        let bytes = Array(raw.utf8)
        var index = bytes.firstIndex(of: 123)! + 1
        func whitespace() { while index < bytes.count, [9, 10, 13, 32].contains(bytes[index]) { index += 1 } }
        whitespace()
        while bytes[index] != 125 {
            let keyStart = index
            index += 1
            while bytes[index] != 34 {
                if bytes[index] == 92 { index += 1 }
                index += 1
            }
            index += 1
            let key = try JSONDecoder().decode(String.self, from: Data(bytes[keyStart..<index]))
            whitespace(); index += 1; whitespace() // colon
            let valueStart = index
            var depth = 0, quoted = false, escaped = false
            while index < bytes.count {
                let byte = bytes[index]
                if quoted {
                    if escaped { escaped = false }
                    else if byte == 92 { escaped = true }
                    else if byte == 34 { quoted = false }
                } else {
                    if depth == 0, byte == 44 || byte == 125 { break }
                    if byte == 34 { quoted = true }
                    else if byte == 123 || byte == 91 { depth += 1 }
                    else if byte == 125 || byte == 93 { depth -= 1 }
                }
                index += 1
            }
            fields[key] = String(decoding: bytes[valueStart..<index], as: UTF8.self)
            if bytes[index] == 44 { index += 1; whitespace() }
        }
    }
    func json() throws -> String {
        let entries = try fields.keys.sorted().map { key in
            String(decoding: try JSONEncoder().encode(key), as: UTF8.self) + ":" + fields[key]!
        }
        return "{" + entries.joined(separator: ",") + "}"
    }
}
