import BarkBoltFFI
import Foundation

extension WalletSession {
    /// Receive's Ark and on-chain addresses, reused until each is paid: every new on-chain address joins every later sync.
    private struct ReceiveAddresses: Codable {
        var ark: String
        var arkIndex: UInt32
        var onchain: String
    }

    /// Kept paired while an amount is edited. Saved in the wallet's folder, which a restore replaces and a deletion
    /// removes, so the addresses never outlive the database that derived them.
    func receiveRequest() async throws -> ReceiveRequest {
        let file = location.directory.appending(path: "noah-receive-addresses.json")
        let saved = (try? Data(contentsOf: file)).flatMap { try? JSONDecoder().decode(ReceiveAddresses.self, from: $0) }
        let addresses = try await withOpenWallet { handles in
            var ark = saved.map { AddressWithIndex(address: $0.ark, index: $0.arkIndex) }
            var onchain = saved?.onchain
            if let current = ark {
                // bark peeks only keys it derived, so a saved index from another database fails here.
                let derived = try? await handles.wallet.peekAddress(index: current.index)
                let paid = try await handles.wallet.history().contains { $0.receivedOnAddresses.contains { $0.paymentMethod.value == current.address } }
                if derived != current.address || paid { ark = nil }
            }
            if let address = onchain, try await handles.onchain.transactions().contains(where: {
                ((try? SendTransaction.amount(to: address, hex: $0.txHex)) ?? 0) > 0
            }) { onchain = nil }
            let nextArk = if let ark { ark } else { try await handles.wallet.newAddressWithIndex() }
            let nextOnchain = if let onchain { onchain } else { try await handles.onchain.newAddress() }
            return ReceiveAddresses(ark: nextArk.address, arkIndex: nextArk.index, onchain: nextOnchain)
        }
        if addresses.ark != saved?.ark || addresses.onchain != saved?.onchain {
            try? JSONEncoder().encode(addresses).write(to: file, options: .atomic)
        }
        return ReceiveRequest(arkAddress: addresses.ark, onchainAddress: addresses.onchain)
    }
}
