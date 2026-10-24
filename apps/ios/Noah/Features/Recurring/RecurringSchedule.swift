import Foundation

/// RN timestamps are milliseconds and all occurrences use the device's current zone.
struct RecurringInterval: Codable, Equatable, Sendable {
    var unit: String
    var every: Int
    var description: String {
        if every == 1 { return unit == "day" ? "Daily" : unit == "week" ? "Weekly" : "Monthly" }
        return "Every \(every) \(unit)s"
    }
    /// The same, translated: "Monthly", "Every 2 weeks".
    func text(locale: String) -> String {
        if every == 1 { return L10n.text(description, locale: locale) }
        switch unit {
        case "day": return L10n.text("Every {n} days", every, locale: locale)
        case "week": return L10n.plural("Every {n} weeks", count: every, locale: locale)
        default: return L10n.plural("Every {n} months", count: every, locale: locale)
        }
    }
    var valid: Bool { ["day", "week", "month"].contains(unit) && every >= 1 && every <= (unit == "day" ? 365 : 12) }
}

struct RecurringPayment: Codable, Equatable, Identifiable, Sendable {
    struct InFlight: Codable, Equatable, Sendable { var occurrenceIndex: Int; var startedAt: Double }
    struct Run: Codable, Equatable, Sendable {
        var occurrenceIndex: Int
        var scheduledFor: Double
        var attemptedAt: Double
        var status: String
        var amountSat: UInt64
        var error: String?
    }
    var id: String
    var label: String
    var destination: String
    var destinationType: String
    var amountSat: UInt64
    var comment: String
    var interval: RecurringInterval
    var startAt: Double
    var endAt: Double?
    var maxOccurrences: Int?
    var nativeRevision: UUID?
    var status = "active"
    var nextOccurrenceIndex = 0
    var nextRunAt: Double?
    var occurrencesPaid = 0
    var consecutiveFailures = 0
    var lastError: String?
    var issue: RecurringIssue?
    var retryNotBefore: Double?
    var inFlight: InFlight?
    var runs: [Run] = []
    var createdAt: Double
    var updatedAt: Double
    var unsupportedStorage = false
    // Derived from the policy authority on load, never persisted with the RN schedule.
    enum StopReason: Sendable { case policyEvidence, automationStopped }
    var stopReason: StopReason?
    var requiresPolicyApproval: Bool { stopReason == .policyEvidence || issue?.code == .policyEvidence }

    enum CodingKeys: String, CodingKey {
        case id, label, destination, destinationType, amountSat, comment, interval, startAt, endAt, maxOccurrences, nativeRevision
        case issue
        case status, nextOccurrenceIndex, nextRunAt, occurrencesPaid, consecutiveFailures, lastError, retryNotBefore, inFlight, runs, createdAt, updatedAt
    }

