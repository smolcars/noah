import BarkBoltFFI
import Foundation
import SwiftUI
import Testing
import UIKit

@testable import Noah

/// Opt-in stages on a dedicated simulator. Host funds through Core and approves the real review.
@MainActor
@Suite(.enabled(if: ProcessInfo.processInfo.environment["NOAH_FFI050_STAGE"] != nil))
struct FFI050LiveTests {
    @Test func acceptance() async throws {
        try #require(AppVariant.current == .regtest)
        let stage = try #require(ProcessInfo.processInfo.environment["NOAH_FFI050_STAGE"])
        let session = WalletSession.shared
        let documents = URL.documentsDirectory
        let seed = KeychainPassword.mnemonic(for: .regtest)
        if stage == "create" || stage == "auto-setup" {
            try #require(!WalletDataLocation(variant: .regtest).hasAnyData)
            let mnemonic = try generateMnemonic()
            try await session.createNew(mnemonic: mnemonic, runDaemon: false, appVersion: "ffi050-live")
            try seed.save(mnemonic)
            try SettingsStorage.shared.setPersistedState(PersistedWalletState(isInitialized: true),
                key: PersistedWalletState.key, version: PersistedWalletState.version)
            var transaction = PersistedTransactionState()
            transaction.setAutoBoarding(stage == "auto-setup")
            if stage == "auto-setup" { transaction.hasAttemptedAutoBoarding = true }
            try SettingsStorage.shared.setPersistedState(transaction,
                key: PersistedTransactionState.key, version: PersistedTransactionState.version)
            try await session.exitDepositAddress().write(to: documents.appending(path: "ffi050-funding.txt"), atomically: true, encoding: .utf8)
            try await session.close()
            return
        }
        guard case .found(let mnemonic) = seed.read() else { throw OnchainFailure.unavailable }
        try await session.ensureOpen(mnemonic: mnemonic, runDaemon: false, appVersion: "ffi050-live")
        if stage == "amount" || stage == "max" || stage == "auto" {
            try await session.sync()
            let quote: OnchainQuote
            if stage == "auto" { quote = try #require(try await session.autoBoardQuote()) }
            else { quote = try await session.quoteOnchain(.board, amount: stage == "max" ? 0 : 30_000) }
            try JSONEncoder().encode(quote).write(to: documents.appending(path: "ffi050-\(stage)-review.json"))
            var completed = false
            var failure: (any Error)?
            let window = try liveWindow()
            window.rootViewController = UIHostingController(rootView: OnchainReviewSheet(quote: quote) {
                Task {
                    do {
                        _ = try await session.executeOnchain(quote)
                        let attempt = try #require(try await OnchainOperationJournal.shared.loadAll().first { $0.id == quote.id })
                        #expect(attempt.txid == quote.prepared?.txid && attempt.receipt?.onchainFee == quote.chainFee)
                        try JSONEncoder().encode(attempt).write(to: documents.appending(path: "ffi050-\(stage)-receipt.json"))
                        try await OnchainOperationJournal.shared.acknowledge(quote.id)
                    } catch { failure = error }
                    completed = true
                }
            }.environment(AppModel()))
            window.makeKeyAndVisible()
            let deadline = ContinuousClock.now.advanced(by: .seconds(110))
            while !completed, ContinuousClock.now < deadline { try await Task.sleep(for: .milliseconds(200)) }
            try #require(completed, "Host must approve the visible review before its two-minute expiry")
            if let failure { throw failure }
        } else if stage == "board-status" {
            let saved = try JSONDecoder().decode(OnchainOperationJournal.Attempt.self,
                from: Data(contentsOf: documents.appending(path: "ffi050-amount-receipt.json")))
            // Re-read a real reviewed receipt after Done, without another submission or wallet sync.
            let file = documents.appending(path: "ffi050-status-journal.json")
            defer { try? FileManager.default.removeItem(at: file) }
            try JSONEncoder().encode([saved]).write(to: file)
            let journal = OnchainOperationJournal(file: file)
            #expect(try await session.checkOnchainAttempt(saved.id, journal: journal))
            let receipt = try #require(try await journal.load()?.receipt)
            if case .confirmed = receipt.chainStatus {} else { Issue.record("Board must be confirmed by chain source") }
            #expect(receipt.txid == saved.txid && receipt.onchainFee == saved.quote.chainFee)
            try JSONEncoder().encode(receipt).write(to: documents.appending(path: "ffi050-board-status.json"))
        } else if stage == "offboard" {
            try await session.sync()
            let destination = try #require(ProcessInfo.processInfo.environment["NOAH_FFI050_RECIPIENT"])
            let payment = SendPayment(destination: destination, method: "onchain", amount: 5000, source: .offchain)
            let controller = SendPaymentController(dependencies: .live(session: session, variant: .regtest))
            let quote = try await session.quoteSend(payment)
            let window = try liveWindow()
            window.rootViewController = UIHostingController(rootView: SendConfirmation(
                payment: payment, quote: quote, controller: controller, cancel: {}, done: {}, onLeave: {}))
            window.makeKeyAndVisible()
            let deadline = ContinuousClock.now.advanced(by: .seconds(110))
            while controller.attempt?.receipt == nil, ContinuousClock.now < deadline {
                try await Task.sleep(for: .milliseconds(200))
            }
            try #require(controller.attempt?.receipt != nil)
            await controller.checkStatus()
            #expect(controller.attempt?.receipt?.chainStatus == .inMempool)
            try JSONEncoder().encode(controller.attempt).write(to: documents.appending(path: "ffi050-offboard-mempool.json"))
        } else if stage == "confirmed" {
            let controller = SendPaymentController(dependencies: .live(session: session, variant: .regtest))
            await controller.checkStatus()
            let receipt = try #require(controller.attempt?.receipt)
            if case .confirmed = receipt.chainStatus {} else { Issue.record("Expected live confirmation height") }
            #expect(receipt.status == .confirmed)
            try JSONEncoder().encode(controller.attempt).write(to: documents.appending(path: "ffi050-offboard-confirmed.json"))
            let window = try liveWindow()
            window.rootViewController = UIHostingController(rootView: SendOutcomeView(controller: controller, done: {}, onLeave: {}))
            window.makeKeyAndVisible()
            try await Task.sleep(for: .seconds(15))
        } else if stage == "cancel" || stage == "reopen" {
            let marker = documents.appending(path: "ffi050-canceled-id.txt")
            let id: String
            if stage == "cancel" {
                try await session.sync()
                id = try #require(try await session.onchainOverview().vtxos.first { $0.eligible }).id
                let start = try await session.quoteOnchain(.startExit, ids: [id])
                _ = try await session.executeOnchain(start)
                try await OnchainOperationJournal.shared.acknowledge(start.id)
                let cancel = try await session.quoteOnchain(.cancel, ids: [id])
                _ = try await session.executeOnchain(cancel)
                try await OnchainOperationJournal.shared.acknowledge(cancel.id)
                try id.write(to: marker, atomically: true, encoding: .utf8)
            } else { id = try String(contentsOf: marker, encoding: .utf8) }
            let active = try await session.withOpenWallet { try await $0.wallet.getExitVtxos() }
            let persisted = try await session.withOpenWallet { try await $0.wallet.listAllExits(includeHistory: true, includeTransactions: true) }
            let row = try #require(persisted.first { $0.vtxoId == id })
            #expect(!active.contains { $0.vtxoId == id })
            let detail = WalletSession.exitModel(row)
            #expect(detail.state == "Canceled" && detail.history.contains("Start"))
            #expect(try await session.onchainOverview().exits.contains { $0.id == id })
            try JSONEncoder().encode(["id": id, "amount": String(row.amountSats), "state": detail.state,
                "history": detail.history.joined(separator: ","), "activeCount": String(active.count)])
                .write(to: documents.appending(path: "ffi050-\(stage).json"))
        } else if stage == "sync" {
            try await session.sync()
            let summary = try #require(try await session.summary())
            try JSONEncoder().encode(["ark": summary.spendableSats, "onchain": summary.balance.onchain.confirmed])
                .write(to: documents.appending(path: "ffi050-balance.json"))
        } else { throw OnchainFailure.invalid }
        try await session.close()
    }

    private func liveWindow() throws -> UIWindow {
        let scene = try #require(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        return try #require(scene.windows.first { $0.isKeyWindow })
    }
}
