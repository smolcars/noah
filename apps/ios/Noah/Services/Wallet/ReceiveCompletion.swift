import BarkBoltFFI
import Foundation

/// Only movements for the currently displayed request may finish Receive.
enum ReceiveCompletion: Equatable, Sendable {
    /// Which rail the payment arrived on; Receive plays that rail's arrival.
    enum Rail: Equatable, Sendable { case ark, lightning }

    case received(Int64, rail: Rail)
    case lightningFailed(canceled: Bool)

    static func matching(_ movement: Movement, request: ReceiveRequest) -> Self? {
        guard movement.subsystemKind.lowercased() == "receive" else { return nil }
        switch movement.subsystemName.lowercased() {
        case "bark.arkoor":
            guard movement.status == "successful" else { return nil }
            let destinations = movement.receivedOnAddresses.filter { $0.paymentMethod.value == request.arkAddress }
            guard !destinations.isEmpty else { return nil }
            let sats = destinations.reduce(UInt64(0)) { $0 + $1.amountSats }
            if sats > 0, let amount = Int64(exactly: sats) { return .received(amount, rail: .ark) }
            return request.amountSat.map { .received($0, rail: .ark) }
        case "bark.lightning_receive":
            guard let hash = request.paymentHash, movement.paymentHash == hash else { return nil }
            if movement.status == "failed" || movement.status == "canceled" {
                return .lightningFailed(canceled: movement.status == "canceled")
            }
            if movement.status == "successful", let sats = request.amountSat { return .received(sats, rail: .lightning) }
            return nil
        default: return nil
        }
    }
}
