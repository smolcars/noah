import BarkBoltFFI
import Foundation
@testable import Noah
import Testing

/// Opt-in stages for bark-bolt-ffi 0.6.0 on a dedicated simulator, against a Bark 0.8 stack selected with
/// NOAH_REGTEST_ARK_URL / NOAH_REGTEST_BITCOIND_URL / NOAH_REGTEST_SERVER_URL. The host funds, pays,
/// cancels and mines between stages; each stage writes non-secret evidence to Documents.
@MainActor
@Suite(.enabled(if: ProcessInfo.processInfo.environment["NOAH_FFI060_STAGE"] != nil))
struct FFI060LiveTests {
    let session = WalletSession.shared
    let environment = ProcessInfo.processInfo.environment

    @Test func acceptance() async throws {
        try #require(AppVariant.current == .regtest)
        let stage = try #require(environment["NOAH_FFI060_STAGE"])
        let seed = KeychainPassword.mnemonic(for: .regtest)
        if stage == "create" {
            try #require(!WalletDataLocation(variant: .regtest).hasAnyData)
            let mnemonic = try generateMnemonic()
            try await session.createNew(mnemonic: mnemonic, runDaemon: false, appVersion: "ffi060-live")
            try seed.save(mnemonic)
            try SettingsStorage.shared.setPersistedState(PersistedWalletState(isInitialized: true),
                                                         key: PersistedWalletState.key, version: PersistedWalletState.version)
            try write("onchain-address.txt", await session.receiveRequest().onchainAddress)
            try write("ark-info.json", DebugJSON.ark(await session.withOpenWallet { try await $0.wallet.arkInfo() }))
            try await session.close()
            return
        }
        guard case .found(let mnemonic) = seed.read() else { throw SendFailure.unresolved }
        try await session.ensureOpen(mnemonic: mnemonic, runDaemon: false, appVersion: "ffi060-live")
        switch stage {
        case "receive": try await receive()
        case "ark": try await arkRoundTrip()
        case "ln-send": try await lightningSend()
        case "ln-fail": try await lightningFailure()
        case "ln-probe": try await lightningOptionsProbe()
        case "offboard": try await offboard()
        case "offboard-confirmed": try await offboardConfirmed()
        case "reopen": try await reopen(mnemonic: mnemonic)
        case "upgrade": try await upgrade()
        default: Issue.record("Unknown stage \(stage)")
        }
        try await session.close()
    }

