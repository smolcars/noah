import BarkBoltFFI
import Foundation
import Observation
import UserNotifications

extension RecurringExecutor {
    static let shared = RecurringExecutor(dependencies: .live())
}

extension RecurringExecutor.Dependencies {
    @MainActor static func live(services: AppServices = .shared, settings: SettingsStorage = .shared,
                                controller: SendPaymentController = .shared, location: WalletDataLocation? = nil) -> Self {
        let location = location ?? WalletDataLocation(variant: services.variant)
        let store = RecurringStore(settings: settings)
        return .init(store: store,
                     journal: .init(file: location.directory.appending(path: "noah-recurring-journal.json")),
                     controller: controller,
                     ensureOpen: { try await services.ensureWalletOpen() },
                     binding: {
                         guard location.databaseExists else { throw RecurringExecutor.NoWallet() }
                         return try RecurringStore.binding(location: location)
                     },
                     allowed: {
                         guard location.databaseExists else { throw RecurringExecutor.NoWallet() }
                         guard !location.restorePending, !FileManager.default.fileExists(atPath: location.deletionMarker.path),
                               settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isInitialized != false,
                               settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)?.isWalletSuspended != true else { throw AppServices.WalletSuspended() }
                     },
                     quote: { try await RecurringRecipient.quote($0, session: services.session) },
                     automationGeneration: { settings.string(forKey: WalletLifecycle.automationStopKey) },
                     sync: { entries in
                         guard settings.persistedState(PersistedServerState.self, key: PersistedServerState.key)?.isRegisteredWithServer == true else { throw RecurringError.message("Register this wallet before syncing recurring payments.") }
                         _ = try await services.client.syncRecurringPayments(body: .json(.init(schedules: entries))).ok
                     },
                     notifications: { await RecurringNotifications.refresh($0, result: $1) },
                     refresh: {
                         guard let summary = try? await services.session.summary() else { return }
                         WidgetDataWriter.write(balance: summary.balance, closestExpiryBlocks: nil, variant: services.variant, preserveExpiry: true)
                     })
    }
}

