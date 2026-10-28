import Foundation

/// Independent fsynced marker: MMKV and the Send receipt can both lag a killed process.
struct RecurringJournal {
    private static let lock = NSRecursiveLock()
    func withLock<T>(_ operation: () throws -> T) rethrows -> T { try Self.lock.withLock(operation) }

    struct Entry: Codable, Sendable {
        var binding: RecurringStore.Binding
        var scheduleID: String
        var plan: RecurringPayment.Plan
        var markedAt: Double
        var policyRevision: UUID?
        var resolved = false
        // swiftlint:disable discouraged_optional_boolean - journal files omit these until set; nil keeps that format
        var consumed: Bool?
        var policyApplied: Bool? // Legacy v2 claim; never used as the history completion flag.
        var terminal: TerminalEvidence?
        var historyApplied: Bool?
        var released: Bool?
        // swiftlint:enable discouraged_optional_boolean
        var policy: RecurringPayment?
        var key: String { "\(scheduleID):\(plan.due.index)" }
    }
    struct TerminalEvidence: Codable, Sendable {
        var settlementID: UUID
        var attempt: SendPaymentController.Attempt
        var generation: Int
        var walletGeneration: Int
        var outcome: TerminalOutcome
        var message: String?
        var recordedAt: Double
        var paid: Bool { outcome == .succeeded || outcome == .alreadyPaid }
    }
    private struct Scope: Codable, Equatable {
        var binding: RecurringStore.Binding
        var scheduleID: String
    }
    private struct Watermark: Codable {
        var binding: RecurringStore.Binding
        var scheduleID: String
        var through: Double
    }
    private struct ConsumedIndices: Codable {
        var binding: RecurringStore.Binding
        var scheduleID: String
        var policyRevision: UUID?
        var indices: [Int]
    }
    private struct State: Codable {
        var version = 3
        var attempts: [String: Entry] = [:]
        var consumed: [Watermark] = []
        var consumedIndices: [ConsumedIndices] = []
        var legacyUncertainty: [Scope] = []

        mutating func remember(_ entry: Entry) throws {
            guard entry.consumed == true else { return }
            guard let at = entry.plan.scheduledFor, at.isFinite,
                  let occurrence = entry.plan.occurrenceIndex, occurrence >= 0 else {
                throw RecurringError.message("Recurring consumption evidence is incomplete. Sending remains stopped.")
            }
            if let index = consumed.firstIndex(where: { $0.binding == entry.binding && $0.scheduleID == entry.scheduleID }) {
                consumed[index].through = max(consumed[index].through, at)
            } else {
                consumed.append(.init(binding: entry.binding, scheduleID: entry.scheduleID, through: at))
            }
            if let index = consumedIndices.firstIndex(where: {
                $0.binding == entry.binding && $0.scheduleID == entry.scheduleID && $0.policyRevision == entry.policyRevision
            }) {
                consumedIndices[index].indices = Array(Set(consumedIndices[index].indices + [occurrence])).sorted()
            } else {
                consumedIndices.append(.init(binding: entry.binding, scheduleID: entry.scheduleID,
                                             policyRevision: entry.policyRevision, indices: [occurrence]))
            }
        }
    }
    static let legacyReason = RecurringIssue(code: .legacyHistory)
    static let legacyMessage = "Noah can't reconstruct this schedule's earlier payment history. Cancel it and explicitly approve a new recurring payment."
    static let invalidMessage = "Recurring payment history needs attention. Its saved evidence has been preserved; sending remains stopped."
    var quarantineFile: URL { file.appendingPathExtension("quarantine") }

