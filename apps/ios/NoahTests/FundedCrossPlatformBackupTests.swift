import BarkBoltFFI
import Foundation
import OpenAPIRuntime
import OpenAPIURLSession
import Testing

@testable import Noah

/// Opt-in, owned simulator only. The host keeps phrases/encrypted files in memory.
@Suite(.enabled(if: ProcessInfo.processInfo.environment["NOAH_FUNDED_BACKUP_URL"] != nil), .serialized)
struct FundedCrossPlatformBackupTests {
    private var base: URL { URL(string: ProcessInfo.processInfo.environment["NOAH_FUNDED_BACKUP_URL"]!)! }

    private func request(_ path: String, _ body: [String: Any]? = nil) async throws -> Data {
        var request = URLRequest(url: base.appending(path: path))
        request.timeoutInterval = 180
        if let body {
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let (data, response) = try await URLSession.shared.data(for: request)
        try #require((response as? HTTPURLResponse)?.statusCode == 200, "Host operation failed")
        return data
    }

    private func state(_ session: WalletSession) async throws -> [String: Any] {
        let summary = try #require(try await session.summary())
        let history = try await session.transactionHistory().sorted { $0.id < $1.id }.map { row -> [String: Any] in
            var result: [String: Any] = ["id": row.id, "amount": row.amount, "type": row.type, "direction": row.direction]
            if let id = row.movementId { result["movementId"] = id }
            if let status = row.movementStatus { result["status"] = status }
            if let kind = row.movementKind { result["kind"] = kind }
            if let fee = row.offchainFeeSat { result["fee"] = fee }
            return result
        }
        return ["fingerprint": summary.fingerprint, "movements": summary.movementCount,
                "balance": try JSONSerialization.jsonObject(with: JSONEncoder().encode(summary.balance.details)), "history": history]
    }

    @Test func fundedTransfer() async throws {
        try #require(AppVariant.current == .regtest)
        let mode = ProcessInfo.processInfo.environment["NOAH_FUNDED_BACKUP_MODE"]!
        let root = FileManager.default.temporaryDirectory.appending(path: "funded-backup-\(UUID())")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let location = WalletDataLocation(variant: .regtest, documentsDirectory: root)
        let session = WalletSession(variant: .regtest, location: location)
        let seed = KeychainPassword(service: "com.noah.tests.funded.\(UUID())", account: "noah")
        defer { seed.remove() }
        do {
            if mode == "produce" {
                let phrase = try generateMnemonic()
                try await session.createNew(mnemonic: phrase, runDaemon: true, appVersion: "funded-backup-test")
                let address = try await session.receiveRequest().onchainAddress
                _ = try await request("onchain", ["address": address])
                var previous: UInt64 = 0
                for amount: UInt64 in [31_000, 7_000] {
                    let invoice = try await session.bolt11Invoice(amountSats: amount)
                    _ = try await request("pay", ["invoice": invoice])
                    let deadline = ContinuousClock.now + .seconds(120)
                    while ContinuousClock.now < deadline {
                        try await session.claimLightningReceives()
                        try await session.sync()
                        if try await session.summary()!.spendableSats > previous { break }
                        try await Task.sleep(for: .seconds(1))
                    }
                    let funded = try #require(try await session.summary())
                    try #require(funded.spendableSats > previous, "Lightning receive did not settle")
                    previous = funded.spendableSats
                    _ = try await request("paid")
                }
                let funded = try #require(try await session.summary())
                try #require(funded.movementCount >= 2 && funded.balance.onchain.confirmed == 10_000)
                let before = try await state(session)
                let service = BackupService(session: session, client: Client(serverURL: base, transport: URLSessionTransport()),
                    readMnemonic: { phrase }, scratch: ScratchSpace(root: root.appending(path: "scratch")))
                let export = try await service.exportEncryptedBackup(to: root.appending(path: "ios.noahbackup"))
                try await session.close()
                _ = try await request("export/ios", ["phrase": phrase, "state": before,
                    "fileBase64": Data(contentsOf: export.url).base64EncodedString()])
            } else {
                let input = try #require(try JSONSerialization.jsonObject(with: await request("input/android")) as? [String: Any])
                // Never use #require/#expect on the phrase: assertion diagnostics must stay public.
                guard let phrase = input["phrase"] as? String else { throw CocoaError(.fileReadCorruptFile) }
                let restorer = WalletRestorer(variant: .regtest, session: session, location: location, seed: seed,
                    makeClient: { _ in Client(serverURL: base.appending(path: "android"), transport: URLSessionTransport()) }, walletChanged: {})
                let outcome = try await restorer.restore(mnemonic: phrase)
                try #require(!outcome.cleanupPending && !location.restorePending)
                let seedMatches = seed.read() == .found(phrase)
                try #require(seedMatches)
                try await session.ensureOpen(mnemonic: phrase, runDaemon: true, appVersion: "funded-backup-test")
                _ = try await request("check/android/before-sync", ["state": try await state(session)])
                try await session.sync()
                _ = try await request("check/android/after-sync", ["state": try await state(session)])
                let destination = String(decoding: try await request("recipient"), as: UTF8.self)
                let payment = SendPayment(destination: destination, method: "ark", amount: 1000)
                let quote = try await session.quoteSend(payment)
                let snapshot = try await session.prepareSend(payment, quote: quote)
                let receipt = try await session.submitSend(payment, quote: quote, snapshot: snapshot)
                try #require(receipt.isProven(for: payment))
                try await session.sync()
                _ = try await request("spent/android", ["state": try await state(session), "fee": quote.fee])
            }
            try await session.close()
        } catch { try? await session.close(); throw error }
    }
}
