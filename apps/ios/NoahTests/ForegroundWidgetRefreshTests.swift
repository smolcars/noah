import Foundation
@testable import Noah
import Testing

@MainActor @Suite(.serialized)
struct ForegroundWidgetRefreshTests {
    private var summary: WalletSession.Summary {
        .init(network: .regtest, fingerprint: "fixture", balance: .init(
            ark: .init(spendable: 1234, needsRefresh: 0, pendingLightningSend: 0, claimableLightningReceive: 0,
                       pendingInRound: 0, pendingExit: 0, pendingBoard: 0, pendingArkoorSend: 0, pendingOffboard: 0),
            onchain: .init(confirmed: 0, pending: 0, total: 0)), movementCount: 0)
    }

    @Test func foregroundRefreshPublishesExpiryAndRecurringBalancePreservesIt() async throws {
        let defaults = try #require(UserDefaults(suiteName: AppVariant.regtest.appGroup))
        let previous = defaults.object(forKey: "widgetBalance")
        defer { defaults.set(previous, forKey: "widgetBalance") }
        _ = try await ForegroundWidgetRefresh.read(summary: { summary }, expiry: { 123 },
                                                   checkStage: {}, publish: { WidgetDataWriter.write(balance: $0, closestExpiryBlocks: $1, variant: .regtest) })
        WidgetDataWriter.write(balance: summary.balance, closestExpiryBlocks: nil, variant: .regtest, preserveExpiry: true)
        let values = defaults.dictionary(forKey: "widgetBalance")
        #expect(values?["closestExpiryBlocks"] as? Double == 123)
        #expect(values?["totalBalance"] as? Double == 1234)
    }

    @Test func revokedSummaryOrExpiryCannotPublish() async {
        for revokeAfterSummary in [true, false] {
            let budget = RecurringSubmissionBudget()
            var writes = 0, expiryReads = 0
            await RecurringSubmissionBudget.$current.withValue(budget) {
                await #expect(throws: CancellationError.self, "Revoked refresh must not return") {
                    _ = try await ForegroundWidgetRefresh.read(summary: {
                        if revokeAfterSummary { budget.revoke() }; return summary
                    }, expiry: { expiryReads += 1; budget.revoke(); return 123 },
                    checkStage: { try BackgroundExecution.checkActive() }, publish: { _, _ in writes += 1 })
                }
            }
            #expect(writes == 0)
            #expect(expiryReads == (revokeAfterSummary ? 0 : 1))
        }
    }

    @Test func cancelledReadCannotPublishAndMissingTipKeepsSummary() async throws {
        var writes = 0
        let work = Task { @MainActor in
            _ = try await ForegroundWidgetRefresh.read(summary: { summary }, expiry: {
                withUnsafeCurrentTask { $0?.cancel() }; return 123
            }, checkStage: { try Task.checkCancellation() }, publish: { _, _ in writes += 1 })
        }
        await #expect(throws: CancellationError.self, "Cancelled refresh must not return") { try await work.value }
        #expect(writes == 0)
        struct TipUnavailable: Error {}
        let latest = try await ForegroundWidgetRefresh.read(summary: { summary }, expiry: { throw TipUnavailable() },
                                                            checkStage: {}, publish: { _, _ in writes += 1 })
        #expect(latest == summary)
        #expect(writes == 0)
    }
}
