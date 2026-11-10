import ActivityKit
import UIKit
import UserNotifications

/// Shows a Lightning payment sent from Send outside the app (async-payments.md, "Live Activity"). iOS starts an
/// activity only from the foreground, so it begins as the payment is submitted; after that it is updated whenever
/// Noah runs, and ended on the payment's result. With Live Activities off, a result reached out of sight is a
/// notification instead. Neither shows an amount, and recurring payments show nothing.
@MainActor final class SendActivityCenter {
    static let shared = SendActivityCenter()
    private let controller: SendPaymentController
    private let session: WalletSession
    private var started = false
    /// The attempt whose result was already shown, so it is said once.
    private var announced: Date?
    private var work: Task<Void, Never>?

    init(controller: SendPaymentController = .shared, session: WalletSession = .shared) {
        self.controller = controller
        self.session = session
    }

    /// What the activity shows for the send journal's state; nil for nothing (no Lightning payment of Send's own
    /// in flight, or an outcome the journal doesn't prove).
    static func phase(state: SendPaymentController.State, attempt: SendPaymentController.Attempt?, refunded: Bool,
                      now: Date = Date()) -> SendActivityPhase? {
        guard let attempt, SendMotion.Rail(attempt.payment) == .lightning, attempt.recurringOccurrenceKey == nil else { return nil }
        switch state {
        case .submitting: return .paying
        case .uncertain:
            guard attempt.pending != nil else { return nil }
            let slow = now.timeIntervalSince(attempt.startedAt ?? .distantPast) >= SendMotion.payingStart + SendMotion.stillPayingAfter
            return slow ? .stillPaying : .paying
        case .succeeded: return .sent
        case .failed: return refunded ? .refunded : .failed
        case .ready, .alreadyPaid, .storageBlocked: return nil
        }
    }

    /// Call when Noah comes to the front: follows the journal from then on. An activity that outlived the last run
    /// is found again through ActivityKit, which is the only record of it.
    func start() {
        guard !started else { return }
        started = true
        follow()
    }

    /// Re-armed on every change: `withObservationTracking` reports one.
    private func follow() {
        withObservationTracking { _ = controller.state; _ = controller.attempt } onChange: { [weak self] in
            Task { @MainActor in self?.follow() }
        }
        refresh()
    }

    /// One update at a time, in order.
    private func refresh() {
        work = Task { [previous = work] in
            await previous?.value
            await self.apply()
        }
    }

    private func apply() async {
        let state = controller.state, attempt = controller.attempt
        var refunded = false
        if state == .failed, attempt?.failureMessage == nil, let hash = attempt?.paymentHash {
            refunded = (try? await session.lightningSendRefund(paymentHash: hash, before: attempt?.snapshot.movementIDs ?? [])) == .recorded
        }
        let inFront = UIApplication.shared.applicationState == .active
        guard let phase = Self.phase(state: state, attempt: attempt, refunded: refunded) else {
            await Self.end(nil, dismissal: .immediate)
            return
        }
        guard phase.ended else { await show(phase, attempt: attempt, inFront: inFront); return }
        guard announced != attempt?.startedAt else { return }
        announced = attempt?.startedAt
        guard Self.showing else {
            if !inFront { await notify(phase) }
            return
        }
        // In front, Send shows the result itself.
        guard !inFront else { await Self.end(nil, dismissal: .immediate); return }
        // The Dynamic Island drops an ended activity at once, so it shows the result for a moment first; the Lock
        // Screen then keeps it for a quarter of an hour. If Noah is suspended meanwhile, this ends on its return.
        await Self.update(Self.content(phase))
        try? await Task.sleep(for: SendBackgroundWindow.resultLinger - .seconds(1))
        await Self.end(Self.content(phase), dismissal: .after(Date().addingTimeInterval(900)))
    }

    private func show(_ phase: SendActivityPhase, attempt: SendPaymentController.Attempt?, inFront: Bool) async {
        if Self.showing { await Self.update(Self.content(phase)); return }
        guard inFront, ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        _ = try? Activity.request(attributes: SendActivityAttributes(), content: Self.content(phase))
        // "Still paying" is due later, perhaps with Noah out of sight but still running.
        guard phase == .paying, let startedAt = attempt?.startedAt else { return }
        let due = startedAt.addingTimeInterval(SendMotion.payingStart + SendMotion.stillPayingAfter + 0.1).timeIntervalSinceNow
        Task {
            do { try await Task.sleep(for: .seconds(max(0, due))) } catch { return }
            self.refresh()
        }
    }

    private typealias Content = ActivityContent<SendActivityAttributes.ContentState>
    /// An ended activity stays listed until iOS removes it; only a live one can be updated.
    private static var showing: Bool {
        Activity<SendActivityAttributes>.activities.contains { $0.activityState == .active || $0.activityState == .stale }
    }

    private nonisolated static func update(_ content: Content) async {
        for activity in Activity<SendActivityAttributes>.activities { await activity.update(content) }
    }

    private nonisolated static func end(_ content: Content?, dismissal: ActivityUIDismissalPolicy) async {
        for activity in Activity<SendActivityAttributes>.activities { await activity.end(content, dismissalPolicy: dismissal) }
    }

    /// Each update is good for three minutes; after that the activity reads "Open Noah to check".
    private static func content(_ phase: SendActivityPhase) -> Content {
        .init(state: .init(phase: phase), staleDate: phase.ended ? nil : Date().addingTimeInterval(180))
    }

    private func notify(_ phase: SendActivityPhase) async {
        let locale = LegacyLocale.resolved(from: .standard)
        let content = UNMutableNotificationContent()
        content.title = phase.title(locale: locale)
        if let line = phase.line(locale: locale) { content.body = line }
        try? await UNUserNotificationCenter.current().add(.init(identifier: UUID().uuidString, content: content, trigger: nil))
    }
}
