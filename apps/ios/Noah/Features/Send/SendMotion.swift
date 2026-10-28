import Foundation
import Observation

/// The sending stage's motion (send-receive.md, "Motion language"). Phases follow the
/// payment controller's real state: the in-flight phases play while the call runs and
/// hold at the last one, and an ending plays only on the real outcome, never ahead of it.
/// Times are seconds from submission (prototype `startSend`, `arkSend`, `lnSend`, `chainSend`).
enum SendMotion {
    enum Rail: Equatable, Sendable {
        case ark, lightning, onchainSwap, onchainWallet

        init(_ payment: SendPayment) {
            switch payment.method {
            case "ark": self = .ark
            case "onchain": self = payment.source == .onchain ? .onchainWallet : .onchainSwap
            default: self = .lightning
            }
        }

        var isOnchain: Bool { self == .onchainSwap || self == .onchainWallet }

        /// The earliest moment an ending may start: the in-flight phases before it have played.
        var endingStart: Double {
            switch self {
            case .ark: 2.4
            case .lightning: 2.95
            case .onchainSwap: 1.8
            case .onchainWallet: 0.42
            }
        }
    }

    enum Outcome: Equatable, Sendable {
        /// The payment call (or the first status check after it) is still running.
        case inFlight
        case succeeded
        /// Lightning failed and history records the refund (`SendRefundEvidence`).
        case refunded
        /// Lightning is still paying; the wallet reports the payment in progress.
        case pending
        /// No motion ending: failed without a recorded refund, already paid, unknown or
        /// unavailable. Today's wording explains it.
        case stopped
    }

    enum Phase: Equatable, Sendable {
        case start, split
        case sign, deliver, delivered
        case lock, hold, paying, stillPaying, unlock, paid
        case fizzle, signBack, refunded
        case swap, drop, waiting, confirmed
        case stopped

        /// Phases that only a settled payment reaches: the key and the finale.
        var isSuccessEnding: Bool { [.deliver, .delivered, .unlock, .paid].contains(self) }
        var isRefundEnding: Bool { [.fizzle, .signBack, .refunded].contains(self) }
    }

    /// Lightning shows "Still paying" after the bolt has looped for about ten seconds.
    static let stillPayingAfter = 10.0
    static let payingStart = 2.3

    /// What the controller's state means for the motion. `refund` is nil while the
    /// refund evidence for a failed Lightning send is still being read.
    static func outcome(state: SendPaymentController.State, attempt: SendPaymentController.Attempt?,
                        // swiftlint:disable:next discouraged_optional_boolean - nil: the refund is not known yet
                        checking: Bool, refund: Bool?) -> Outcome {
        let lightning = attempt.map { Rail($0.payment) == .lightning } ?? false
        switch state {
        case .ready, .submitting: return .inFlight
        case .succeeded(let receipt):
            // A transaction still waiting to be broadcast isn't in the next block yet.
            return receipt.broadcastRetryPending == true ? .stopped : .succeeded
        case .uncertain:
            if lightning, attempt?.existingLightning != nil { return .pending }
            // The status check right after a thrown submission: hold the phase until it answers.
            if checking, attempt?.receipt == nil, attempt?.candidates == nil, attempt?.possibleTxids == nil {
                return .inFlight
            }
            return .stopped
        case .failed:
            guard lightning, attempt?.failureMessage == nil else { return .stopped }
            switch refund {
            case .some(true): return .refunded
            case .some(false): return .stopped
            case nil: return .inFlight
            }
        case .alreadyPaid, .storageBlocked: return .stopped
        }
    }

    /// The phase at `t` seconds after submission. `outcomeAt` is when the outcome was
    /// first seen and `confirmedAt` when an on-chain confirmation was first seen.
    static func phase(rail: Rail, outcome: Outcome, outcomeAt: Double, at t: Double, confirmedAt: Double? = nil) -> Phase {
        switch outcome {
        case .inFlight: return inFlight(rail, t)
        case .pending:
            guard rail == .lightning else { return inFlight(rail, t) }
            return t >= max(outcomeAt, payingStart) ? .stillPaying : inFlight(rail, t)
        case .stopped:
            return t >= outcomeAt ? .stopped : inFlight(rail, t)
        case .succeeded:
            let ending = max(outcomeAt, rail.endingStart)
            guard t >= ending else { return inFlight(rail, min(t, ending)) }
            switch rail {
            case .ark: return t < ending + 0.35 ? .deliver : .delivered
            case .lightning: return t < ending + 1.15 ? .unlock : .paid
            case .onchainSwap, .onchainWallet:
                if t < ending + 0.6 { return .drop }
                if let confirmedAt, t >= max(ending + 0.6, confirmedAt) { return .confirmed }
                return .waiting
            }
        case .refunded:
            guard rail == .lightning else { return t >= outcomeAt ? .stopped : inFlight(rail, t) }
            let ending = max(outcomeAt, rail.endingStart)
            guard t >= ending else { return inFlight(rail, t) }
            return t < ending + 0.65 ? .fizzle : t < ending + 1.2 ? .signBack : .refunded
        }
    }

