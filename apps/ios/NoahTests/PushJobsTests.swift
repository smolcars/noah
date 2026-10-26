import Foundation
import HTTPTypes
import OpenAPIRuntime
import Testing
import UserNotifications

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
            recurring: { recorder.add("recurring") },
            progressExits: { recorder.add("exits") }))
    }

    private func decode(_ json: String) throws -> PushPayload.NotificationData {
        try JSONDecoder().decode(PushPayload.NotificationData.self, from: Data(json.utf8))
    }

    private func backupTrigger() throws -> PushPayload.NotificationData {
        try decode(#"{"notification_type":"backup_trigger","notification_k1":"k2"}"#)
    }

    /// A backup push under a deadline the test reaches itself with `deadlinePassed()`.
    @MainActor private func execute(_ dispatcher: PushJobs, allowance: Duration, then: @escaping @Sendable () -> Void = {},
                                    completion: @escaping (Bool) -> Void) throws -> BackgroundExecution {
        let notification = try backupTrigger()
        return BackgroundExecution(timeout: .seconds(60), reportAllowance: allowance, completion: completion) {
            try await dispatcher.handle(notification)
            then()
        }
    }

    @MainActor private func waitUntil(_ condition: @MainActor () async -> Bool) async throws {
        let limit = ContinuousClock.now + .seconds(5)
        while !(await condition()), ContinuousClock.now < limit { await Task.yield() }
        try #require(await condition())
    }

    @MainActor @Test func expiryDuringShieldedMaintenanceStopsReportingAndWidget() async throws {
        let recorder = Recorder()
        let gate = RecurringReservationTests.Gate()
        var dependencies = jobs(recorder).dependencies
        dependencies.maintenance = {
            recorder.add("maintenance")
            await Task { await gate.wait() }.value
        }
        let dispatcher = PushJobs(dependencies: dependencies)
        let notification = try decode(#"{"notification_type":"maintenance","notification_k1":"fake"}"#)
        var completed: [Bool] = []
        let execution = BackgroundExecution(completion: { completed.append($0) }) {
            defer { recorder.add("settled") }
            try await dispatcher.handle(notification)
        }
        let limit = ContinuousClock.now + .seconds(3)
        while !(await gate.entered), ContinuousClock.now < limit { await Task.yield() }
        try #require(await gate.entered)
        recorder.add("expire")
        execution.systemExpired()
        await gate.release()
        while !recorder.all.contains("settled"), ContinuousClock.now < limit { await Task.yield() }
        #expect(recorder.all == ["open", "maintenance", "expire", "settled"])
        #expect(completed == [false])
    }

    @Test func recurringWakeOpensWalletThenRunsLocalPolicy() async throws {
        let recorder = Recorder()
        try await jobs(recorder).handle(decode(#"{"notification_type":"recurring_payment_due","due_count":4294967295}"#))
        #expect(recorder.all == ["open", "recurring", "widget"])
    }

    /// Disposable simulator presentation fixture: no seed, native wallet, claim or payment.
    @Test(.enabled(if: ProcessInfo.processInfo.environment["NOAH_SYSTEM_PRESENTATION"] == "1"))
    func systemPresentationFixtures() async throws {
        let center = UNUserNotificationCenter.current()
        #expect(try await center.requestAuthorization(options: [.alert, .badge, .sound]))
        let recorder = Recorder()
        var dependencies = jobs(recorder).dependencies
        dependencies.refreshWidget = {
            let balance = WalletBalance(ark: .init(spendable: 1234, needsRefresh: 0, pendingLightningSend: 0,
                claimableLightningReceive: 0, pendingInRound: 0, pendingExit: 0, pendingBoard: 0,
                pendingArkoorSend: 0, pendingOffboard: 0), onchain: .init(confirmed: 0, pending: 0, total: 0))
            WidgetDataWriter.write(balance: balance, closestExpiryBlocks: 123, variant: .regtest)
            recorder.add("widget")
        }
        try await PushJobs(dependencies: dependencies).handle(decode(#"{"notification_type":"recurring_payment_due","due_count":1}"#))
        #expect(recorder.all == ["open", "recurring", "widget"])
        try await center.add(UNNotificationRequest(identifier: "system-lightning",
            content: PushJobs.receivedContent(sats: 1234, usd: "1.23", unit: .sats),
            trigger: UNTimeIntervalNotificationTrigger(timeInterval: 20, repeats: false)))
    }

    @Test func maintenanceReportsAndRefreshesTheWidget() async throws {
        let recorder = Recorder()
        try await jobs(recorder).handle(decode(#"{"notification_type":"maintenance","notification_k1":"k1"}"#))
        #expect(recorder.all == ["open", "maintenance", "report maintenance success k1", "widget", "exits"])
    }

    @Test func maintenanceSkipsExitProgressOnceItsBudgetIsGone() async throws {
        let recorder = Recorder()
        let budget = RecurringSubmissionBudget()
        var dependencies = jobs(recorder).dependencies
        dependencies.refreshWidget = { recorder.add("widget"); budget.revoke() }
        let notification = try decode(#"{"notification_type":"maintenance","notification_k1":"k1"}"#)
        await #expect(throws: CancellationError.self) {
            try await RecurringSubmissionBudget.$current.withValue(budget) { try await PushJobs(dependencies: dependencies).handle(notification) }
        }
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

    @Test(arguments: [false, true])
    func backupSettlingAfterTheBudgetIsStillReported(fails: Bool) async throws {
        let recorder = Recorder()
        let budget = RecurringSubmissionBudget()
        var dependencies = jobs(recorder).dependencies
        dependencies.backup = { recorder.add("backup"); budget.revoke(); if fails { throw Boom() } }
        var thrown: Error?
        do {
            try await RecurringSubmissionBudget.$current.withValue(budget) { try await PushJobs(dependencies: dependencies).handle(backupTrigger()) }
        } catch { thrown = error }
        #expect(fails ? thrown is Boom : thrown == nil)
        #expect(recorder.all == ["open", "backup", "report backup \(fails ? "failure" : "success") k2"])
    }

    /// Only a settled backup may report after the budget: not one that never started or stopped waiting.
    @Test(arguments: ["open", "open fails", "backup cancelled"])
    func backupThatDidNotSettleIsNotReportedAfterTheBudget(stage: String) async throws {
        let recorder = Recorder()
        let budget = RecurringSubmissionBudget()
        var dependencies = jobs(recorder).dependencies
        dependencies.ensureWalletOpen = {
            recorder.add("open")
            if stage.hasPrefix("open") { budget.revoke() }
            if stage == "open fails" { throw Boom() }
        }
        dependencies.backup = { recorder.add("backup"); budget.revoke(); throw CancellationError() }
        await #expect(throws: (any Error).self) {
            try await RecurringSubmissionBudget.$current.withValue(budget) { try await PushJobs(dependencies: dependencies).handle(backupTrigger()) }
        }
        #expect(recorder.all == (stage == "backup cancelled" ? ["open", "backup"] : ["open"]))
    }

    @MainActor @Test func reportUnderWayAtTheDeadlineHoldsCompletionUntilItEnds() async throws {
        let recorder = Recorder()
        let gate = RecurringReservationTests.Gate()
        var dependencies = jobs(recorder).dependencies
        dependencies.reportJob = { _, _, _ in recorder.add("report"); await gate.wait(); recorder.add("reported") }
        var completed: [Bool] = []
        let execution = try execute(PushJobs(dependencies: dependencies), allowance: .seconds(60)) {
            recorder.add("completed"); completed.append($0)
        }
        try await waitUntil { await gate.entered }
        execution.deadlinePassed()
        #expect(completed.isEmpty)
        await gate.release()
        try await waitUntil { !completed.isEmpty }
        #expect(recorder.all == ["open", "backup", "report", "reported", "completed"])
        #expect(completed == [false])
    }

    @MainActor @Test func hungReportStillCompletesAtItsTimeout() async throws {
        let recorder = Recorder()
        let gate = RecurringReservationTests.Gate()
        var dependencies = jobs(recorder).dependencies
        // Never answers and ignores cancellation.
        dependencies.reportJob = { _, _, _ in recorder.add("report"); await gate.wait() }
        var completed: [Bool] = []
        let execution = try execute(PushJobs(dependencies: dependencies), allowance: .milliseconds(100)) { completed.append($0) }
        try await waitUntil { await gate.entered }
        execution.deadlinePassed()
        try await waitUntil { !completed.isEmpty }
        #expect(completed == [false])
        #expect(recorder.all == ["open", "backup", "report"])
        await gate.release()
    }

    @MainActor @Test func reportCannotStartOnceThePushHasCompleted() async throws {
        let recorder = Recorder()
        let gate = RecurringReservationTests.Gate()
        var dependencies = jobs(recorder).dependencies
        // Its success arrives only after the deadline has completed the push.
        dependencies.backup = { recorder.add("backup"); await gate.wait() }
        var completed: [Bool] = []
        let execution = try execute(PushJobs(dependencies: dependencies), allowance: .seconds(60), then: { recorder.add("handled") }) {
            recorder.add("completed"); completed.append($0)
        }
        try await waitUntil { await gate.entered }
        execution.deadlinePassed()
        #expect(completed == [false])
        await gate.release()
        try await waitUntil { recorder.all.contains("handled") }
        #expect(recorder.all == ["open", "backup", "completed", "handled"])
    }

    @MainActor @Test func admittedReportDoesNotStartOnceThePushIsCancelled() async throws {
        let recorder = Recorder()
        var dependencies = jobs(recorder).dependencies
        // Expiry cancels the work after the backup settled, before the report's body starts.
        dependencies.backup = { recorder.add("backup"); withUnsafeCurrentTask { $0?.cancel() } }
        var completed: [Bool] = []
        _ = try execute(PushJobs(dependencies: dependencies), allowance: .seconds(60), then: { recorder.add("handled") }) {
            completed.append($0)
        }
        try await waitUntil { !completed.isEmpty }
        #expect(recorder.all == ["open", "backup", "handled"])
        #expect(completed == [false])
    }

    actor ReportTransport: ClientTransport {
        var operations: [String] = []
        func send(_ request: HTTPRequest, body: HTTPBody?, baseURL: URL, operationID: String) async throws -> (HTTPResponse, HTTPBody?) {
            operations.append(operationID)
            return (.init(status: .ok), HTTPBody(#"{"success":true}"#))
        }
    }

    @MainActor @Test func expiredReportReleasesWalletAccessWhileLoginIsStillRunning() async throws {
        let recorder = Recorder()
        let now = Date()
        let fake = ServerAuthTests.Fake(nextToken: ServerAuthTests.token(expiresIn: 72 * 3600, now: now))
        fake.holdLogin = true
        let transport = ReportTransport()
        let client = Client(serverURL: URL(string: "https://noah.invalid")!, transport: transport,
            middlewares: [ServerFailureMiddleware(), BearerAuthMiddleware(tokens: AuthTokenProvider(dependencies: fake.dependencies(now: now)))])
        var dependencies = jobs(recorder).dependencies
        // The live report: the bearer middleware has to log in first.
        dependencies.reportJob = { _, _, k1 in
            recorder.add("report")
            _ = try? await client.reportJobStatus(body: .json(.init(
                errorMessage: nil, notificationK1: k1, reportType: .backup, status: .success))).ok
        }
        // Like the live session, wallet access is held until the job returns.
        dependencies.withWalletAccess = { operation in
            defer { recorder.add("access released") }
            try await operation()
        }
        var completed: [Bool] = []
        let execution = try execute(PushJobs(dependencies: dependencies), allowance: .seconds(60)) { completed.append($0) }
        try await waitUntil { fake.sync { fake.gate != nil } }
        execution.systemExpired()
        try await waitUntil { recorder.all.contains("access released") }
        #expect(completed == [false])
        #expect(fake.sync { fake.logins } == 0)
        #expect(await transport.operations.isEmpty)
        // The shared login still finishes and is kept for later callers.
        fake.sync { fake.gate?.resume(); fake.gate = nil }
        try await waitUntil { fake.sync { fake.stored } != nil }
    }

    @MainActor @Test func systemExpiryCancelsAReportUnderWay() async throws {
        let recorder = Recorder()
        var dependencies = jobs(recorder).dependencies
        dependencies.reportJob = { _, _, _ in
            recorder.add("report")
            do { try await Task.sleep(for: .seconds(60)); recorder.add("reported") }
            catch { recorder.add("report cancelled") }
        }
        var completed: [Bool] = []
        let execution = try execute(PushJobs(dependencies: dependencies), allowance: .seconds(60)) { completed.append($0) }
        try await waitUntil { recorder.all.contains("report") }
        execution.systemExpired()
        try await waitUntil { recorder.all.contains("report cancelled") }
        #expect(completed == [false])
        #expect(!recorder.all.contains("reported"))
    }

    @MainActor @Test func reportThatEndsInTimeLeavesTheRestOfThePushAlone() async throws {
        let recorder = Recorder()
        let settling = RecurringReservationTests.Gate()
        var dependencies = jobs(recorder).dependencies
        // The wallet access wrapper keeps settling after the job, as the live session does.
        dependencies.withWalletAccess = { operation in try await operation(); await settling.wait(); recorder.add("settled") }
        var completed: [Bool] = []
        _ = try execute(PushJobs(dependencies: dependencies), allowance: .seconds(1)) { completed.append($0) }
        try await waitUntil { await settling.entered }
        // Past the report's own timeout, which must have been cancelled when it ended.
        try await Task.sleep(for: .milliseconds(1500))
        #expect(completed.isEmpty)
        await settling.release()
        try await waitUntil { !completed.isEmpty }
        #expect(completed == [true])
        #expect(recorder.all == ["open", "backup", "report backup success k2", "settled"])
    }

    @Test func heartbeatFailureDoesNotFailTheJob() async throws {
        let recorder = Recorder()
        recorder.failHeartbeat = true
        try await jobs(recorder).handle(decode(#"{"notification_type":"heartbeat","notification_id":"h1"}"#))
        #expect(recorder.all == ["heartbeat h1"])
    }
}
