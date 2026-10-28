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

    @Test func foregroundRefreshPublishesRenewAndRecurringBalanceKeepsIt() async throws {
        let defaults = try #require(UserDefaults(suiteName: AppVariant.regtest.appGroup))
        let previous = (defaults.object(forKey: WidgetSnapshot.key), defaults.object(forKey: WidgetSnapshot.legacyKey))
        defer { defaults.set(previous.0, forKey: WidgetSnapshot.key); defaults.set(previous.1, forKey: WidgetSnapshot.legacyKey) }
        defaults.set(["totalBalance": 1.0], forKey: WidgetSnapshot.legacyKey)
        _ = try await ForegroundWidgetRefresh.read(summary: { summary }, widget: { .init(changes: [], renewal: .blocks(123)) },
                                                   checkStage: {}, publish: { WidgetDataWriter.write(
                                                       balance: $0,
                                                       facts: $1,
                                                       variant: .regtest,
                                                       ticket: WidgetDataWriter.ticket(variant: .regtest)) })
        let published = try #require(WidgetSnapshot.read(from: defaults, variant: "regtest"))
        WidgetDataWriter.write(balance: summary.balance, facts: .init(), variant: .regtest, ticket: WidgetDataWriter.ticket(variant: .regtest))
        let snapshot = try #require(WidgetSnapshot.read(from: defaults, variant: "regtest"))
        #expect(snapshot.renewBy == published.renewBy)
        #expect(snapshot.renewHours(now: snapshot.writtenAt) == 20)
        #expect(snapshot.balance == 1234)
        #expect(defaults.object(forKey: WidgetSnapshot.legacyKey) == nil)
    }

    @Test func revokedSummaryOrExpiryCannotPublish() async {
        for revokeAfterSummary in [true, false] {
            let budget = RecurringSubmissionBudget()
            var writes = 0, expiryReads = 0
            await RecurringSubmissionBudget.$current.withValue(budget) {
                await #expect(throws: CancellationError.self, "Revoked refresh must not return") {
                    _ = try await ForegroundWidgetRefresh.read(summary: {
                        if revokeAfterSummary { budget.revoke() }; return summary
                    }, widget: { expiryReads += 1; budget.revoke(); return .init(renewal: .blocks(123)) },
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
            _ = try await ForegroundWidgetRefresh.read(summary: { summary }, widget: {
                withUnsafeCurrentTask { $0?.cancel() }; return .init(renewal: .blocks(123))
            }, checkStage: { try Task.checkCancellation() }, publish: { _, _ in writes += 1 })
        }
        await #expect(throws: CancellationError.self, "Cancelled refresh must not return") { try await work.value }
        #expect(writes == 0)
        struct WalletUnreadable: Error {}
        let latest = try await ForegroundWidgetRefresh.read(summary: { summary }, widget: { throw WalletUnreadable() },
                                                            checkStage: {}, publish: { _, _ in writes += 1 })
        #expect(latest == summary)
        #expect(writes == 0)
    }
}