    mutating func decodeLegacyIssue() {
        if issue == nil, let lastError { issue = .legacy(lastError) }
        if issue?.valid == false { issue = .init(code: .unknown) }
        if let code = issue?.code, ![.quoteExceedsCap, .scheduleExceedsCap, .arkFee, .retryableFailure].contains(code) {
            if status == "active" || status == "needs_approval" { status = "needs_attention"; retryNotBefore = nil }
        } else if issue != nil, issue?.code != .retryableFailure, status == "active" {
            status = "needs_approval"; retryNotBefore = nil
        }
    }
    static let unsupportedMessage = "Noah can't safely read this schedule. Cancel it and explicitly approve a new recurring payment."
    static let maximumAmount: UInt64 = 2_100_000_000_000_000
    static let amountLimitMessage = "This amount exceeds 21 million BTC. Cancel this schedule and approve a new payment within the limit."
    static var now: Double { floor(Date().timeIntervalSince1970 * 1000) }
    private var wholeMillisecondTimes: Bool {
        let times = [startAt, endAt, nextRunAt, createdAt, updatedAt, retryNotBefore, inFlight?.startedAt].compactMap { $0 }
            + runs.flatMap { [$0.scheduledFor, $0.attemptedAt] }
        return times.allSatisfy { $0.isFinite && $0.rounded(.towardZero) == $0 }
    }
    var validPolicy: Bool {
        !label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && label.utf16.count <= 64
            && amountSat > 0
            && ["ark", "lnurl", "offer"].contains(destinationType) && !destination.isEmpty
            && ["active", "paused", "needs_attention", "needs_approval", "completed"].contains(status)
            && wholeMillisecondTimes && interval.valid && startAt.isFinite && abs(startAt) < 8.64e15
            && (endAt == nil || (endAt!.isFinite && endAt! >= startAt))
            && (maxOccurrences == nil || maxOccurrences! >= 1) && nextOccurrenceIndex >= 0 && nextOccurrenceIndex <= 1_000_000
    }
    func validateInput(now: Double) throws {
        guard !unsupportedStorage, wholeMillisecondTimes else { throw RecurringError.message(Self.unsupportedMessage) }
        guard !label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, label.utf16.count <= 64 else { throw RecurringError.message("Give this payment a short name (max 64 characters).") }
        guard amountSat > 0, amountSat <= Self.maximumAmount else { throw RecurringError.message("Enter a whole amount in sats, up to 21 million BTC.") }
        guard ["ark", "lnurl", "offer"].contains(destinationType), !destination.isEmpty else { throw RecurringError.message("Enter an Ark address, Lightning address or BOLT12 offer.") }
        guard interval.valid else { throw RecurringError.message("Custom intervals must be between 1 and 365 days.") }
        guard startAt.isFinite, startAt >= now - 300_000 else { throw RecurringError.message("The first payment can't be in the past.") }
        guard endAt == nil || (endAt!.isFinite && endAt! >= startAt) else { throw RecurringError.message("The end date must be after the first payment.") }
        guard maxOccurrences == nil || maxOccurrences! >= 1 else { throw RecurringError.message("Enter how many payments should be made.") }
    }
    func occurrence(_ index: Int, zone: TimeZone = .current) -> Double {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        let start = Date(timeIntervalSince1970: startAt / 1000)
        var parts = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: start)
        let milliseconds = startAt - floor(startAt / 1000) * 1000
        let steps = index * interval.every
        if interval.unit == "month" {
            let totalMonths = parts.month! - 1 + steps
            parts.year! += totalMonths / 12
            parts.month = totalMonths % 12 + 1
            var first = parts
            first.day = 1
            let days = calendar.range(of: .day, in: .month, for: calendar.date(from: first)!)!.count
            parts.day = min(parts.day!, days)
        } else {
            parts.day! += steps * (interval.unit == "week" ? 7 : 1)
        }
        // Calendar's compatible resolution matches JS Date: first fold, forward by the gap.
        return (calendar.date(from: parts)!.timeIntervalSince1970 * 1000).rounded() + milliseconds
    }
    func next(_ index: Int, zone: TimeZone = .current) -> Double? {
        guard !unsupportedStorage else { return nil }
        if let maxOccurrences, index >= maxOccurrences { return nil }
        let at = occurrence(index, zone: zone)
        return endAt.map { at > $0 } == true ? nil : at
    }
    struct Plan: Codable, Equatable, Sendable {
        var kind: String
        var occurrenceIndex: Int?
        var scheduledFor: Double?
        var skippedIndices: [Int]?
    }
    func plan(now: Double, zone: TimeZone = .current, ignoreBackoff: Bool = false) -> Plan {
        guard !unsupportedStorage, status == "active" else { return .init(kind: "idle") }
        if let inFlight { return .init(kind: now - inFlight.startedAt > 600_000 ? "stale_in_flight" : "idle") }
        guard let first = next(nextOccurrenceIndex, zone: zone) else { return .init(kind: "complete") }
        guard first <= now, ignoreBackoff || (retryNotBefore ?? 0) <= now else { return .init(kind: "idle") }
        var latest = nextOccurrenceIndex
        for _ in 0..<5000 {
            guard let at = next(latest + 1, zone: zone), at <= now else { break }
            latest += 1
        }
        if let at = next(latest + 1, zone: zone), at <= now { return .init(kind: "too_many_missed") }
        return .init(kind: "pay", occurrenceIndex: latest, scheduledFor: occurrence(latest, zone: zone), skippedIndices: Array(nextOccurrenceIndex..<latest))
    }
    mutating func advance(_ index: Int, zone: TimeZone = .current) {
        nextOccurrenceIndex = index
        nextRunAt = next(index, zone: zone)
        if nextRunAt == nil { status = "completed" }
    }
    mutating func resume(now: Double, zone: TimeZone = .current) {
        status = "active"; inFlight = nil; retryNotBefore = nil
        if let index = plan(now: now, zone: zone, ignoreBackoff: true).occurrenceIndex { advance(index + 1, zone: zone) }
        lastError = nil; issue = nil; consecutiveFailures = 0; updatedAt = now
    }
    mutating func success(_ plan: Plan, now: Double, zone: TimeZone = .current) {
        let additions = (plan.skippedIndices ?? []).map { Run(occurrenceIndex: $0, scheduledFor: occurrence($0, zone: zone), attemptedAt: now, status: "skipped", amountSat: amountSat) }
            + [Run(occurrenceIndex: plan.occurrenceIndex!, scheduledFor: plan.scheduledFor!, attemptedAt: now, status: "success", amountSat: amountSat)]
        runs = Array((additions.reversed() + runs).prefix(24))
        occurrencesPaid += 1; consecutiveFailures = 0; lastError = nil; issue = nil; retryNotBefore = nil; inFlight = nil; updatedAt = now
        advance(plan.occurrenceIndex! + 1, zone: zone)
    }
    mutating func failure(_ plan: Plan, message: String, retryable: Bool, now: Double, issue: RecurringIssue? = nil) {
        inFlight = nil; consecutiveFailures += 1; lastError = message; self.issue = issue ?? .init(code: retryable ? .retryableFailure : .uncertain); updatedAt = now
        if !retryable { status = "needs_attention" }
        retryNotBefore = retryable ? now + min(900_000 * pow(2, Double(max(0, min(consecutiveFailures, 16) - 1))), 21_600_000) : nil
        runs = Array(([Run(occurrenceIndex: plan.occurrenceIndex!, scheduledFor: plan.scheduledFor!, attemptedAt: now, status: "failed", amountSat: amountSat, error: message)] + runs).prefix(24))
    }
}

enum RecurringError: LocalizedError {
    case message(String)
    var errorDescription: String? { switch self { case .message(let text): text } }
}

// Keep unsupported wide RN amounts visible without narrowing their stored value.
extension RecurringPayment {
    func formattedAmount(unit: BitcoinAmountUnit, locale: Locale) -> String {
        let number = amountSat.formatted(.number.locale(locale))
        return unit == .bip177 ? "₿\u{00A0}\(number)" : "\(number) sats"
    }
}
