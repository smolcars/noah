import Foundation
@testable import Noah
import Testing

/// Home says "Couldn't open your wallet" only for real failures, not for an open the app interrupted by leaving
/// the front or one a push or background task was holding the wallet for.
struct WalletOpenFailureTests {
    struct Other: Error {}

    @Test func cancellationIsAnInterruptedOpen() {
        #expect(AppModel.openFailure(CancellationError()) == .interrupted)
    }

    @Test func busyWaitsForBackgroundWork() {
        #expect(AppModel.openFailure(WalletSession.OpenError.busy) == .busy)
    }

    @Test func otherErrorsAreFailures() {
        #expect(AppModel.openFailure(WalletSession.OpenError.walletDatabaseMissing) == .failed)
        #expect(AppModel.openFailure(WalletSession.OpenError.networkMismatch(found: "bitcoin")) == .failed)
        #expect(AppModel.openFailure(Other()) == .failed)
    }
}