enum RecurringRecipient {
    static func resolve(_ raw: String) throws -> (destination: String, type: String) {
        let cleaned = SendDestination.cleaned(raw)
        let parsed = SendDestination.parse(cleaned)
        if let error = parsed.error { throw error }
        switch parsed.destinationType {
        case "ark", "offer": return (cleaned, parsed.destinationType!)
        case "lnurl": return (cleaned.lowercased(), "lnurl")
        case "bip321":
            if let ark = parsed.bip321?["arkAddress"] { return (ark, "ark") }
            if let offer = parsed.bip321?["offer"] { return (offer, "offer") }
            throw RecurringError.message("This payment request has no reusable Ark address or Lightning offer")
        case "lightning": throw RecurringError.message("Lightning invoices can only be paid once. Use a Lightning address or offer")
        case "onchain": throw RecurringError.message("On-chain addresses are not supported for recurring payments yet")
        default: throw RecurringError.message("Unsupported recipient")
        }
    }
    static func quote(_ schedule: RecurringPayment, session: WalletSession) async throws -> (SendPayment, SendQuote, LightningSendFees?) {
        guard schedule.validPolicy, schedule.amountSat <= RecurringPayment.maximumAmount else { throw SendFailure.invalid }
        let recipient = try resolve(schedule.destination)
        guard recipient.type == schedule.destinationType else { throw SendFailure.invalid }
        var payment = SendPayment(destination: recipient.destination, method: recipient.type, amount: schedule.amountSat, comment: schedule.comment)
        if recipient.type == "lnurl" {
            let route = try await SendRecipientService.resolve(recipient.destination, session: session)
            payment = try lnurlPayment(payment, route: route)
        }
        payment.repeatMetadata = try SendPayment.metadata(destination: schedule.destination, comment: schedule.comment, amount: String(schedule.amountSat), fiatCurrency: nil)
        let quote = try await session.quoteSend(payment)
        let fees = payment.method == "ark" ? nil : await lightningFees {
            try await session.withOpenWallet { try await $0.wallet.arkInfo()?.feeSchedule.lightningSend }
        }
        return (payment, quote, fees)
    }
    static func lnurlPayment(_ input: SendPayment, route: SendRecipientRoute) throws -> SendPayment {
        var payment = input
        let (msat, overflow) = payment.amount.multipliedReportingOverflow(by: 1000)
        guard !overflow, msat >= route.minMsat, msat <= route.maxMsat else {
            throw RecurringError.message("The recipient no longer accepts this amount. Update or cancel the recurring payment.")
        }
        // RN uses Lightning when a note is present so it reaches the recipient.
        if payment.comment.isEmpty, let ark = route.arkAddress { payment.destination = ark; payment.method = "ark" }
        return payment
    }
    static func lightningFees(_ fetch: () async throws -> LightningSendFees?) async -> LightningSendFees {
        do {
            if let fees = try await fetch() { return fees }
        } catch { /* Use the published fallback for unavailable ArkInfo. */ }
        AppLog("recurring").d(#"ArkInfo unavailable; using the published Lightning fee schedule"#)
        return RecurringFeePolicy.fallback
    }

}

/// iOS uses the same server wake + foreground execution and local reminder policy as RN.
enum RecurringNotifications {
    @MainActor private static var revision = UUID()
    @MainActor static func refresh(_ schedules: [RecurringPayment], result: RecurringPayment?) async {
        let center = UNUserNotificationCenter.current()
        if let result {
            let content = UNMutableNotificationContent()
            let locale = LegacyLocale.resolved()
            let unit = SettingsStorage.shared.persistedState(PersistedProfileState.self, key: PersistedProfileState.key)?.amountUnit ?? .bip177
            let amount = BitcoinAmount.format(sats: Int64(clamping: result.amountSat), unit: unit, locale: Locale(identifier: locale))
            if result.status == "needs_approval" {
                content.title = L10n.text("Recurring payment needs approval", locale: locale)
                content.body = L10n.text("Automatic payment to {recipient} was skipped. Open the schedule to review its fee and pay manually.", result.label, locale: locale)
            } else if result.status == "needs_attention" {
                content.title = L10n.text("Recurring payment needs your attention", locale: locale)
                content.body = L10n.text("A payment to {recipient} was interrupted. Check your history, then resume it.", result.label, locale: locale)
            } else if result.runs.first?.status == "failed" {
                content.title = L10n.text("Recurring payment will retry", locale: locale)
                content.body = L10n.text("{amount} to {recipient} could not be sent yet: {error}", amount, result.label, result.issue?.message(locale: locale) ?? L10n.text(result.lastError ?? "unknown error", locale: locale), locale: locale)
            } else {
                content.title = L10n.text("Recurring payment sent", locale: locale)
                content.body = L10n.text("{amount} sent to {recipient}.", amount, result.label, locale: locale)
            }
            content.sound = .default
            content.userInfo = ["notification_type": "recurring_payment_result"]
            center.add(.init(identifier: UUID().uuidString, content: content, trigger: nil), withCompletionHandler: nil)
            return
        }
        revision = UUID()
        let requested = revision
        // Notification-daemon callbacks are advisory and must never hold up payments or the UI.
        center.getPendingNotificationRequests { pending in
            let identifiers = pending.map(\.identifier).filter { $0.hasPrefix("recurring-") }
            Task { @MainActor in
                guard requested == revision else { return }
                let center = UNUserNotificationCenter.current()
                center.removePendingNotificationRequests(withIdentifiers: identifiers)
                schedule(schedules, center: center)
            }
        }
    }
    private static func schedule(_ schedules: [RecurringPayment], center: UNUserNotificationCenter) {
        for schedule in schedules where schedule.status == "active" && schedule.inFlight == nil {
            guard let due = schedule.nextRunAt else { continue }
            for (suffix, at) in [
                ("reminder", due - 86_400_000),
                ("overdue", due + 7_200_000)
            ] where at > RecurringPayment.now {
                let content = UNMutableNotificationContent()
                let unit = SettingsStorage.shared.persistedState(PersistedProfileState.self, key: PersistedProfileState.key)?.amountUnit ?? .bip177
                let amount = BitcoinAmount.format(sats: Int64(clamping: schedule.amountSat), unit: unit, locale: Locale(identifier: LegacyLocale.resolved()))
                let copy = NotificationCopy.reminder(amount: amount, recipient: schedule.label, upcoming: suffix == "reminder")
                content.title = copy.title; content.body = copy.body; content.sound = .default
                content.userInfo = ["notification_type": "recurring_payment_reminder"]
                let trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(1, (at - RecurringPayment.now) / 1000), repeats: false)
                center.add(.init(identifier: "recurring-\(schedule.id)-\(suffix)", content: content, trigger: trigger), withCompletionHandler: nil)
            }
        }
    }
}

