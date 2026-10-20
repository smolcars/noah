import BarkBoltFFI
import Foundation
import SwiftUI
import Testing
import UIKit
@testable import Noah

/// Opt-in on a new, dedicated simulator only. The host funds the published address exactly once.
@MainActor @Suite(.enabled(if: ProcessInfo.processInfo.environment["NOAH_RECURRING_LIVE"] == "1"))
struct RecurringLiveTests {
    @Test func realPushAndForegroundPayOnceThenPauseAndCancel() async throws {
        try #require(AppVariant.current == .regtest)
        let location = WalletDataLocation(variant: .regtest)
        try #require(!location.hasAnyData)
        let recipient = try #require(ProcessInfo.processInfo.environment["NOAH_RECURRING_RECIPIENT"])
        let services = AppServices.shared
        let session = services.session
        let mnemonic = try generateMnemonic()
        try await session.createNew(mnemonic: mnemonic, runDaemon: true, appVersion: "recurring-m7-live")
        try services.seed.save(mnemonic)
        try SettingsStorage.shared.setPersistedState(PersistedWalletState(isInitialized: true, isWalletLoaded: true), key: PersistedWalletState.key, version: 0)
        let request = try await session.receiveRequest()
        try request.arkAddress.write(to: URL.documentsDirectory.appending(path: "recurring-live-funding-address.txt"), atomically: true, encoding: .utf8)
        let deadline = ContinuousClock.now + .seconds(180)
        while ContinuousClock.now < deadline {
            try await session.sync()
            if try await session.summary()?.spendableSats ?? 0 >= 10000 { break }
            try await Task.sleep(for: .seconds(1))
        }
        try #require(try await session.summary()?.spendableSats ?? 0 >= 10000)
        let registration = ServerRegistration(variant: .regtest, session: session, client: services.client)
        try await registration.ensureRegistered()
        let executor = RecurringExecutor.shared
        let now = RecurringPayment.now
        var schedule = RecurringPayment(id: UUID().uuidString, label: "Regtest recurring proof", destination: recipient, destinationType: "ark", amountSat: 1000, comment: "", interval: .init(unit: "day", every: 1), startAt: now + 2000, nextRunAt: now + 2000, createdAt: now, updatedAt: now)
        try await executor.save(schedule, editing: false)
        try #require(!executor.syncPending)
        let before = try await session.withOpenWallet { try await $0.wallet.history().map(\.id) }
        while RecurringPayment.now < schedule.startAt { try await Task.sleep(for: .milliseconds(100)) }
        let push = PushJobs(dependencies: .live(services))
        async let waking: Void = push.handle(.recurringPaymentDue(.init(dueCount: 999, notificationType: .recurringPaymentDue)))
        async let foreground: Void = executor.execute()
        _ = try await (waking, foreground)
        await executor.execute(manual: true)
        let after = try await session.withOpenWallet { try await $0.wallet.history().filter { !before.contains($0.id) } }
        try #require(after.count == 1)
        #expect(after[0].status == "successful")
        #expect(after[0].sentToAddresses.reduce(UInt64(0)) { $0 + $1.amountSats } == 1000)
        #expect(executor.schedules.first?.occurrencesPaid == 1)
        let binding = try RecurringStore.binding(location: location)
        let journal = RecurringJournal(file: location.directory.appending(path: "noah-recurring-journal.json"))
        #expect(try journal.load()[schedule.id]?.resolved == true)
        try await executor.pause(schedule.id)
        await executor.execute()
        #expect(executor.schedules.first?.status == "paused")
        try await executor.cancel(schedule.id)
        await executor.execute()
        #expect(executor.schedules.isEmpty)
        let afterAgain = try await session.withOpenWallet { try await $0.wallet.history().filter { !before.contains($0.id) } }
        #expect(afterAgain.count == 1)
        #expect(!executor.syncPending)
        // Leave a future policy for visual inspection of the real production list/editor.
        schedule.id = UUID().uuidString; schedule.label = "Future regtest payment"
        schedule.startAt = RecurringPayment.now + 86_400_000; schedule.nextRunAt = schedule.startAt
        try await executor.save(schedule, editing: false)
        let evidence: [String: String] = [
            "walletFingerprint": binding.wallet.fingerprint, "network": binding.wallet.network,
            "rail": "Ark", "amountSat": "1000", "newSenderMovements": String(after.count),
            "movementID": String(after[0].id), "movementStatus": after[0].status,
            "repeatedTriggersNewMovements": String(afterAgain.count), "pause": "paused; no additional movement",
            "cancel": "removed; no additional movement", "serverSync": "authenticated generated client; save/pause/cancel succeeded"
        ]
        try JSONEncoder().encode(evidence).write(to: URL.documentsDirectory.appending(path: "recurring-live-evidence.json"), options: .atomic)
        let window = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first?.windows.first)
        let previous = window.rootViewController
        window.rootViewController = UIHostingController(rootView: NavigationStack { RecurringPaymentsView() })
        let finish = URL.documentsDirectory.appending(path: "recurring-live-finish")
        let inspectionDeadline = ContinuousClock.now + .seconds(180)
        while !FileManager.default.fileExists(atPath: finish.path), ContinuousClock.now < inspectionDeadline {
            try await Task.sleep(for: .milliseconds(250))
        }
        window.rootViewController = previous
        try await executor.cancel(schedule.id)
        try await session.closeSettled()
    }
}
