import Foundation

/// Process-wide services shared by the UI, push handlers and background
/// work: one wallet session, one token provider (one login at a time), one
/// server client.
final class AppServices: Sendable {
    static let shared = AppServices(variant: .current)

    let variant: AppVariant
    let session: WalletSession
    let tokens: AuthTokenProvider
    let client: Client
    let seed: KeychainPassword

    init(variant: AppVariant, session: WalletSession = .shared) {
        self.variant = variant
        self.session = session
        self.tokens = AuthTokenProvider(dependencies: .live(variant: variant))
        self.client = ServerClient.make(variant: variant, tokens: tokens)
        self.seed = KeychainPassword.mnemonic(for: variant)
    }

    var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown"
    }

    struct SeedUnavailable: Error {}

    func readMnemonic() throws -> String {
        guard case .found(let mnemonic) = seed.read() else { throw SeedUnavailable() }
        return mnemonic
    }

    /// Opens the wallet if needed (RN `loadWalletIfNeeded`).
    func ensureWalletOpen() async throws {
        try await session.ensureOpen(mnemonic: readMnemonic(), runDaemon: true, appVersion: appVersion)
    }

    var backupService: BackupService {
        BackupService(session: session, client: client, readMnemonic: { [self] in try readMnemonic() })
    }
}
