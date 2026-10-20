import Foundation
import UserNotifications

/// Runs the work a silent push asks for, ported from `handleNotificationData`
/// and `client/src/lib/tasks.ts`. Maintenance and backups report their
/// outcome to the server with the push's k1; failures are rethrown so the
/// system sees `.failed`.
struct PushJobs: Sendable {
    typealias NotificationData = Components.Schemas.NotificationData

    enum Report: String, Sendable {
        case maintenance, backup
    }

    struct Dependencies: Sendable {
        var ensureWalletOpen: @Sendable () async throws -> Void
        var maintenance: @Sendable () async throws -> Void
        var createInvoice: @Sendable (_ sats: UInt64) async throws -> String
        var submitInvoice: @Sendable (_ transactionID: String, _ invoice: String) async throws -> Void
        var claimLightning: @Sendable () async throws -> Void
        var backup: @Sendable () async throws -> Void
        var reportJob: @Sendable (_ report: Report, _ error: Error?, _ k1: String) async -> Void
        var heartbeat: @Sendable (_ notificationID: String) async throws -> Void
        var announceReceived: @Sendable (_ sats: Int64, _ usd: String?) async -> Void
        var refreshWidget: @Sendable () async -> Void
        var recurring: @Sendable () async throws -> Void = {}
        var withWalletAccess: @Sendable (@escaping @Sendable () async throws -> Void) async throws -> Void = { try await $0() }
    }

    let dependencies: Dependencies
    private let log = AppLog("pushJobs")

    func handle(_ notification: NotificationData) async throws {
        await WalletBackgroundActivity.shared.begin()
        do {
            try await dependencies.withWalletAccess { try await handleAction(notification) }
            await WalletBackgroundActivity.shared.end()
        } catch {
            await WalletBackgroundActivity.shared.end()
            throw error
        }
    }

    private func handleAction(_ notification: NotificationData) async throws {
        let d = dependencies
        log.i("Handling \(Self.name(notification)) push")
        defer { log.i("Finished \(Self.name(notification)) push") }
        switch notification {
        case .maintenance(let payload):
            let result = await outcome { try await d.ensureWalletOpen(); try await d.maintenance() }
            await d.reportJob(.maintenance, result, payload.notificationK1)
            if let result { throw result }
            await d.refreshWidget()

        case .lightningInvoiceRequest(let payload):
            try await d.ensureWalletOpen()
            // msat → sat, truncating like the RN app's Nitro call.
            let sats = UInt64(max(payload.amount, 0)) / 1000
            let invoice = try await d.createInvoice(sats)
            try await d.submitInvoice(payload.transactionId, invoice)

        case .lightningClaimRequest(let payload):
            try await d.ensureWalletOpen()
            try await d.claimLightning()
            await d.announceReceived(payload.amountSat, payload.amountUsd)
            await d.refreshWidget()

        case .backupTrigger(let payload):
            let result = await outcome { try await d.ensureWalletOpen(); try await d.backup() }
            await d.reportJob(.backup, result, payload.notificationK1)
            if let result { throw result }

        case .heartbeat(let payload):
            do {
                try await d.heartbeat(payload.notificationId)
            } catch {
                log.w("Failed to answer heartbeat")
            }

        case .recurringPaymentDue:
            try await d.ensureWalletOpen()
            try await d.recurring()
            await d.refreshWidget()
        }
    }

    static func name(_ notification: NotificationData) -> String {
        switch notification {
        case .maintenance: "maintenance"
        case .lightningInvoiceRequest: "lightning_invoice_request"
        case .lightningClaimRequest: "lightning_claim_request"
        case .backupTrigger: "backup_trigger"
        case .heartbeat: "heartbeat"
        case .recurringPaymentDue: "recurring_payment_due"
        }
    }

    private func outcome(_ work: () async throws -> Void) async -> Error? {
        do {
            try await work()
            return nil
        } catch {
            return error
        }
    }
}

extension PushJobs.Dependencies {
    static func live(_ services: AppServices) -> Self {
        let session = services.session
        let client = services.client
        return Self(
            ensureWalletOpen: { try await services.ensureWalletOpen() },
            maintenance: { try await session.maintenanceDelegated() },
            createInvoice: { try await session.bolt11Invoice(amountSats: $0) },
            submitInvoice: { transactionID, invoice in
                _ = try await client.submitInvoice(body: .json(.init(invoice: invoice, transactionId: transactionID))).ok
            },
            claimLightning: { try await session.claimLightningReceives() },
            backup: {
                // RN `triggerBackupTask`: flush regardless of the backup setting.
                try await services.backupCoordinator.flush(.push, requireEnabled: false)
            },
            reportJob: { report, error, k1 in
                let message = error.map(SafeErrorDescription.of)
                _ = try? await client.reportJobStatus(body: .json(.init(
                    errorMessage: message, notificationK1: k1,
                    reportType: report == .maintenance ? .maintenance : .backup,
                    status: error == nil ? .success : .failure))).ok
            },
            heartbeat: { id in
                _ = try await client.heartbeatResponse(body: .json(.init(notificationId: id))).ok
            },
            announceReceived: { sats, usd in
                let content = UNMutableNotificationContent()
                content.title = String(localized: "Lightning Payment Received! ⚡")
                let amount = BitcoinAmount.format(sats: sats, unit: .bip177)
                content.body = usd.map { String(localized: "You received \(amount) (≈ US$\($0))") }
                    ?? String(localized: "You received \(amount)")
                try? await UNUserNotificationCenter.current().add(
                    UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
            },
            refreshWidget: {
                guard let summary = try? await session.summary() else { return }
                let expiry = try? await session.closestExpiryBlocks()
                WidgetDataWriter.write(balance: summary.balance, closestExpiryBlocks: expiry ?? nil, variant: services.variant)
            },
            recurring: { await RecurringExecutor.shared.execute() },
            withWalletAccess: { try await services.withBackgroundWallet($0) })
    }
}
