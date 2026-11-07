import BarkBoltFFI
import Foundation

extension WalletSession {
    /// Ark and on-chain addresses, kept paired while an amount is edited. Each is handed out until it's paid, so a Receive
    /// visit derives nothing and later on-chain syncs don't grow.
    func receiveRequest() async throws -> ReceiveRequest {
        try await withOpenWallet { handles in
            let ark = try await handles.wallet.nextUnusedAddress()
            let onchain = try await handles.onchain.nextUnusedAddress()
            return ReceiveRequest(arkAddress: ark.address, onchainAddress: onchain)
        }
    }
}
