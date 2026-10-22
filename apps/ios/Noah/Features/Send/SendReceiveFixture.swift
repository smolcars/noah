import SwiftUI

/// Stand-ins for wallet reads in the opt-in Send and Receive screenshot fixtures
/// (`NoahTests/SendReceiveShotTests`). Never set in the app.
struct SendReceiveFixture {
    var summary: WalletSession.Summary?
    var tip: UInt32?
    /// Whether a failed Lightning send's refund is recorded.
    var refunded: Bool?
    var history: [HistoryTransaction] = []
    var chain: (@Sendable (String) async throws -> ReceiveChainSnapshot)?
    var fees: [SendRail: UInt64] = [:]
    /// Receive opens on this format, and with this sheet ("formats" or "amount").
    var receiveFormat: ReceiveFormat?
    var receiveSheet: String?
}

extension EnvironmentValues {
    @Entry var sendReceiveFixture: SendReceiveFixture?
}
