import BarkBoltFFI
import Foundation

extension WalletSession {
    /// Creates a wallet from `mnemonic` in `datadir`, a restore's staging directory (never the live datadir), which runs
    /// bark's scan of the Ark server for its VTXOs, then shuts it again. Returns the scan's report, or nil when the scan
    /// failed. Finishes even if the caller is cancelled, so nothing still runs on the files when the restore moves or
    /// removes them.
    func recoverFromArkServer(_ reservation: Reservation, mnemonic: String, datadir: URL) async throws -> RecoveryReport? {
        try checkHeld(reservation)
        let opener = opener
        return try await Task {
            let handles = try await opener(mnemonic, datadir.path, "restore", true)
            let status = handles.wallet.recoveryStatus()
            try await handles.wallet.stopDaemonWait()
            guard case .completed(let report) = status else { return nil }
            return report
        }.value
    }
}
