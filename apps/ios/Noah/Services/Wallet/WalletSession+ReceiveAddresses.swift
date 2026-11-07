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

    /// The foreground pass's on-chain sync. A restored database misses payments to addresses revealed after its backup, and
    /// handing out each address until it's paid never reveals them, so a restore's first one is bark's full scan.
    nonisolated func syncOnchain(_ handles: Handles) async throws {
        let scan = location.directory.appending(path: RestoreTransaction.onchainScanName)
        guard FileManager.default.fileExists(atPath: scan.path) else { _ = try await handles.onchain.sync(); return }
        _ = try await handles.onchain.initialScan(birthdayHeight: nil)
        _ = try await handles.onchain.sync()
        try FileManager.default.removeItem(at: scan)
    }
}
