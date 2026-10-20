import Foundation

/// Headless entry points only read the seed. A journal/marker requires foreground
/// recovery; do not call RestoreRecovery here (deletion recovery can remove a seed).
enum HeadlessWalletGate {
    static func mnemonic(location: WalletDataLocation, settings: SettingsStorage,
                         readSeed: () -> KeychainPassword.ReadResult) throws -> String {
        try settings.requireCompatibleRNStores()
        guard !location.restorePending, !RestoreTransaction(location: location).settingsPending,
              !FileManager.default.fileExists(atPath: location.deletionMarker.path) else {
            throw AppServices.RestoreNotSettled()
        }
        guard location.databaseExists else { throw WalletSession.OpenError.walletDatabaseMissing }
        guard !location.hasMultipleDatabases else { throw StoredSeed.Failure.conflict }
        let wallet = settings.persistedState(PersistedWalletState.self, key: PersistedWalletState.key)
        guard wallet?.isInitialized != false, wallet?.isWalletSuspended != true else { throw AppServices.WalletSuspended() }
        let seed = readSeed()
        try StoredSeed.requireUsable(seed)
        guard case .found(let mnemonic) = seed else { throw AppServices.SeedUnavailable() }
        return mnemonic
    }
}