    private static func inFlight(_ rail: Rail, _ t: Double) -> Phase {
        if rail == .onchainWallet || t < 0.42 { return .start }
        if t < 1.15 { return .split }
        switch rail {
        case .ark: return .sign
        case .onchainSwap: return .swap
        case .onchainWallet: return .start
        case .lightning:
            if t < 1.7 { return .lock }
            if t < payingStart { return .hold }
            return t < payingStart + stillPayingAfter ? .paying : .stillPaying
        }
    }

    /// When the ending starts, in seconds from submission.
    static func endingStart(rail: Rail, outcomeAt: Double) -> Double { max(outcomeAt, rail.endingStart) }

    /// Every moment the phase can change, in seconds from submission, so the phase line
    /// redraws only then. Extra moments are harmless; a missing one would leave it stale.
    static func phaseBoundaries(rail: Rail, outcomeAt: Double, confirmedAt: Double? = nil) -> [Double] {
        var times = [0.42, 1.15, 1.7, payingStart, payingStart + stillPayingAfter]
        guard outcomeAt.isFinite else { return times + (confirmedAt.map { [$0] } ?? []) }
        let ending = endingStart(rail: rail, outcomeAt: outcomeAt)
        times += [outcomeAt, max(outcomeAt, payingStart)] + [0, 0.35, 0.6, 0.65, 1.15, 1.2].map { ending + $0 }
        if let confirmedAt { times += [confirmedAt, max(ending + 0.6, confirmedAt)] }
        return times
    }

    /// Under Reduce Motion the in-flight stage holds still after this (its last fade), in
    /// seconds from submission; nothing loops.
    static let reducedInFlightEnd = 2.6

    /// When the result appears, in seconds from submission: once the motion reaches its last phase.
    static func resultAt(rail: Rail, outcome: Outcome, outcomeAt: Double) -> Double? {
        let ending = endingStart(rail: rail, outcomeAt: outcomeAt)
        switch outcome {
        case .inFlight: return nil
        case .pending, .stopped: return outcomeAt
        case .succeeded:
            switch rail {
            case .ark: return ending + 0.35
            case .lightning: return ending + 1.45
            case .onchainSwap, .onchainWallet: return ending + 0.6
            }
        case .refunded: return rail == .lightning ? ending + 1.2 : outcomeAt
        }
    }

    enum Haptic: Equatable, Sendable { case light, success, error }
    /// One haptic of the stage: its key (each plays once), when, and which.
    struct Cue {
        let key: String, at: Double, kind: Haptic
        init(_ key: String, _ at: Double, _ kind: Haptic) { self.key = key; self.at = at; self.kind = kind }
    }

    /// Every haptic the stage plays, keyed so each plays once (prototype `hap` calls).
    static func haptics(rail: Rail, outcome: Outcome, outcomeAt: Double, confirmations: Int = 0,
                        required: Int = 1, confirmedAt: Double? = nil) -> [Cue] {
        var list: [Cue] = []
        if rail != .onchainWallet { list.append(Cue("split", 0.42, .light)) }
        switch rail {
        case .ark: list += [Cue("signatures", 1.57, .light), Cue("seal", 2.05, .light)]
        case .lightning: list.append(Cue("lock", 1.15, .light))
        default: break
        }
        let ending = endingStart(rail: rail, outcomeAt: outcomeAt)
        switch (outcome, rail) {
        case (.succeeded, .ark): list.append(Cue("finale", ending + 0.35, .success))
        case (.succeeded, .lightning):
            list += [Cue("unlock", ending + 0.9, .light), Cue("finale", ending + 1.45, .success)]
        case (.succeeded, .onchainSwap), (.succeeded, .onchainWallet):
            list.append(Cue("drop", ending + (rail == .onchainSwap ? 0.25 : 0), .light))
            if let confirmedAt, confirmations > 0 {
                let at = max(ending + 0.6, confirmedAt)
                list.append(Cue("confirmed-\(min(confirmations, required))", at, confirmations >= required ? .success : .light))
            }
        case (.refunded, .lightning):
            list += [Cue("fizzle", ending, .error), Cue("refund", ending + 1.2, .success)]
        default: break
        }
        return list
    }

