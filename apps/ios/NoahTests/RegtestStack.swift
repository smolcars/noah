import Foundation
import Network
@testable import Noah
import Testing

/// Whether the regtest bitcoind the wallet opens against is up. Opening a real bark wallet
/// checks it, so tests that do fail without the regtest stack (e.g. on CI).
/// `NOAH_REGTEST_LIVE=0` skips them and `=1` forces them on; otherwise one TCP connect decides.
enum RegtestStack {
    static let bitcoind = WalletConfiguration.base(for: .regtest).bitcoind ?? ""

    static let isReachable: Bool = {
        switch ProcessInfo.processInfo.environment["NOAH_REGTEST_LIVE"] {
        case "0": return false
        case "1": return true
        default: break
        }
        guard let url = URL(string: bitcoind), let host = url.host(),
              let port = url.port.flatMap({ UInt16(exactly: $0) }).flatMap(NWEndpoint.Port.init(rawValue:)) else { return false }
        let connection = NWConnection(host: .init(host), port: port, using: .tcp)
        let ready = DispatchSemaphore(value: 0)
        connection.stateUpdateHandler = { if case .ready = $0 { ready.signal() } }
        connection.start(queue: .global())
        defer { connection.cancel() }
        return ready.wait(timeout: .now() + .milliseconds(300)) == .success
    }()
}

extension Trait where Self == ConditionTrait {
    /// For tests that open a real bark wallet; see `RegtestStack`.
    static var regtestStack: Self {
        .enabled(if: RegtestStack.isReachable,
                 "Needs the regtest stack's bitcoind at \(RegtestStack.bitcoind) (NOAH_REGTEST_LIVE=1 forces, =0 skips)")
    }
}
