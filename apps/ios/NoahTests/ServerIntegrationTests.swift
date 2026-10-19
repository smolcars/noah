import BarkBoltFFI
import Foundation
import Testing

@testable import Noah

/// Live against the regtest Noah server (`just setup-everything`).
/// Enabled with `TEST_RUNNER_NOAH_SERVER_IT=1`. Uses a throwaway mnemonic;
/// never touches the app's Keychain items.
@Suite(.enabled(if: ProcessInfo.processInfo.environment["NOAH_SERVER_IT"] == "1"))
struct ServerIntegrationTests {
    final class MemoryToken: @unchecked Sendable {
        let lock = NSLock()
        var value: String?
    }

    @Test func logsInRegistersAndCallsAGatedEndpoint() async throws {
        let mnemonic = try generateMnemonic()
        let memory = MemoryToken()
        let tokens = AuthTokenProvider(
            dependencies: .init(
                readToken: { memory.lock.withLock { memory.value } },
                saveToken: { token in memory.lock.withLock { memory.value = token } },
                clearToken: { memory.lock.withLock { memory.value = nil } },
                readMnemonic: { mnemonic },
                login: { try await ServerClient.login(variant: .regtest, mnemonic: $0) },
                now: { Date() }))

        let token = try await tokens.token()
        #expect(try !JWT.needsRefresh(token), "server issued a token that is already near expiry")

        let client = ServerClient.make(variant: .regtest, tokens: tokens)
        _ = try await client.register(body: .json(.init())).ok
        let info = try await client.getUserInfo().ok.body.json
        #expect(info.lightningAddress.contains("@"))

        // A rejected token is replaced transparently (one retry): a fresh
        // provider starts from a stored token the server will not accept.
        let rejected = "e30.eyJleHAiOjQxMDI0NDQ4MDB9.bad"
        let stale = MemoryToken()
        stale.value = rejected
        let retrying = ServerClient.make(
            variant: .regtest,
            tokens: AuthTokenProvider(
                dependencies: .init(
                    readToken: { stale.lock.withLock { stale.value } },
                    saveToken: { token in stale.lock.withLock { stale.value = token } },
                    clearToken: { stale.lock.withLock { stale.value = nil } },
                    readMnemonic: { mnemonic },
                    login: { try await ServerClient.login(variant: .regtest, mnemonic: $0) },
                    now: { Date() })))
        _ = try await retrying.getUserInfo().ok
        let replaced = stale.lock.withLock { stale.value }
        #expect(replaced != nil && replaced != rejected)
    }
}
