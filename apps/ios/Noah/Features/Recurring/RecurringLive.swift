import Foundation
import UserNotifications

extension RecurringExecutor {
    static let shared = RecurringExecutor(dependencies: .live())
}

extension RecurringExecutor.Dependencies {
    @MainActor static func live(services: AppServices = .shared, settings: SettingsStorage = .shared,
                                controller: SendPaymentController = .shared) -> Self {
        let location = WalletDataLocation(variant: services.variant)
        let store = RecurringStore(settings: settings)
        return .init(store: store,
                     journal: .init(file: location.directory.appending(path: "noah-recurring-journal.json")),
                     controller: controller,
                     ensureOpen: { try await services.ensureWalletOpen() },
                     binding: { try RecurringStore.binding(location: location) },
                     allowed: {
                         guard !location.restorePending, !FileManager.default.fileExists(atPath: location.deletionMarker.path),
                               let wallet = settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key),
                               wallet.isInitialized == true, wallet.isWalletSuspended != true else { throw AppServices.WalletSuspended() }
                     },
                     quote: { try await RecurringRecipient.quote($0, session: services.session) },
                     sync: { entries in
                         guard settings.persistedState(PersistedServerState.self, key: PersistedServerState.key)?.isRegisteredWithServer == true else { throw RecurringError.message("Register this wallet before syncing recurring payments.") }
                         _ = try await services.client.syncRecurringPayments(body: .json(.init(schedules: entries))).ok
                     },
                     notifications: { await RecurringNotifications.refresh($0, result: $1) },
                     refresh: {
                         guard let summary = try? await services.session.summary() else { return }
                         WidgetDataWriter.write(balance: summary.balance, closestExpiryBlocks: nil, variant: services.variant)
                     })
    }
}

enum RecurringRecipient {
    static func resolve(_ raw: String) throws -> (destination: String, type: String) {
        let cleaned = SendDestination.cleaned(raw)
        let parsed = SendDestination.parse(cleaned)
        guard parsed.error == nil else { throw RecurringError.message(parsed.error!) }
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
    static func quote(_ schedule: RecurringPayment, session: WalletSession) async throws -> (SendPayment, SendQuote) {
        guard schedule.validPolicy else { throw SendFailure.invalid }
        let recipient = try resolve(schedule.destination)
        guard recipient.type == schedule.destinationType else { throw SendFailure.invalid }
        var payment = SendPayment(destination: recipient.destination, method: recipient.type, amount: schedule.amountSat, comment: schedule.comment)
        if recipient.type == "lnurl" {
            let route = try await SendRecipientService.resolve(recipient.destination, session: session)
            let (msat, overflow) = payment.amount.multipliedReportingOverflow(by: 1000)
            guard !overflow, msat >= route.minMsat, msat <= route.maxMsat else {
                throw RecurringError.message("The recipient no longer accepts this amount. Update or cancel the recurring payment.")
            }
            if let ark = route.arkAddress { payment.destination = ark; payment.method = "ark" }
        }
        payment.repeatMetadata = try SendPayment.metadata(destination: schedule.destination, comment: schedule.comment, amount: String(schedule.amountSat), fiatCurrency: nil)
        return (payment, try await session.quoteSend(payment))
    }
}

/// iOS uses the same server wake + foreground execution and local reminder policy as RN.
enum RecurringNotifications {
    static func refresh(_ schedules: [RecurringPayment], result: RecurringPayment?) async {
        let center = UNUserNotificationCenter.current()
        if let result {
            let content = UNMutableNotificationContent()
            if result.status == "needs_attention" {
                content.title = "Recurring payment needs your attention"
                content.body = "A payment to \(result.label) was interrupted. Check your history, then resume it."
            } else if result.runs.first?.status == "failed" {
                content.title = "Recurring payment will retry"
                content.body = "\(result.amountSat) sats to \(result.label) could not be sent yet: \(result.lastError ?? "unknown error")"
            } else {
                content.title = "Recurring payment sent"
                content.body = "\(result.amountSat) sats sent to \(result.label)."
            }
            content.sound = .default
            try? await center.add(.init(identifier: UUID().uuidString, content: content, trigger: nil))
            return
        }
        let pending = await center.pendingNotificationRequests().map(\.identifier).filter { $0.hasPrefix("recurring-") }
        center.removePendingNotificationRequests(withIdentifiers: pending)
        for schedule in schedules where schedule.status == "active" && schedule.inFlight == nil {
            guard let due = schedule.nextRunAt else { continue }
            for (suffix, at, title, body) in [
                ("reminder", due - 86_400_000, "Upcoming recurring payment", "\(schedule.amountSat) sats to \(schedule.label) will be sent tomorrow."),
                ("overdue", due + 7_200_000, "Recurring payment waiting", "Open Noah to send \(schedule.amountSat) sats to \(schedule.label).")
            ] where at > RecurringPayment.now {
                let content = UNMutableNotificationContent()
                content.title = title; content.body = body; content.sound = .default
                content.userInfo = ["notification_type": "recurring_payment_reminder"]
                let trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(1, (at - RecurringPayment.now) / 1000), repeats: false)
                try? await center.add(.init(identifier: "recurring-\(schedule.id)-\(suffix)", content: content, trigger: trigger))
            }
        }
    }
}
