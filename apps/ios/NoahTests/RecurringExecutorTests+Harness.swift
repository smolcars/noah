import BarkBoltFFI
import Foundation
import MMKV
@testable import Noah
import Testing

extension RecurringExecutorTests {
    final class Locked<Value>: @unchecked Sendable {
        private let lock = NSLock()
        private var value: Value
        init(_ value: Value) { self.value = value }
        func withLock<T>(_ body: (inout Value) throws -> T) rethrows -> T { try lock.withLock { try body(&value) } }
    }
    @MainActor final class Harness {
        var settings: SettingsStorage
        var store: RecurringStore
        let directory: URL
        let journal: RecurringJournal
        let owner: Locked<RecurringStore.Binding>
        let stopped = Locked(false)
        let storeWriteFails = Locked(false)
        let calls = Locked(0)
        var saved: SendPaymentController.Attempt?
        var writeReceiptFails = false
        var writeRemovalFails = false
        var ambiguous = false
        var arkMovementID: UInt32?
        var resolution: SendPaymentController.Resolution = .uncertain
        var statusChecks = 0
        var fee: UInt64 = 0
        var method = "ark"
        var fees: LightningSendFees?
        var feeFetches = 0
        var feeFetchFails = false
        var notified: [RecurringPayment] = []
        var notificationHook: (@MainActor (RecurringPayment) async throws -> Void)?
        var reservation: (@MainActor (@escaping @MainActor @Sendable () async -> Void) async throws -> Void)?
        var preflight: (@MainActor () async throws -> Void)?
        var beforePay: (@MainActor () async throws -> Void)?
        var afterPay: (@MainActor () async throws -> Void)?
        var syncHook: (@MainActor ([Components.Schemas.RecurringPaymentScheduleEntry]) async throws -> Void)?
        var sentEntries: [[Components.Schemas.RecurringPaymentScheduleEntry]] = []
        lazy var controller = makeController()
        lazy var executor = makeExecutor()
        let now = 1_800_000_000_000.0
        let zone = Locked(TimeZone.current)
        var reminders: [RecurringPayment] = []
        let clock = Locked(1_800_000_000_000.0)
        let lightningDestination: String
        init(journalWrite: (@Sendable (Data, URL) throws -> Void)? = nil) throws {
            SettingsStorage.bootstrap()
            lightningDestination = try SendLightningSafetyTests().payment().destination
            directory = FileManager.default.temporaryDirectory.appending(path: "recurring-executor-\(UUID())/noah-data-regtest")
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            settings = try .init(rootPath: directory.appending(path: "mmkv").path, instanceID: UUID().uuidString)
            let settings = settings, storeWriteFails = storeWriteFails
            store = .init(settings: settings, write: {
                if storeWriteFails.withLock({ $0 }) { throw CocoaError(.fileWriteUnknown) }
                try settings.setDurably($0, forKey: RecurringStore.key)
            })
            journal = .init(file: directory.appending(path: "journal.json"), persist: journalWrite ?? { try RecurringStore.durableWrite($0, to: $1) })
            owner = Locked(.init(wallet: .init(fingerprint: "test", network: "regtest"), instance: UUID()))
            let owner = owner
            store.protectPolicies(file: journal.file.appendingPathExtension("policies"), sync: { _ in }, binding: { owner.withLock { $0 } })
            rebuild()
        }
        func rebuild() {
            controller = makeController()
            executor = makeExecutor()
        }
        private func makeController() -> SendPaymentController {
            let owner = owner, calls = calls
            return SendPaymentController(dependencies: .init(
                prepare: { _, _ in .init(movementIDs: [], walletIdentity: owner.withLock { $0.wallet }) },
                pay: { _, _, _ in Issue.record("Recurring must use guarded Send submission"); throw SendFailure.unresolved },
                reconcile: { [self] _ in await reconcile() },
                read: { [self] in saved },
                write: { [self] in
                    if writeReceiptFails && $0?.receipt != nil { throw CocoaError(.fileWriteUnknown) }
                    if writeRemovalFails && $0 == nil { throw CocoaError(.fileWriteUnknown) }
                    saved = $0
                },
                identity: { owner.withLock { $0.wallet } },
                withRecurringReservation: { [self] work in try await reserve(work) },
                guardedPay: { [self] payment, _, _, guardPolicy in
                    try await beforePayment()
                    try guardPolicy()
                    calls.withLock { $0 += 1 }
                    try await afterPayment()
                    if await isAmbiguous() { throw URLError(.timedOut) }
                    if payment
                        .method == "ark" { return .init(amount: payment.amount, movementID: await arkReceiptMovementID(), proof: .submitReturnedArkSuccess) }
                    return .init(
                        amount: payment.amount,
                        paymentHash: PaymentEncoding.invoice(payment.destination)?.hash ?? "test-hash",
                        movementID: 1,
                        fee: 0,
                        proof: .actualMovement)
                }))
        }
        private func makeExecutor() -> RecurringExecutor {
            let owner = owner, stopped = stopped
            return .init(dependencies: .init(store: store, journal: journal, controller: controller,
                                             ensureOpen: {}, binding: { owner.withLock { $0 } }, allowed: {
                                                 if stopped.withLock({ $0 }) { throw AppServices.WalletSuspended() }
                                             }, quote: { [self] schedule in
                                                 try await beforeQuote()
                                                 let fees = await RecurringRecipient.lightningFees { [self] in
                                                     try await fetchFees()
                                                 }
                                                 return await quoted(schedule, fees: fees)
                                             }, automationGeneration: { [settings] in settings.string(forKey: WalletLifecycle.automationStopKey) },
                                             zone: { [zone] in zone.withLock { $0 } }, now: { [clock] in clock.withLock { $0 } },
                                             sync: { [self] entries in try await sync(entries) },
                                             notifications: { [self] schedules, result in await notification(schedules, result) }))
        }
        func fetchFees() throws -> LightningSendFees? {
            feeFetches += 1
            if feeFetchFails { throw URLError(.notConnectedToInternet) }
            return fees
        }
        func quoted(_ schedule: RecurringPayment, fees: LightningSendFees) -> RecurringExecutor.Quote {
            .init(
                payment: SendPayment(
                    destination: method == "lightning" ? lightningDestination : schedule.destination,
                    method: method,
                    amount: schedule.amountSat),
                quote: .init(amount: schedule.amountSat, fee: fee, total: schedule.amountSat + fee, walletIdentity: owner.withLock { $0.wallet }),
                fees: fees)
        }
        func notification(_ schedules: [RecurringPayment], _ result: RecurringPayment?) async {
            if let result { notified.append(result); try? await notificationHook?(result) } else { reminders = schedules }
        }
        func reserve(_ work: @escaping @MainActor @Sendable () async -> Void) async throws {
            if let reservation { try await reservation(work) } else { await work() }
        }
        func beforeQuote() async throws { try await preflight?() }
        func beforePayment() async throws { try await beforePay?() }
        func afterPayment() async throws { try await afterPay?() }
        func isAmbiguous() -> Bool { ambiguous }
        func arkReceiptMovementID() -> UInt32? { arkMovementID }
        func reconcile() -> SendPaymentController.Resolution { statusChecks += 1; return resolution }
        func sync(_ entries: [Components.Schemas.RecurringPaymentScheduleEntry]) async throws { sentEntries.append(entries); try await syncHook?(entries) }
        func add() throws -> RecurringPayment {
            var schedule = RecurringScheduleTests.schedule(start: now - 4 * 86_400_000)
            schedule.destinationType = "ark"; schedule.destination = "ark-test-destination"
            schedule.interval = .init(unit: "day", every: 1)
            try store.upsert(schedule)
            return schedule
        }
        func marker(_ schedule: RecurringPayment, mmkv: Bool) throws {
            let binding = owner.withLock { $0 }
            try store.bind(binding)
            let plan = schedule.plan(now: now)
            try journal.save(.init(binding: binding, scheduleID: schedule.id, plan: plan, markedAt: now))
            if mmkv {
                var schedule = schedule; schedule.inFlight = try .init(occurrenceIndex: #require(plan.occurrenceIndex), startedAt: now)
                try store.upsert(schedule)
            }
        }
    }
}
