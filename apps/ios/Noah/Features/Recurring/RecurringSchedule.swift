import Foundation

/// RN timestamps are milliseconds and all occurrences use the device's current zone.
struct RecurringInterval: Codable, Equatable, Sendable {
    var unit: String
    var every: Int
    var description: String {
        if every == 1 { return unit == "day" ? "Daily" : unit == "week" ? "Weekly" : "Monthly" }
        return "Every \(every) \(unit)s"
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
    var status = "active"
    var nextOccurrenceIndex = 0
    var nextRunAt: Double?
    var occurrencesPaid = 0
    var consecutiveFailures = 0
    var lastError: String?
    var retryNotBefore: Double?
    var inFlight: InFlight?
    var runs: [Run] = []
    var createdAt: Double
    var updatedAt: Double

    static var now: Double { Date().timeIntervalSince1970 * 1000 }
    var validPolicy: Bool {
        !label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && label.utf16.count <= 64
            && amountSat > 0 && amountSat <= 9_007_199_254_740_991
            && ["ark", "lnurl", "offer"].contains(destinationType) && !destination.isEmpty
            && interval.valid && startAt.isFinite && abs(startAt) < 8.64e15
            && (endAt == nil || (endAt!.isFinite && endAt! >= startAt))
            && (maxOccurrences == nil || maxOccurrences! >= 1) && nextOccurrenceIndex >= 0
    }
    func validateInput(now: Double) throws {
        guard !label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, label.utf16.count <= 64 else { throw RecurringError.message("Give this payment a short name (max 64 characters).") }
        guard amountSat > 0, amountSat <= 9_007_199_254_740_991 else { throw RecurringError.message("Enter a whole amount in sats.") }
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
        guard status == "active" else { return .init(kind: "idle") }
        if let inFlight { return .init(kind: now - inFlight.startedAt > 600_000 ? "stale_in_flight" : "idle") }
        guard let first = next(nextOccurrenceIndex, zone: zone) else { return .init(kind: "complete") }
        guard first <= now, ignoreBackoff || (retryNotBefore ?? 0) <= now else { return .init(kind: "idle") }
        var latest = nextOccurrenceIndex
        for _ in 0..<5000 {
            guard let at = next(latest + 1, zone: zone), at <= now else { break }
            latest += 1
        }
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
        lastError = nil; consecutiveFailures = 0; updatedAt = now
    }
    mutating func success(_ plan: Plan, now: Double, zone: TimeZone = .current) {
        let additions = (plan.skippedIndices ?? []).map { Run(occurrenceIndex: $0, scheduledFor: occurrence($0, zone: zone), attemptedAt: now, status: "skipped", amountSat: amountSat) }
            + [Run(occurrenceIndex: plan.occurrenceIndex!, scheduledFor: plan.scheduledFor!, attemptedAt: now, status: "success", amountSat: amountSat)]
        runs = Array((additions.reversed() + runs).prefix(24))
        occurrencesPaid += 1; consecutiveFailures = 0; lastError = nil; retryNotBefore = nil; inFlight = nil; updatedAt = now
        advance(plan.occurrenceIndex! + 1, zone: zone)
    }
    mutating func failure(_ plan: Plan, message: String, retryable: Bool, now: Double) {
        inFlight = nil; consecutiveFailures += 1; lastError = message; updatedAt = now
        if !retryable { status = "needs_attention" }
        retryNotBefore = retryable ? now + min(900_000 * pow(2, Double(max(0, min(consecutiveFailures, 16) - 1))), 21_600_000) : nil
        runs = Array(([Run(occurrenceIndex: plan.occurrenceIndex!, scheduledFor: plan.scheduledFor!, attemptedAt: now, status: "failed", amountSat: amountSat, error: message)] + runs).prefix(24))
    }
}

enum RecurringError: LocalizedError {
    case message(String)
    var errorDescription: String? { switch self { case .message(let text): text } }
}