    /// Confirmations of a transaction mined at `height` with the chain at `tip`.
    static func confirmations(height: UInt32?, tip: UInt32?) -> Int {
        guard let height, let tip, tip >= height else { return height == nil ? 0 : 1 }
        return Int(tip - height) + 1
    }
}

/// How the result reads (send-receive.md, "Results"). Today's edge states keep their meaning.
enum SendResultKind: Equatable, Sendable {
    case sending, stillSending, sent, refunded, failed, alreadyPaid, uncertain, unavailable, awaitingBroadcast

    static func of(state: SendPaymentController.State, outcome: SendMotion.Outcome, slow: Bool) -> Self {
        switch state {
        case .ready, .submitting: return slow ? .stillSending : .sending
        case .succeeded(let receipt): return receipt.broadcastRetryPending == true ? .awaitingBroadcast : .sent
        case .uncertain:
            switch outcome {
            case .pending: return .stillSending
            case .inFlight: return .sending
            default: return .uncertain
            }
        case .failed:
            switch outcome {
            case .refunded: return .refunded
            case .inFlight: return .sending
            default: return .failed
            }
        case .alreadyPaid: return .alreadyPaid
        case .storageBlocked: return .unavailable
        }
    }

    /// Done may leave: every result, and a Lightning payment that is still paying.
    var allowsDone: Bool { ![.sending, .uncertain, .unavailable, .awaitingBroadcast].contains(self) }
}

/// Remembers when the stage started and when each outcome was first seen, so a rebuilt
/// view (a pose change) continues where it was, and each haptic plays once.
@MainActor @Observable final class SendMotionClock {
    private(set) var submittedAt: Date?
    private(set) var outcome: SendMotion.Outcome = .inFlight
    private(set) var outcomeAt: Date?
    private(set) var confirmedAt: Date?
    private(set) var confirmedHeight: UInt32?
    private var played: Set<String> = []
    /// Haptics due before this many seconds belong to a restored stage and stay silent.
    private var silentUntil = 0.0

    /// Hold to send finished: a new stage.
    func start(at date: Date = Date()) {
        reset()
        submittedAt = date
    }

    /// An outcome the view derived from the controller. A status check never takes a
    /// shown outcome back to in-flight. An outcome seen without a stage (a saved attempt
    /// after relaunch) shows its end state at once, silently: it dates from submission.
    func observe(_ next: SendMotion.Outcome, at date: Date = Date()) {
        if submittedAt == nil {
            submittedAt = next == .inFlight ? date : date.addingTimeInterval(-Self.restoredAge)
            silentUntil = next == .inFlight ? 0 : Self.restoredAge + 1
        }
        guard next != outcome, !(next == .inFlight && outcome != .inFlight) else { return }
        outcome = next
        outcomeAt = restoring(date) ? submittedAt : date
    }

    func confirm(height: UInt32?, at date: Date = Date()) {
        guard height != confirmedHeight else { return }
        confirmedHeight = height
        confirmedAt = height == nil ? nil : restoring(date) ? submittedAt : date
    }

    /// Whether `date` falls in a restored stage's first moment, when what it sees was already so.
    private func restoring(_ date: Date) -> Bool { silentUntil > 0 && elapsed(date) < silentUntil }

    func reset() {
        submittedAt = nil
        outcome = .inFlight
        outcomeAt = nil
        confirmedAt = nil
        confirmedHeight = nil
        played = []
        silentUntil = 0
    }

    /// A restored stage starts this far in the past, after every ending.
    private static let restoredAge = 60.0

    func elapsed(_ date: Date) -> Double { submittedAt.map { max(0, date.timeIntervalSince($0)) } ?? 0 }
    /// Seconds from submission to `moment`.
    func since(_ moment: Date?) -> Double? {
        guard let moment, let submittedAt else { return nil }
        return max(0, moment.timeIntervalSince(submittedAt))
    }

    /// Marks a haptic due at `at` played; false if it already was, or belongs to a restored stage.
    func shouldPlay(_ key: String, at: Double) -> Bool {
        guard at >= silentUntil else { return false }
        return played.insert(key).inserted
    }
}
