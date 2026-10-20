import Foundation
import Testing

@testable import Noah

struct PushJobsTests {
    final class Recorder: @unchecked Sendable {
        let lock = NSLock()
        var events: [String] = []
        var failMaintenance = false
        var failBackup = false
        var failHeartbeat = false
        func add(_ event: String) { lock.withLock { events.append(event) } }
        var all: [String] { lock.withLock { events } }
    }

    struct Boom: Error {}

    private func jobs(_ recorder: Recorder) -> PushJobs {
        PushJobs(dependencies: .init(
            ensureWalletOpen: { recorder.add("open") },
            maintenance: { recorder.add("maintenance"); if recorder.failMaintenance { throw Boom() } },
            createInvoice: { sats in recorder.add("invoice \(sats)"); return "lnbcrt1" },
            submitInvoice: { tx, invoice in recorder.add("submit \(tx) \(invoice)") },
            claimLightning: { recorder.add("claim") },
            backup: { recorder.add("backup"); if recorder.failBackup { throw Boom() } },
            reportJob: { report, error, k1 in recorder.add("report \(report.rawValue) \(error == nil ? "success" : "failure") \(k1)") },
            heartbeat: { id in recorder.add("heartbeat \(id)"); if recorder.failHeartbeat { throw Boom() } },
            announceReceived: { sats, usd in recorder.add("announce \(sats) \(usd ?? "-")") },
            refreshWidget: { recorder.add("widget") },
            recurring: { recorder.add("recurring") }))
    }

    private func decode(_ json: String) throws -> PushPayload.NotificationData {
        try JSONDecoder().decode(PushPayload.NotificationData.self, from: Data(json.utf8))
    }

    @Test func recurringWakeOpensWalletThenRunsLocalPolicy() async throws {
        let recorder = Recorder()
        try await jobs(recorder).handle(decode(#"{"notification_type":"recurring_payment_due","due_count":4294967295}"#))
        #expect(recorder.all == ["open", "recurring", "widget"])
    }

    @Test func maintenanceReportsAndRefreshesTheWidget() async throws {
        let recorder = Recorder()
        try await jobs(recorder).handle(decode(#"{"notification_type":"maintenance","notification_k1":"k1"}"#))
        #expect(recorder.all == ["open", "maintenance", "report maintenance success k1", "widget"])
    }

    @Test func failedMaintenanceIsReportedAndRethrown() async throws {
        let recorder = Recorder()
        recorder.failMaintenance = true
        await #expect(throws: Boom.self) {
            try await jobs(recorder).handle(decode(#"{"notification_type":"maintenance","notification_k1":"k1"}"#))
        }
        #expect(recorder.all == ["open", "maintenance", "report maintenance failure k1"])
    }

    @Test func invoiceRequestTruncatesMillisatoshis() async throws {
        let recorder = Recorder()
        try await jobs(recorder).handle(decode(#"{"notification_type":"lightning_invoice_request","transaction_id":"tx1","amount":21999}"#))
        #expect(recorder.all == ["open", "invoice 21", "submit tx1 lnbcrt1"])
    }

    @Test func claimAnnouncesThePaymentAndRefreshesTheWidget() async throws {
        let recorder = Recorder()
        try await jobs(recorder).handle(decode(#"{"notification_type":"lightning_claim_request","payment_hash":"ab","amount_sat":1500,"amount_usd":"1.02"}"#))
        #expect(recorder.all == ["open", "claim", "announce 1500 1.02", "widget"])
    }

    @Test func failedBackupIsReportedAndRethrown() async throws {
        let recorder = Recorder()
        recorder.failBackup = true
        await #expect(throws: Boom.self) {
            try await jobs(recorder).handle(decode(#"{"notification_type":"backup_trigger","notification_k1":"k2"}"#))
        }
        #expect(recorder.all == ["open", "backup", "report backup failure k2"])
    }

    @Test func heartbeatFailureDoesNotFailTheJob() async throws {
        let recorder = Recorder()
        recorder.failHeartbeat = true
        try await jobs(recorder).handle(decode(#"{"notification_type":"heartbeat","notification_id":"h1"}"#))
        #expect(recorder.all == ["heartbeat h1"])
    }
}
