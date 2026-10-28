import BarkBoltFFI
import Foundation
@testable import Noah
import Testing

/// Opt-in on an owned simulator; the loopback host pays only this fresh regtest invoice.
@Suite(.enabled(if: ProcessInfo.processInfo.environment["NOAH_WIDGET_FUNDING_URL"] != nil))
@MainActor struct ForegroundWidgetDeviceTests {
    @Test func fundedForegroundRefreshPublishesExpiry() async throws {
        try #require(AppVariant.current == .regtest)
        let root = FileManager.default.temporaryDirectory.appending(path: "widget-\(UUID())")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let session = WalletSession(variant: .regtest, location: .init(variant: .regtest, documentsDirectory: root))
        do {
            try await session.createNew(mnemonic: generateMnemonic(), runDaemon: true, appVersion: "widget-test")
            let invoice = try await session.bolt11Invoice(amountSats: 20_000)
            let funding = try #require(ProcessInfo.processInfo.environment["NOAH_WIDGET_FUNDING_URL"])
            let fundingURL = try #require(URL(string: funding))
            var request = URLRequest(url: fundingURL)
            request.httpMethod = "POST"; request.timeoutInterval = 120
            request.httpBody = try JSONSerialization.data(withJSONObject: ["invoice": invoice])
            let (_, response) = try await URLSession.shared.data(for: request)
            try #require((response as? HTTPURLResponse)?.statusCode == 200)
            let deadline = ContinuousClock.now + .seconds(120)
            while ContinuousClock.now < deadline {
                try await session.claimLightningReceives(); try await session.sync()
                if try #require(try await session.summary()).spendableSats > 0 { break }
                try await Task.sleep(for: .seconds(1))
            }
            var published = false
            await ForegroundExecution.run {
                do {
                    _ = try await ForegroundWidgetRefresh.read(summary: { try await session.summary() },
                                                               expiry: { try await session.closestExpiryBlocks() }, checkStage: {
                                                                   try BackgroundExecution.checkActive(); try ForegroundExecution.checkActiveNow()
                                                               }, publish: { balance, expiry in
                                                                   #expect(balance.ark.spendable > 0)
                                                                   #expect(expiry != nil)
                                                                   WidgetDataWriter.write(balance: balance, closestExpiryBlocks: expiry, variant: .regtest)
                                                                   WidgetDataWriter.write(
                                                                       balance: balance,
                                                                       closestExpiryBlocks: nil,
                                                                       variant: .regtest,
                                                                       preserveExpiry: true)
                                                                   published = true
                                                               })
                } catch { Issue.record("Foreground widget refresh failed") }
            }
            #expect(published)
            try await session.close()
        } catch { try? await session.close(); throw error }
    }
}
