import Foundation

/// Persist identifiers and quote arguments; resolve copy only at the display boundary.
struct RecurringIssue: Codable, Equatable, Sendable {
    enum Code: String, Codable, Sendable {
        case policyEvidence, automationStopped, legacyHistory, invalidJournal, interrupted, tooManyMissed
        case quoteExceedsCap, scheduleExceedsCap, arkFee, uncertain, retryableFailure, alreadyPaid
        case unsupported, amountLimit, unknown
    }
    var code: Code
    var arguments: [String] = []
    var valid: Bool {
        let count = code == .quoteExceedsCap || code == .scheduleExceedsCap ? 2 : 0
        return arguments.count == count && arguments.allSatisfy { UInt64($0) != nil }
    }
    func message(locale: String = LegacyLocale.resolved()) -> String {
        switch code {
        case .quoteExceedsCap:
            return L10n.text("The fee for this payment ({fee} sats) is above your automatic limit of {cap} sats. Review it to pay manually.", arguments[0], arguments[1], locale: locale)
        case .scheduleExceedsCap:
            return L10n.text("Ark's published Lightning fees for this amount can reach {fee} sats, above your automatic limit of {cap} sats. Review it to pay manually.", arguments[0], arguments[1], locale: locale)
        case .policyEvidence: return L10n.text(RecurringPolicyLedger.message, locale: locale)
        case .automationStopped: return L10n.text(RecurringAutomationAuthority.message, locale: locale)
        case .legacyHistory: return L10n.text(RecurringJournal.legacyMessage, locale: locale)
        case .invalidJournal: return L10n.text(RecurringJournal.invalidMessage, locale: locale)
        case .interrupted: return L10n.text("Noah was interrupted while sending this payment. Check your transaction history before resuming.", locale: locale)
        case .tooManyMissed: return L10n.text("This schedule has more than 5,000 missed occurrences. Cancel it and create a schedule with a future first payment.", locale: locale)
        case .arkFee: return L10n.text("The fee for this Ark payment must be 0 sats to pay automatically. Review it to pay manually.", locale: locale)
        case .uncertain: return L10n.text("This payment may have been sent. Check its status before making another payment.", locale: locale)
        case .alreadyPaid: return L10n.text("This invoice was already paid. This occurrence was not sent again. Check the recipient before resuming.", locale: locale)
        case .retryableFailure: return L10n.text("This payment could not be prepared. No payment was submitted.", locale: locale)
        case .amountLimit: return L10n.text(RecurringPayment.amountLimitMessage, locale: locale)
        case .unsupported, .unknown: return L10n.text(RecurringPayment.unsupportedMessage, locale: locale)
        }
    }
    // Only legacy decoding may recognize English copy. Never infer a safe outcome from unknown text.
    static func legacy(_ text: String) -> Self {
        let fixed: [String: Code] = [
            RecurringPolicyLedger.message: .policyEvidence,
            RecurringAutomationAuthority.message: .automationStopped,
            RecurringJournal.legacyMessage: .legacyHistory,
            RecurringJournal.invalidMessage: .invalidJournal,
            "Noah was interrupted while sending this payment. Check your transaction history before resuming.": .interrupted,
            "This schedule has more than 5,000 missed occurrences. Cancel it and create a schedule with a future first payment.": .tooManyMissed,
            "The fee for this Ark payment must be 0 sats to pay automatically. Review it to pay manually.": .arkFee,
            "This payment may have been sent. Check its status before making another payment.": .uncertain,
            "This invoice was already paid. This occurrence was not sent again. Check the recipient before resuming.": .alreadyPaid,
            "This payment could not be prepared. No payment was submitted.": .retryableFailure,
            "No payment was submitted.": .retryableFailure,
            RecurringPayment.unsupportedMessage: .unsupported,
            RecurringPayment.amountLimitMessage: .amountLimit
        ]
        if let code = fixed[text] { return .init(code: code) }
        for (pattern, code) in [
            (#"^The fee for this payment \(([0-9]+) sats\) is above your automatic limit of ([0-9]+) sats\. Review it to pay manually\.$"#, Code.quoteExceedsCap),
            (#"^Ark's published Lightning fees for this amount can reach ([0-9]+) sats, above your automatic limit of ([0-9]+) sats\. Review it to pay manually\.$"#, Code.scheduleExceedsCap)
        ] {
            if let match = try? NSRegularExpression(pattern: pattern).firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) {
                let values = (1...2).compactMap { Range(match.range(at: $0), in: text).map { String(text[$0]) } }
                let issue = Self(code: code, arguments: values)
                if issue.valid { return issue }
            }
        }
        return .init(code: .unknown)
    }
}
