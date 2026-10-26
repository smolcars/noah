import BarkBoltFFI
import Foundation
import SwiftUI
import Testing
import UIKit

@testable import Noah

/// Fresh dedicated simulator only. Host funds the published address, drives Back/Confirm,
/// checks Core, and kills/relaunches the app with the final receipt unacknowledged.
@MainActor
@Suite(.enabled(if: ProcessInfo.processInfo.environment["NOAH_PREPARED_LIVE"] == "1"))
struct SendPreparedLiveTests {
    @Test func preparedReviewCancelEnteredAndMax() async throws {
        try #require(AppVariant.current == .regtest)
        let recipient = try #require(ProcessInfo.processInfo.environment["NOAH_PREPARED_RECIPIENT"])
        let session = WalletSession.shared
        try #require(!WalletDataLocation(variant: .regtest).hasAnyData)
        let creator = WalletCreator(dependencies: .live(session: session, variant: .regtest, appVersion: "prepared-live"))
        try await creator.createOrResume()
        try SettingsStorage.shared.setPersistedState(PersistedWalletState(isInitialized: true),
            key: PersistedWalletState.key, version: PersistedWalletState.version)
        let documents = URL.documentsDirectory
        let request = try await session.receiveRequest()
        try request.onchainAddress.write(to: documents.appending(path: "prepared-fund.txt"), atomically: true, encoding: .utf8)
        let deadline = ContinuousClock.now.advanced(by: .seconds(240))
        while ContinuousClock.now < deadline {
            try await session.sync()
            if try await session.summary()?.balance.onchain.confirmed ?? 0 >= 100000 { break }
            try await Task.sleep(for: .seconds(1))
        }
        try #require(try await session.summary()?.balance.onchain.confirmed ?? 0 >= 100000)
        let controller = SendPaymentController(dependencies: .live(session: session, variant: .regtest))
        var payment = SendPayment(destination: recipient, method: "onchain", amount: 5000, source: .onchain)
        let scene = try #require(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let window = try #require(scene.windows.first { $0.isKeyWindow })
        let canceledQuote = try await session.quoteSend(payment)
        var backedOut = false
        window.rootViewController = UIHostingController(rootView: SendConfirmation(
            payment: payment, quote: canceledQuote, controller: controller,
            cancel: { Task { await session.discardSend(canceledQuote); backedOut = true } }, done: {}, onLeave: {}))
        window.makeKeyAndVisible()
        try JSONEncoder().encode(canceledQuote).write(to: documents.appending(path: "prepared-cancel-review.json"))
        try await waitUntil { backedOut }
        #expect(await session.sendPreparations.isEmpty)
        let staleReview = try await session.quoteSend(payment)
        var expiredReview = staleReview
        expiredReview.prepared?.reviewedAt = ContinuousClock.now - .seconds(121)
        await #expect(throws: SendFailure.feeChanged) {
            _ = try await session.prepareSend(payment, quote: expiredReview)
        }
        var edited = payment
        edited.amount += 1
        await #expect(throws: SendFailure.feeChanged) {
            _ = try await session.prepareSend(edited, quote: staleReview)
        }
        // All close/suspend/delete paths use closeSettled -> close, even while the daemon retains BDK.
        try await session.closeSettled()
        #expect(await session.sendPreparations.isEmpty)
        try await creator.createOrResume()
        await #expect(throws: SendFailure.feeChanged) {
            _ = try await session.prepareSend(payment, quote: staleReview)
        }
        let enteredQuote = try await session.quoteSend(payment)
        #expect(enteredQuote.amount == canceledQuote.amount && enteredQuote.fee == canceledQuote.fee)
        try #require(enteredQuote.prepared != nil)
        window.rootViewController = UIHostingController(rootView: SendConfirmation(
            payment: payment, quote: enteredQuote, controller: controller, cancel: {}, done: {}, onLeave: {}))
        try JSONEncoder().encode(enteredQuote).write(to: documents.appending(path: "prepared-entered-review.json"))
        try await waitUntil { if case .succeeded = controller.state { return true }; return false }
        let enteredAttempt = try #require(controller.attempt)
        #expect(enteredAttempt.receipt?.txid == enteredQuote.prepared?.txid)
        #expect(enteredAttempt.receipt?.amount == enteredQuote.amount && enteredAttempt.receipt?.fee == enteredQuote.fee)
        try JSONEncoder().encode(enteredAttempt).write(to: documents.appending(path: "prepared-entered-receipt.json"))
        // Host inspects the receipt before letting this harness proceed to MAX.
        try await waitUntil { FileManager.default.fileExists(atPath: documents.appending(path: "prepared-max-go").path) }
        controller.acknowledge()
        try #require(!controller.locked)
        payment.max = true
        let maxQuote = try await session.quoteSend(payment)
        #expect(maxQuote.amount > 0 && maxQuote.prepared != nil)
        window.rootViewController = UIHostingController(rootView: SendConfirmation(
            payment: payment, quote: maxQuote, controller: controller, cancel: {}, done: {}, onLeave: {}))
        try JSONEncoder().encode(maxQuote).write(to: documents.appending(path: "prepared-max-review.json"))
        try await waitUntil { if case .succeeded = controller.state { return true }; return false }
        let maxAttempt = try #require(controller.attempt)
        #expect(maxAttempt.receipt?.txid == maxQuote.prepared?.txid)
        #expect(maxAttempt.receipt?.amount == maxQuote.amount && maxAttempt.receipt?.fee == maxQuote.fee)
        try JSONEncoder().encode(maxAttempt).write(to: documents.appending(path: "prepared-max-receipt.json"))
        // Leave the journal for real kill/relaunch. Do not acknowledge this receipt.
        try await session.close()
    }

    private func waitUntil(_ condition: @MainActor () -> Bool) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(240))
        while !condition(), ContinuousClock.now < deadline { try await Task.sleep(for: .milliseconds(200)) }
        try #require(condition(), "Host action or wallet completion timed out; never retry the send blindly")
    }
}