    private func validate(_ state: State, complete: Bool = true) throws {
        func require(_ valid: Bool) throws {
            guard valid else { throw RecurringError.message(Self.invalidMessage) }
        }
        func scope(_ binding: RecurringStore.Binding, _ id: String) throws {
            try require(!id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                && !binding.wallet.fingerprint.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                && !binding.wallet.network.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            // Binding.instance and policyRevision are decoded as UUIDs.
        }
        for (id, entry) in state.attempts {
            try scope(entry.binding, id)
            try require(id == entry.scheduleID && entry.plan.kind == "pay"
                && entry.plan.occurrenceIndex.map { $0 >= 0 } == true
                && entry.plan.scheduledFor?.isFinite == true && entry.markedAt.isFinite
                && (entry.consumed != true || entry.resolved) && (entry.policyApplied != true || entry.resolved))
            if let terminal = entry.terminal {
                try require(terminal.attempt.recurringOccurrenceKey == entry.key && terminal.attempt.walletIdentity == entry.binding.wallet
                    && terminal.generation >= 0 && terminal.walletGeneration >= 0 && terminal.recordedAt.isFinite)
                if terminal.outcome == .succeeded {
                    try require(terminal.attempt.receipt.map { !$0.isUnresolved && $0.broadcastRetryPending != true && $0.isProven(
                        for: terminal.attempt.payment,
                        knownHash: terminal.attempt.paymentHash) } == true)
                }
                if terminal.outcome == .alreadyPaid {
                    try require(terminal.attempt.alreadyPaid.map { $0.isProven(
                        for: terminal.attempt.payment,
                        knownHash: terminal.attempt.paymentHash,
                        alreadyPaid: true) } == true)
                }
                try require(terminal.outcome != .failed || terminal.attempt.failed)
            }
            try require(entry.historyApplied != true || entry.terminal != nil && entry.resolved)
            try require(entry.released != true || entry.historyApplied == true)
            if complete, entry.consumed == true {
                try require(state.consumed.contains { $0.binding == entry.binding && $0.scheduleID == id && $0.through >= entry.plan.due.at }
                    && state.consumedIndices.contains { $0.binding == entry.binding && $0.scheduleID == id
                        && $0.policyRevision == entry.policyRevision && $0.indices.contains(entry.plan.due.index)
                    })
            }
        }
        for watermark in state.consumed {
            try scope(watermark.binding, watermark.scheduleID)
            try require(watermark.through.isFinite)
        }
        for indices in state.consumedIndices {
            try scope(indices.binding, indices.scheduleID)
            try require(!indices.indices.isEmpty && indices.indices.allSatisfy { $0 >= 0 }
                && indices.indices == Array(Set(indices.indices)).sorted())
        }
        for uncertain in state.legacyUncertainty { try scope(uncertain.binding, uncertain.scheduleID) }
    }
    private struct Header: Decodable { var version: Int }
    private struct Version1: Decodable { var attempts: [String: Entry]; var consumed: [Watermark] }
    var file: URL
    var persist: @Sendable (Data, URL) throws -> Void = { try RecurringStore.durableWrite($0, to: $1) }
    private func state() throws -> State {
        guard !FileManager.default.fileExists(atPath: quarantineFile.path) else { throw RecurringError.message(Self.invalidMessage) }
        guard FileManager.default.fileExists(atPath: file.path) else { return State() }
        let bytes = try Data(contentsOf: file)
        do { return try decode(bytes) }
        catch {
            // Preserve the original and the first quarantine copy, including across relaunch.
            if !FileManager.default.fileExists(atPath: quarantineFile.path) {
                try RecurringStore.durableWrite(bytes, to: quarantineFile)
            }
            throw RecurringError.message(Self.invalidMessage)
        }
    }
    private func decode(_ bytes: Data) throws -> State {
        let decoder = JSONDecoder()
        var migrated: State
        if let header = try? decoder.decode(Header.self, from: bytes) {
            switch header.version {
            case 3:
                let decoded = try decoder.decode(State.self, from: bytes)
                try validate(decoded)
                return decoded
            case 2:
                migrated = try decoder.decode(State.self, from: bytes)
                migrated.version = 3
                // Old consumption claimed history before writing it: preserve it, never guess.
                migrated.legacyUncertainty += migrated.attempts.values.filter { $0.resolved || $0.consumed == true || $0.policyApplied == true }.map { Scope(
                    binding: $0.binding,
                    scheduleID: $0.scheduleID) }
            case 1:
                let old = try decoder.decode(Version1.self, from: bytes)
                migrated = State(attempts: old.attempts, consumed: old.consumed)
                // Watermarks can cover overwritten revisions whose indices are unknowable.
                migrated.legacyUncertainty = old.consumed.map { Scope(binding: $0.binding, scheduleID: $0.scheduleID) }
            default: throw RecurringError.message("Unsupported recurring journal version. Sending remains stopped.")
            }
        } else {
            let entries = try decoder.decode([String: Entry].self, from: bytes)
            migrated = State(attempts: entries)
            // Even a legacy failed attempt may have replaced earlier consumed evidence.
            migrated.legacyUncertainty = entries.values.map { Scope(binding: $0.binding, scheduleID: $0.scheduleID) }
        }
        try validate(migrated, complete: false)
        for entry in migrated.attempts.values { try migrated.remember(entry) }
        try write(migrated)
        return migrated
    }
    /// Durable handoff before Send publishes a terminal result.
    /// The Send controller's generations when the attempt finished.
    struct Generations { var generation: Int; var walletGeneration: Int }
    func record(_ attempt: SendPaymentController.Attempt, generations: Generations,
                message: String?, binding: RecurringStore.Binding, now: Double) throws {
        let generation = generations.generation, walletGeneration = generations.walletGeneration
        Self.lock.lock(); defer { Self.lock.unlock() }
        let outcome: TerminalOutcome
        if attempt.alreadyPaid != nil { outcome = .alreadyPaid }
        else if let receipt = attempt.receipt, !receipt.isUnresolved, receipt.broadcastRetryPending != true { outcome = .succeeded }
        else if attempt.failed { outcome = .failed }
        else { outcome = .uncertain }
        guard let key = attempt.recurringOccurrenceKey,
              var entry = try load().values.first(where: { $0.key == key && $0.binding == binding }),
              attempt.walletIdentity == binding.wallet else { throw RecurringError.message(Self.invalidMessage) }
        guard try !legacyUncertain(binding: binding, scheduleID: entry.scheduleID) else { throw RecurringError.message(Self.legacyMessage) }
        if let old = entry.terminal {
            guard old.attempt.recurringAttemptID == attempt.recurringAttemptID else { throw RecurringError.message(Self.invalidMessage) }
            if old.outcome != .uncertain { return }
            let encoder = JSONEncoder(); encoder.outputFormatting = .sortedKeys
            if old.outcome == outcome, try encoder.encode(old.attempt) == encoder.encode(attempt) { return }
        }
        entry.terminal = .init(
            settlementID: entry.terminal?.settlementID ?? UUID(),
            attempt: attempt,
            generation: generation,
            walletGeneration: walletGeneration,
            outcome: outcome,
            message: message,
            recordedAt: now)
        try save(entry)
    }
    func load() throws -> [String: Entry] { try withLock { try state().attempts } }
    func legacyUncertain(binding: RecurringStore.Binding, scheduleID: String) throws -> Bool {
        try withLock { try state().legacyUncertainty.contains(.init(binding: binding, scheduleID: scheduleID)) }
    }
    func consumed(binding: RecurringStore.Binding, scheduleID: String, at: Double,
                  policyRevision: UUID?, occurrenceIndex: Int) throws -> Bool {
        Self.lock.lock(); defer { Self.lock.unlock() }
        let state = try state()
        return state.legacyUncertainty.contains(.init(binding: binding, scheduleID: scheduleID))
            || state.consumed.contains { $0.binding == binding && $0.scheduleID == scheduleID && at <= $0.through }
            || state.consumedIndices.contains {
                $0.binding == binding && $0.scheduleID == scheduleID && $0.policyRevision == policyRevision && $0.indices.contains(occurrenceIndex)
            }
    }
    private func write(_ state: State) throws {
        try validate(state)
        try persist(JSONEncoder().encode(state), file)
    }
    func save(_ entry: Entry) throws {
        Self.lock.lock(); defer { Self.lock.unlock() }
        var state = try state()
        state.attempts[entry.scheduleID] = entry
        try validate(state, complete: false)
        try state.remember(entry)
        try write(state)
    }
}

extension RecurringJournal {
    // swiftlint:disable:next raw_value_for_camel_cased_codable_enum - case names are the wire strings; SwiftFormat strips equal raw values
    enum TerminalOutcome: String, Codable, Sendable { case succeeded, alreadyPaid, failed, uncertain }
}