/// bark-bolt-ffi 0.4.0 pins bark bfbe6b7c3088a09dd4e81c4ac24cdc763a627a8c.
/// lib/src/fees.rs LightningSendFees::calculate charges max(min, base + ceil(ppm)),
/// with calc_ppm_expiry_fee charging at most the payment amount across expiry tiers.
/// bark/src/actions/lightning/pay.rs start_lightning_send selects inputs using the
/// wallet's ArkInfo lightning_send schedule, stores that fee, and builds the HTLC
/// and change from it; the server cosigns those outputs, without adding a fee.
/// Thus the maximum tier bounds submission under that schedule. The user accepts
/// the residual risk of the server changing its schedule between quote and submit.
/// Arkoor remains zero-fee. Attended Send review is deliberately uncapped.
enum RecurringFeePolicy {
    static let fallback = LightningSendFees(minFeeSats: 20, baseFeeSats: 0, ppmExpiryTable: [
        .init(expiryBlocksThreshold: 0, ppm: 2000),
        .init(expiryBlocksThreshold: 1008, ppm: 4000),
        .init(expiryBlocksThreshold: 2016, ppm: 5000)
    ])
    enum Reason: String { case withinCap = "within_cap", quoteExceedsCap = "quote_exceeds_cap", scheduleExceedsCap = "schedule_exceeds_cap", arkFee = "ark_fee" }
    struct Decision {
        let worstCaseFee: UInt64?
        let cap: UInt64?
        let reason: Reason
        var autoPay: Bool { reason == .withinCap }
        func approvalIssue(quoteFee: UInt64) -> RecurringIssue? {
            switch reason {
            case .withinCap: nil
            case .quoteExceedsCap: .init(code: .quoteExceedsCap, arguments: [String(quoteFee), String(cap!)])
            case .scheduleExceedsCap: .init(code: .scheduleExceedsCap, arguments: [String(worstCaseFee!), String(cap!)])
            case .arkFee: .init(code: .arkFee)
            }
        }
        func approvalMessage(quoteFee: UInt64) -> String? {
            switch reason {
            case .withinCap: nil
            case .quoteExceedsCap: "The fee for this payment (\(quoteFee) sats) is above your automatic limit of \(cap!) sats. Review it to pay manually."
            case .scheduleExceedsCap: "Ark's published Lightning fees for this amount can reach \(worstCaseFee!) sats, above your automatic limit of \(cap!) sats. Review it to pay manually."
            case .arkFee: "The fee for this Ark payment must be 0 sats to pay automatically. Review it to pay manually."
            }
        }
    }
    static func evaluate(amount: UInt64, method: String, quoteFee: UInt64, schedule: LightningSendFees?) -> Decision {
        if method == "ark" { return .init(worstCaseFee: nil, cap: nil, reason: quoteFee == 0 ? .withinCap : .arkFee) }
        let schedule = schedule ?? fallback
        let ppm = schedule.ppmExpiryTable.map(\.ppm).max() ?? 0
        // Widen before dividing: a UInt64 product can overflow while its ppm fee fits.
        let product = amount.multipliedFullWidth(by: ppm)
        let proportional: UInt64
        if product.high >= 1_000_000 { proportional = .max }
        else {
            let divided = UInt64(1_000_000).dividingFullWidth(product)
            proportional = add(divided.quotient, divided.remainder == 0 ? 0 : 1)
        }
        let worst = max(schedule.minFeeSats, add(schedule.baseFeeSats, proportional))
        let cap = min(add(worst, worst), max(200, amount / 50))
        // The cap is always below UInt64.max, so any saturated fee exceeds it.
        let reason: Reason = quoteFee > cap ? .quoteExceedsCap : worst > cap ? .scheduleExceedsCap : .withinCap
        return .init(worstCaseFee: worst, cap: cap, reason: reason)
    }
    private static func add(_ a: UInt64, _ b: UInt64) -> UInt64 {
        let (value, overflow) = a.addingReportingOverflow(b)
        return overflow ? .max : value
    }
}

/// A page Home opens with Settings beneath: Activity's links, or a recurring notification's tap (kept
/// pending through a cold launch until the wallet's navigation is available).
@MainActor @Observable final class SettingsRoute {
    static let shared = SettingsRoute()
    var page: SettingsPage?

    func receive(_ userInfo: [AnyHashable: Any]) {
        if Self.opensSchedules(userInfo) { page = .recurring }
    }
    /// Home's path for the pending page, once.
    func take() -> [HomeView.Route]? {
        defer { page = nil }
        return page.map { SettingsPath.deepLink($0) }
    }
    nonisolated static func opensSchedules(_ info: [AnyHashable: Any]) -> Bool {
        if let type = info["notification_type"] as? String,
           ["recurring_payment_reminder", "recurring_payment_result", "recurring_payment_due"].contains(type) { return true }
        for key in ["body", "dataString", "data", "UIApplicationLaunchOptionsRemoteNotificationKey"] {
            if let record = info[key] as? [AnyHashable: Any], opensSchedules(record) { return true }
            if let text = info[key] as? String,
               let record = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [AnyHashable: Any],
               opensSchedules(record) { return true }
        }
        return false
    }
}
