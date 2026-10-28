import BarkBoltFFI
import Foundation
@testable import Noah
import SwiftUI
import Testing
import UIKit

/// Leaves the successful Ark journal unacknowledged for host-driven kill/relaunch verification.
/// Only run on a fresh dedicated simulator; fund its published address exactly once.
@MainActor
@Suite(.enabled(if: ProcessInfo.processInfo.environment["NOAH_SEND_ROUND4_LIVE"] == "1"))
struct SendRoundFourLiveTests {
    @Test func arkReceiptBeforeDone() async throws {
        try #require(AppVariant.current == .regtest)
        let recipient = try #require(ProcessInfo.processInfo.environment["NOAH_SEND_ARK_RECIPIENT"])
        let session = WalletSession.shared
        try #require(!WalletDataLocation(variant: .regtest).hasAnyData)
        let creator = WalletCreator(dependencies: .live(session: session, variant: .regtest, appVersion: "send-round4-live"))
        try await creator.createOrResume()
        try SettingsStorage.shared.setPersistedState(PersistedWalletState(isInitialized: true),
                                                     key: PersistedWalletState.key, version: PersistedWalletState.version)
        let documents = URL.documentsDirectory
        let request = try await session.receiveRequest()
        try request.arkAddress.write(to: documents.appending(path: "send-round4-funding-address.txt"), atomically: true, encoding: .utf8)
        let deadline = ContinuousClock.now.advanced(by: .seconds(180))
        while ContinuousClock.now < deadline {
            try await session.sync()
            if try await session.summary()?.spendableSats ?? 0 >= 20000 { break }
            try await Task.sleep(for: .seconds(1))
        }
        try #require(try await session.summary()?.spendableSats ?? 0 >= 20000)
        let controller = SendPaymentController(dependencies: .live(session: session, variant: .regtest))
        try #require(!controller.locked)
        let payment = SendPayment(destination: recipient, method: "ark", amount: 1000)
        controller.submit(payment, quote: try await session.quoteSend(payment))
        try await SendRoundThreeLiveTests().waitUntil { controller.state != .submitting }
        guard case .succeeded(let receipt) = controller.state else {
            Issue.record("Ark payment did not return success; inspect the journal, never retry")
            try await session.close()
            return
        }
        #expect(receipt.proof == .submitReturnedArkSuccess && receipt.amount == 1000)
        let restarted = SendPaymentController(dependencies: .live(session: session, variant: .regtest))
        #expect(restarted.state == controller.state && restarted.locked && !restarted.canOverride)
        let scene = try #require(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let window = try #require(scene.keyWindow)
        window.rootViewController = UIHostingController(rootView: SendOutcomeView(controller: controller, done: {}, onLeave: {}))
        window.makeKeyAndVisible()
        try JSONEncoder().encode(try #require(controller.attempt)).write(to: documents.appending(path: "send-round4-ark-evidence.json"))
        try Data().write(to: documents.appending(path: "send-round4-ark-ready"))
        let finish = documents.appending(path: "send-round4-finish")
        try await SendRoundThreeLiveTests().waitUntil { FileManager.default.fileExists(atPath: finish.path) }
        // No Done/acknowledge: a real app relaunch must still show this successful receipt.
        try await session.close()
    }
}
