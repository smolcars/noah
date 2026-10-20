import Darwin
import Foundation

/// Keeps RN's version-0 envelope and unknown fields. Corruption is never treated as an empty store.
final class RecurringStore: @unchecked Sendable {
    static let key = "recurring-payment-storage"
    // createRecurringPayment in client/src/lib/recurringSchedule.ts (retryNotBefore is optional).
    private static let requiredRNFields = ["id", "label", "destination", "destinationType", "amountSat", "comment",
        "interval", "startAt", "endAt", "maxOccurrences", "status", "nextOccurrenceIndex", "nextRunAt",
        "occurrencesPaid", "consecutiveFailures", "lastError", "inFlight", "runs", "createdAt", "updatedAt"]
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
    private func protected(_ state: State, automationOverlay: Bool = true) -> State {
        guard let ledger = policyLedger else { return state }
        let stopped: Set<String>
        let automationStopped: Bool
        do {
            // Welcome has no wallet authority to bootstrap or mark failed.
            let owner = try policyBinding!()
            guard FileManager.default.fileExists(atPath: ledger.file.deletingLastPathComponent().path),
                  try policyBinding!() == owner else { throw RecurringExecutor.NoWallet() }
            stopped = ledger.stopped(binding: state.binding ?? owner, schedules: state.schedules)
            automationStopped = automationOverlay && automation?.allowed() == false
        } catch is RecurringExecutor.NoWallet {
            stopped = Set(state.schedules.keys); automationStopped = false
        } catch {
            AppLog("recurring").w(RecurringPolicyLedger.logMessage)
            stopped = Set(state.schedules.keys); automationStopped = false
        }
        var visible = state
        for id in state.schedules.keys where (stopped.contains(id) || automationStopped) && visible.schedules[id]?.unsupportedStorage == false {
            visible.schedules[id]?.status = "needs_attention"
            visible.schedules[id]?.lastError = stopped.contains(id) ? RecurringPolicyLedger.message : RecurringAutomationAuthority.message
            visible.schedules[id]?.issue = .init(code: stopped.contains(id) ? .policyEvidence : .automationStopped)
            visible.schedules[id]?.stopReason = stopped.contains(id) ? .policyEvidence : .automationStopped
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
    private static func payment(id: String, raw: String, migrateIssue: Bool = true) -> RecurringPayment {
        let decoded = try? { () throws -> RecurringPayment in
            try RawObject.requireUniqueProperties(raw)
            try Self.requireNumericTokens(raw)
            return try JSONDecoder().decode(RecurringPayment.self, from: Data(raw.utf8))
        }()
        var schedule: RecurringPayment
        if let decoded, let fields = try? RawObject(raw), requiredRNFields.allSatisfy({ fields.fields[$0] != nil }),
           decoded.id == id, decoded.validPolicy {
            schedule = decoded
            if migrateIssue { schedule.decodeLegacyIssue() }
        } else {
            let fields = (try? JSONSerialization.jsonObject(with: Data(raw.utf8))) as? [String: Any]
            // Display only: the original row remains authoritative and is never encoded from this placeholder.
            schedule = decoded ?? RecurringPayment(id: id, label: fields?["label"] as? String ?? "Saved recurring payment",
                destination: fields?["destination"] as? String ?? "", destinationType: fields?["destinationType"] as? String ?? "",
                amountSat: (try? RawObject(raw)).flatMap { $0.fields["amountSat"] }
                    .flatMap { UInt64($0.trimmingCharacters(in: .whitespacesAndNewlines)) } ?? 0, comment: fields?["comment"] as? String ?? "",
                interval: .init(unit: "month", every: 1), startAt: 0, createdAt: 0, updatedAt: 0)
            schedule.id = id
            schedule.unsupportedStorage = true
            schedule.status = "needs_attention"
            schedule.lastError = RecurringPayment.unsupportedMessage
            schedule.issue = .init(code: .unsupported)
        }
        if !schedule.unsupportedStorage, schedule.amountSat > RecurringPayment.maximumAmount {
            schedule.status = "needs_attention"
            schedule.lastError = RecurringPayment.amountLimitMessage
            schedule.issue = .init(code: .amountLimit)
        }
        return schedule
    }
    private static func requireNumericTokens(_ raw: String) throws {
        func number(_ token: String, _ minimum: Int64, _ maximum: Int64) throws {
            // Int64 parses the original token exactly; Double/NSNumber may round before validation.
            guard let value = Int64(token.trimmingCharacters(in: .whitespacesAndNewlines)),
                  value >= minimum, value <= maximum else { throw RecurringError.message(RecurringPayment.unsupportedMessage) }
        }
        func numbers(_ fields: [String: String], _ minimum: Int64, _ maximum: Int64, _ keys: [String]) throws {
            for key in keys {
                if let token = fields[key], token.trimmingCharacters(in: .whitespacesAndNewlines) != "null" {
                    try number(token, minimum, maximum)
                }
            }
        }
        let row = try RawObject(raw).fields
        guard requiredRNFields.allSatisfy({ row[$0] != nil }) else { throw RecurringError.message(RecurringPayment.unsupportedMessage) }
        let safeInteger: Int64 = 9_007_199_254_740_991
        let timeLimit: Int64 = 8_639_999_999_999_999
        try numbers(row, 1, safeInteger, ["amountSat"])
        try numbers(row, 1, Int64.max, ["maxOccurrences"])
        try numbers(row, -timeLimit, timeLimit, ["startAt", "endAt", "nextRunAt", "retryNotBefore", "createdAt", "updatedAt"])
        try numbers(row, 0, Int64(Int32.max), ["nextOccurrenceIndex", "occurrencesPaid", "consecutiveFailures"])
        let interval = try RawObject(row["interval"]!).fields
        guard let rawUnit = interval["unit"], let every = interval["every"] else { throw RecurringError.message(RecurringPayment.unsupportedMessage) }
        let unit = try JSONDecoder().decode(String.self, from: Data(rawUnit.utf8))
        try number(every, 1, unit == "day" ? 365 : 12)
        if let flight = row["inFlight"], flight.trimmingCharacters(in: .whitespacesAndNewlines) != "null" {
            let fields = try RawObject(flight).fields
            try numbers(fields, 0, Int64(Int32.max), ["occurrenceIndex"])
            try numbers(fields, -timeLimit, timeLimit, ["startedAt"])
        }
        for run in try RawObject.arrayObjects(row["runs"]!) {
            try numbers(run.fields, 0, Int64(Int32.max), ["occurrenceIndex"])
            try numbers(run.fields, -timeLimit, timeLimit, ["scheduledFor", "attemptedAt"])
            try numbers(run.fields, 1, safeInteger, ["amountSat"])
        }
    }
    private func rawState(migrateIssue: Bool = true) throws -> State {
        try settings.requireHealthy()
        return try lock.withLock {
            guard !writeFailed else { throw RecurringError.message("Recurring payment storage failed. Restart Noah; sending is stopped.") }
            let state = try RawObject(envelope().fields["state"]!)
            let rows = try RawObject(state.fields["schedules"]!)
            let schedules = Dictionary(uniqueKeysWithValues: rows.fields.map { (id, raw) in (id, Self.payment(id: id, raw: raw, migrateIssue: migrateIssue)) })
            let binding = try state.fields["nativeWalletBinding"].map {
                try JSONDecoder().decode(Binding.self, from: Data($0.utf8))
            }
            let pending = state.fields["serverSyncPending"].flatMap { try? JSONDecoder().decode(Bool.self, from: Data($0.utf8)) } ?? true
            return State(schedules: schedules, pending: pending, binding: binding)
        }
    }
    func load() throws -> State { try lock.withLock { try recoverSettlements(); return protected(try rawState()) } }
    /// Record submitted money against policy authority, without a temporary wallet display overlay.
    func settlementState() throws -> State { try lock.withLock { try recoverSettlements(); return protected(try rawState(), automationOverlay: false) } }
    private func recoverSettlements() throws {
        guard let ledger = policyLedger else { return }
        func replay() throws {
            guard ledger.hasPending else { return }
            let before = try rawState(migrateIssue: false), owner = try policyBinding!()
            guard before.binding == nil || before.binding == owner else { throw RecurringError.message(RecurringPolicyLedger.message) }
            let pending = try ledger.pending(binding: before.binding ?? owner, schedules: before.schedules)
            guard !pending.isEmpty else { return }
            var envelope = try envelope(), state = try RawObject(envelope.fields["state"]!)
            var rows = try RawObject(state.fields["schedules"]!)
            for row in pending {
                var value = try RawObject(rows.fields[row.id]!)
                let encoded = try RawObject(String(decoding: JSONEncoder().encode(row), as: UTF8.self))
                for key in ["endAt", "maxOccurrences", "nextRunAt", "lastError", "issue", "retryNotBefore", "inFlight", "nativeRevision"] { value.fields[key] = "null" }
                value.fields.merge(encoded.fields) { _, new in new }
                rows.fields[row.id] = try value.json()
            }
            state.fields["schedules"] = try rows.json(); state.fields["serverSyncPending"] = "true"
            envelope.fields["state"] = try state.json()
            do { try write(envelope.json()) } catch { writeFailed = true; throw error }
            try ledger.finish(binding: before.binding ?? owner, schedules: rawState(migrateIssue: false).schedules)
        }
        try replay()
        // Validate and finish original records before reason codes change their representation.
        let before = try rawState(migrateIssue: false)
        guard let owner = try? policyBinding!(), before.binding == nil || before.binding == owner else { return }
        if try ledger.migrate(binding: before.binding ?? owner, schedules: before.schedules) { try replay() }
    }
    func settlementApplied(_ id: String, token: String) throws -> Bool {
        try lock.withLock {
            try recoverSettlements()
            let state = try rawState()
            return try policyLedger?.applied(binding: state.binding ?? policyBinding!(), schedules: state.schedules, id: id, token: token) == true
        }
    }
    private func change(policyID: String? = nil, reapproval: Bool = false, settlementKey: String? = nil, _ mutate: (inout [String: String]) throws -> Void) throws {
        try lock.withLock {
            try recoverSettlements()
            let before = try rawState()
            var envelope = try envelope()
            var state = try RawObject(envelope.fields["state"]!)
            try mutate(&state.fields)
            envelope.fields["state"] = try state.json()
            let raw = try envelope.json()
            let fields = try RawObject(state.fields["schedules"]!)
            let after = Dictionary(uniqueKeysWithValues: fields.fields.map { (id, raw) in (id, Self.payment(id: id, raw: raw)) })
            try policyLedger?.commit(binding: before.binding ?? policyBinding!(), before: before.schedules, after: after, policyID: policyID, reapproval: reapproval, settlementKey: settlementKey)
            do { try write(raw) }
            catch { writeFailed = true; throw error }
            if settlementKey != nil { try policyLedger?.finish(binding: before.binding ?? policyBinding!(), schedules: after) }
        }
    }
    func bind(_ binding: Binding) throws {
        try change { state in
            state["nativeWalletBinding"] = String(decoding: try JSONEncoder().encode(binding), as: UTF8.self)
            state["serverSyncPending"] = "true"
        }
    }
    func upsert(_ schedule: RecurringPayment, reapproval: Bool = false, settlementKey: String? = nil) throws {
        try change(policyID: schedule.unsupportedStorage ? nil : schedule.id, reapproval: reapproval, settlementKey: settlementKey) { state in
            var schedules = try RawObject(state["schedules"]!)
            let previous = schedules.fields[schedule.id]
            if schedule.unsupportedStorage || previous.map({ Self.payment(id: schedule.id, raw: $0).unsupportedStorage }) == true {
                guard previous != nil else { throw RecurringError.message(RecurringPayment.unsupportedMessage) }
                return
            }
            // Merge known fields, retaining unknown RN/native fields and their original JSON tokens.
            var value = try RawObject(previous ?? "{}")
            let fields = try RawObject(String(decoding: JSONEncoder().encode(schedule), as: UTF8.self))
            for key in ["endAt", "maxOccurrences", "nextRunAt", "lastError", "issue", "retryNotBefore", "inFlight", "nativeRevision"] { value.fields[key] = "null" }
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
    /// Inspect original keys before Foundation/JSONDecoder choose different duplicate values.
    static func requireUniqueProperties(_ raw: String) throws {
        let bytes = Array(raw.utf8)
        var objects: [Set<String>?] = []
        var index = 0
        while index < bytes.count {
            switch bytes[index] {
            case 123: objects.append([])
            case 91: objects.append(nil)
            case 125, 93: objects.removeLast()
            case 34:
                let start = index
                index += 1
                while bytes[index] != 34 { if bytes[index] == 92 { index += 1 }; index += 1 }
                index += 1
                let end = index
                while index < bytes.count, [9, 10, 13, 32].contains(bytes[index]) { index += 1 }
                if index < bytes.count, bytes[index] == 58 {
                    let key = try JSONDecoder().decode(String.self, from: Data(bytes[start..<end]))
                    guard objects[objects.count - 1]!.insert(key).inserted else {
                        throw RecurringError.message(RecurringPayment.unsupportedMessage)
                    }
                }
                continue
            default: break
            }
            index += 1
        }
    }
    static func arrayObjects(_ raw: String) throws -> [RawObject] {
        guard try JSONSerialization.jsonObject(with: Data(raw.utf8)) is [Any] else {
            throw RecurringError.message(RecurringPayment.unsupportedMessage)
        }
        let bytes = Array(raw.utf8)
        var start = bytes.firstIndex(of: 91)! + 1
        var depth = 0, quoted = false, escaped = false
        var result: [RawObject] = []
        for index in start..<bytes.count {
            let byte = bytes[index]
            if quoted {
                if escaped { escaped = false }
                else if byte == 92 { escaped = true }
                else if byte == 34 { quoted = false }
            } else {
                if depth == 0, byte == 44 || byte == 93 {
                    let value = String(decoding: bytes[start..<index], as: UTF8.self)
                    if !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { result.append(try RawObject(value)) }
                    start = index + 1
                    if byte == 93 { break }
                } else if byte == 34 { quoted = true }
                else if byte == 123 || byte == 91 { depth += 1 }
                else if byte == 125 || byte == 93 { depth -= 1 }
            }
        }
        return result
    }
    func json() throws -> String {
        let entries = try fields.keys.sorted().map { key in
            String(decoding: try JSONEncoder().encode(key), as: UTF8.self) + ":" + fields[key]!
        }
        return "{" + entries.joined(separator: ",") + "}"
    }
}
