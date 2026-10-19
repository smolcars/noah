import Foundation

/// What the app shows on launch, decided only from the seed in the Keychain
/// and the wallet database on disk.
///
/// Unlike the React Native app, nothing here ever deletes the seed: a seed
/// without wallet data (e.g. reinstall, where iOS keeps the Keychain) is
/// offered for restore instead of being wiped.
enum LaunchState: String, Equatable, Sendable {
    /// Seed and database present: open the existing wallet.
    case existingWallet
    /// Nothing present: fresh install.
    case onboarding
    /// Seed present, database missing: offer restoring with that seed.
    case seedWithoutWalletData
    /// Database present, seed missing: ask for the recovery phrase. Never
    /// create a new wallet over existing data.
    case walletDataWithoutSeed
    /// Keychain could not be read (locked, error). Retry later; do nothing.
    case secureStorageUnavailable

    enum SeedStatus: String, Sendable {
        case present, absent, unavailable
    }

    static func resolve(seed: SeedStatus, walletDatabaseExists: Bool) -> LaunchState {
        switch (seed, walletDatabaseExists) {
        case (.unavailable, _): .secureStorageUnavailable
        case (.present, true): .existingWallet
        case (.present, false): .seedWithoutWalletData
        case (.absent, true): .walletDataWithoutSeed
        case (.absent, false): .onboarding
        }
    }
}

extension LaunchState.SeedStatus {
    init(_ result: KeychainPassword.ReadResult) {
        switch result {
        case .found(let value):
            self = value.isEmpty ? .absent : .present
        case .notFound:
            self = .absent
        case .unavailable:
            self = .unavailable
        }
    }
}

extension LaunchState {
    /// Reads the current state from the Keychain and the filesystem.
    static func current(variant: AppVariant = .current) -> LaunchState {
        resolve(
            seed: SeedStatus(KeychainPassword.mnemonic(for: variant).read()),
            walletDatabaseExists: WalletDataLocation(variant: variant).databaseExists
        )
    }
}