    /// Core paid the onchain address (one block mined); LND pays this invoice through the server.
    private func receive() async throws {
        func received() async throws -> [Movement] {
            try await history().filter { $0.subsystemName == "bark.lightning_receive" && $0.status == "successful" }
        }
        if try await received().isEmpty {
            var request = try await session.receiveRequest()
            request = try await session.receiveInvoice(for: request, amountSat: 30_000, description: "")
            try write("invoice.txt", try #require(request.lightningInvoice))
            try await poll(seconds: 240) {
                try await session.sync(includingLightningClaims: true)
                return try await !received().isEmpty
            }
        }
        try await session.sync()
        let summary = try #require(try await session.summary())
        let movement = try #require(try await received().first)
        // The server keeps its Lightning receive fee (100 sats + 2,000 ppm on noah-bark08).
        #expect(summary.spendableSats > 0 && summary.spendableSats == UInt64(movement.effectiveBalanceSats))
        #expect(summary.balance.onchain.confirmed >= 200_000)
        try evidence("receive", ["arkSpendable": summary.spendableSats, "onchainConfirmed": summary.balance.onchain.confirmed,
                                 "lightningReceiveMovementID": UInt64(movement.id), "lightningReceiveFee": movement.offchainFeeSats])
    }

    /// Ark send to a second in-process wallet, which pays part of it back.
    private func arkRoundTrip() async throws {
        let peer = try await Peer.create()
        let peerAddress = try await peer.wallet.newAddress()
        var payment = SendPayment(destination: peerAddress, method: "ark", amount: 8_000)
        payment.repeatMetadata = try SendPayment.metadata(destination: "ffi060@localhost.com", comment: "exact movement",
                                                          amount: "8000", fiatCurrency: nil)
        let (controller, journal) = try await send(payment)
        guard case .succeeded(let receipt) = controller.state else { Issue.record("Ark send did not succeed: \(controller.state)"); return }
        let id = try #require(receipt.movementID)
        #expect(receipt.proof == .submitReturnedArkSuccess && journal.receipt?.movementID == id)
        let movements = try await history()
        let movement = try #require(movements.first { $0.id == id })
        #expect(movement.subsystemName == "bark.arkoor" && movement.subsystemKind == "send")
        #expect(movement.sentToAddresses.contains { $0.paymentMethod.value == peerAddress && $0.amountSats == 8_000 })
        // History metadata lands on exactly the returned movement.
        #expect(movements.filter { $0.metadataJson.contains("repeat_payment") }.map(\.id) == [id])
        controller.acknowledge()

        try await poll(seconds: 120) {
            try await peer.wallet.sync()
            return try await peer.wallet.balance().spendableSats >= 8_000
        }
        let back = try await session.receiveRequest().arkAddress
        let peerMovement = try await peer.wallet.sendArkoorPayment(arkAddress: back, amountSats: 3_000)
        #expect(try await peer.wallet.history().contains { $0.id == peerMovement && $0.subsystemKind == "send" })
        try await poll(seconds: 120) {
            try await session.sync()
            return try await history().contains { $0.subsystemName == "bark.arkoor" && $0.subsystemKind == "receive" }
        }
        try evidence("ark", ["movementID": UInt64(id), "journalMovementID": UInt64(journal.receipt?.movementID ?? 0),
                             "peerSendMovementID": UInt64(peerMovement),
                             "spendable": try await session.summary()?.spendableSats ?? 0])
    }

    private func lightningSend() async throws {
        let invoice = try #require(environment["NOAH_FFI060_INVOICE"])
        let hash = try #require(PaymentEncoding.invoice(invoice)?.hash)
        let (controller, journal) = try await send(.init(destination: invoice, method: "lightning", amount: 5_000))
        guard case .succeeded(let receipt) = controller.state else { Issue.record("Lightning send did not succeed: \(controller.state)"); return }
        let id = try #require(receipt.movementID)
        #expect(receipt.proof == .actualMovement && receipt.paymentHash == hash && journal.receipt?.movementID == id)
        let movement = try #require(try await history().first { $0.id == id })
        #expect(movement.subsystemName == "bark.lightning_send" && movement.status == "successful" && movement.paymentHash == hash)
        controller.acknowledge()
        try evidence("ln-send", ["movementID": UInt64(id), "fee": receipt.fee ?? 0])
    }

    /// 60 s is also captaind's default, so a distinct value shows the SDK forwards retryForSecs (host greps its span).
    private func lightningOptionsProbe() async throws {
        let invoice = try #require(environment["NOAH_FFI060_INVOICE"])
        let status = try await session.withOpenWallet {
            try await $0.wallet.payLightningInvoice(invoice: invoice, amountSats: nil, options: .init(wait: true, retryForSecs: 45))
        }
        guard case .paid = status else { Issue.record("Probe payment did not complete"); return }
    }

    /// The host holds the HTLC on LND, then cancels it: the payment fails and Ark refunds the coins.
    private func lightningFailure() async throws {
        let invoice = try #require(environment["NOAH_FFI060_INVOICE"])
        let hash = try #require(PaymentEncoding.invoice(invoice)?.hash)
        try await session.sync()
        let before = try #require(try await session.summary())
        let started = ContinuousClock.now
        let (controller, journal) = try await send(.init(destination: invoice, method: "lightning", amount: 2_000))
        let elapsed = ContinuousClock.now - started
        #expect(controller.state == .failed && journal.failed)
        let movement = try #require(try await history().first { $0.paymentHash == hash && $0.subsystemName == "bark.lightning_send" })
        #expect(movement.status == "failed")
        try await poll(seconds: 60) {
            try await session.sync()
            let after = try await session.summary()?.balance.ark
            return after?.spendable == before.balance.ark.spendable && after?.pendingLightningSend == 0
        }
        controller.acknowledge()
        try evidence("ln-fail", ["movementID": UInt64(movement.id), "spendableBefore": before.spendableSats,
                                 "spendableAfter": try await session.summary()?.spendableSats ?? 0,
                                 "seconds": UInt64(elapsed.components.seconds)])
    }

    /// Ark → onchain (sendOnchain): the receipt keeps the returned txid and movement id.
    private func offboard() async throws {
        let destination = try #require(environment["NOAH_FFI060_RECIPIENT"])
        let (controller, journal) = try await send(.init(destination: destination, method: "onchain", amount: 10_000, source: .offchain))
        guard case .succeeded(let receipt) = controller.state else { Issue.record("Offboard did not succeed: \(controller.state)"); return }
        let txid = try #require(receipt.txid)
        let id = try #require(receipt.movementID)
        #expect(receipt.proof == .submitReturnedTxid && journal.receipt?.movementID == id && journal.receipt?.txid == txid)
        let movement = try #require(try await history().first { $0.id == id })
        #expect(movement.subsystemName == "bark.offboard" && movement.subsystemKind == "send_onchain")
        #expect(movement.metadataJson.contains(txid))
        await controller.checkStatus()
        #expect(controller.attempt?.receipt?.chainStatus == .inMempool)
        #expect(controller.attempt?.receipt?.amount == 10_000 && controller.attempt?.receipt?.movementID == id)
        try write("offboard-txid.txt", txid)
        try evidence("offboard", ["movementID": UInt64(id), "amount": controller.attempt?.receipt?.amount ?? 0,
                                  "fee": controller.attempt?.receipt?.fee ?? 0])
    }

    /// After the host mines a block, the restarted journal resolves by id and txid without resubmitting.
    private func offboardConfirmed() async throws {
        let controller = SendPaymentController(dependencies: .live(session: session, variant: .regtest))
        let saved = try #require(controller.attempt?.receipt)
        await controller.checkStatus()
        let receipt = try #require(controller.attempt?.receipt)
        switch receipt.chainStatus {
        case .confirmed: break
        default: Issue.record("Expected a confirmed offboard")
        }
        #expect(receipt.status == .confirmed && receipt.movementID == saved.movementID && receipt.txid == saved.txid)
        controller.acknowledge()
        #expect(!controller.locked)
        try evidence("offboard-confirmed", ["movementID": UInt64(receipt.movementID ?? 0)])
    }

    /// Close and reopen: history, balances and identity survive; debug info is display-only.
    private func reopen(mnemonic: String) async throws {
        try await session.sync()
        let before = try #require(try await session.summary())
        let ids = Set(try await history().map(\.id))
        try await session.close()
        try await session.ensureOpen(mnemonic: mnemonic, runDaemon: false, appVersion: "ffi060-live")
        let after = try #require(try await session.summary())
        #expect(after.fingerprint == before.fingerprint && after.movementCount == before.movementCount)
        #expect(after.balance.ark.spendable == before.balance.ark.spendable)
        #expect(Set(try await history().map(\.id)) == ids)
        let info = try await session.withOpenWallet { try await $0.wallet.debugInfo() }
        #expect(info.network == .regtest && !info.mailboxId.isEmpty && !info.vtxoXpub.isEmpty)
        _ = try DebugJSON.debugInfo(info)
        // The xpub never reaches the app log that Settings exports.
        let logs = try await LogFile.shared.checkedLinesForExport()
        #expect(!logs.isEmpty && !logs.contains { $0.contains(info.vtxoXpub) })
        try evidence("reopen", ["movements": UInt64(after.movementCount), "spendable": after.spendableSats,
                                "onchainConfirmed": after.balance.onchain.confirmed])
    }

    /// In-place upgrade: a wallet funded and used by the bark-bolt-ffi 0.5.0 build opens unchanged.
    private func upgrade() async throws {
        let baseline = try #require(try JSONSerialization.jsonObject(with: Data(contentsOf:
            URL.documentsDirectory.appending(path: "ffi060-upgrade-baseline.json"))) as? [String: Any])
        func rows() async throws -> [[String: String]] {
            try await history().sorted { $0.id < $1.id }.map {
                ["id": "\($0.id)", "status": $0.status, "subsystem": $0.subsystemName, "kind": $0.subsystemKind,
                 "intended": "\($0.intendedBalanceSats)", "effective": "\($0.effectiveBalanceSats)", "fee": "\($0.offchainFeeSats)"]
            }
        }
        let expected = try #require(baseline["movements"] as? [[String: String]])
        let summary = try #require(try await session.summary())
        #expect(summary.fingerprint == baseline["fingerprint"] as? String)
        #expect(summary.spendableSats == (baseline["spendable"] as? UInt64))
        #expect(try await rows() == expected)
        try await session.sync()
        #expect(try await rows() == expected)
        #expect(try await session.summary()?.spendableSats == (baseline["spendable"] as? UInt64))
        try evidence("upgrade", ["movements": UInt64(expected.count), "spendable": summary.spendableSats])
    }

    // MARK: Helpers

    private func send(_ payment: SendPayment) async throws -> (SendPaymentController, SendPaymentController.Attempt) {
        let controller = SendPaymentController(dependencies: .live(session: session, variant: .regtest))
        try #require(controller.state == .ready)
        controller.submit(payment, quote: try await session.quoteSend(payment))
        let deadline = ContinuousClock.now.advanced(by: .seconds(240))
        while controller.state == .submitting || controller.checking, ContinuousClock.now < deadline {
            try await Task.sleep(for: .milliseconds(200))
        }
        let file = WalletDataLocation(variant: .regtest).directory.appending(path: "noah-send-attempt.json")
        let journal = try JSONDecoder().decode(SendPaymentController.Attempt.self, from: Data(contentsOf: file))
        return (controller, journal)
    }

    private func history() async throws -> [Movement] {
        try await session.withOpenWallet { try await $0.wallet.history() }
    }

    private func poll(seconds: Int, _ done: () async throws -> Bool) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(seconds))
        while ContinuousClock.now < deadline {
            if try await done() { return }
            try await Task.sleep(for: .seconds(2))
        }
        try #require(try await done(), "Timed out after \(seconds) s")
    }

    private func write(_ name: String, _ text: String) throws {
        try text.write(to: URL.documentsDirectory.appending(path: "ffi060-\(name)"), atomically: true, encoding: .utf8)
    }

    private func evidence(_ stage: String, _ values: [String: UInt64]) throws {
        try JSONEncoder().encode(values).write(to: URL.documentsDirectory.appending(path: "ffi060-\(stage).json"))
    }

    /// A throwaway second wallet in a temporary directory; its phrase never leaves memory.
    struct Peer: @unchecked Sendable {
        let wallet: Wallet
        let onchain: OnchainWallet
        static func create() async throws -> Self {
            let directory = FileManager.default.temporaryDirectory.appending(path: "ffi060-peer-\(UUID().uuidString)")
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let configuration = WalletConfiguration.base(for: .regtest)
            let config = configuration.sdkConfig(userAgent: "noah-ffi060-peer/1")
            let mnemonic = try generateMnemonic()
            let onchain = try await OnchainWallet.default(network: .regtest, mnemonic: mnemonic, config: config, datadir: directory.path)
            let wallet = try await Wallet.openWithOnchain(
                network: .regtest, mnemonicOrSeed: mnemonic, config: config,
                args: WalletOpenArgs(runDaemon: false, datadir: directory.path, createIfNotExists: true), onchain: onchain)
            return Self(wallet: wallet, onchain: onchain)
        }
    }
}
