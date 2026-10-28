import Foundation

extension ServerClient {
    /// The entered phrase and JWT live only as long as this restore client.
    /// AuthTokenProvider owns the in-memory cache; no Keychain dependency.
    static func makeRestore(variant: AppVariant, mnemonic: String) -> Client {
        make(variant: variant, tokens: AuthTokenProvider(dependencies: .init(
            readToken: { nil }, saveToken: { _ in }, clearToken: {},
            readMnemonic: { mnemonic },
            login: { try await login(variant: variant, mnemonic: $0) },
            now: { Date() })))
    }
}
