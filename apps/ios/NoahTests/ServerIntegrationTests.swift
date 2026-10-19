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

    @MainActor @Test func registersLightningIdentityAndVerifiesEmergencyEmail() async throws {
        // No seed, JWT, request bodies or OTPs are printed, including on failure.
        struct LiveFailure: Error {}
        do {
            let mnemonic = try generateMnemonic()
            let memory = MemoryToken()
            let tokens = AuthTokenProvider(dependencies: .init(
                readToken: { memory.lock.withLock { memory.value } },
                saveToken: { value in memory.lock.withLock { memory.value = value } },
                clearToken: { memory.lock.withLock { memory.value = nil } },
                readMnemonic: { mnemonic },
                login: { try await ServerClient.login(variant: .regtest, mnemonic: $0) }, now: { Date() }))
            let client = ServerClient.make(variant: .regtest, tokens: tokens)
            _ = try await client.register(body: .json(.init(deviceInfo: DeviceInfo.current().registrationPayload))).ok
            SettingsStorage.bootstrap()
            let root = FileManager.default.temporaryDirectory.appending(path: "live-registration-\(UUID())")
            defer { try? FileManager.default.removeItem(at: root) }
            let settings = try SettingsStorage(rootPath: root.path, instanceID: UUID().uuidString)
            _ = try settings.updateServerState { $0.isRegisteredWithServer = true }
            let service = ServerRegistration(variant: .regtest, settings: settings, client: client)
            let username = "native-" + UUID().uuidString.lowercased()
            let email = "\(username)@example.com"
            try await service.saveIdentity(username: " \(username.uppercased()) ", npub: "")
            #expect(service.state.lightningAddress?.hasPrefix(username + "@") == true)
            #expect(service.state.nostrPubkey == nil)
            #expect(try await !service.sendEmail(email))
            #expect(service.state.emailAddress == nil)
            // EMAIL_DEV_MODE accepts its fixed test code (email_verification_store.rs).
            // This is a dev-only bypass, not a retrieved or logged OTP.
            try await service.verifyEmail(code: String(repeating: "0", count: 6), email: email)
            #expect(service.state.isEmailVerified == true)
            #expect(service.state.emailAddress == email)
            #expect(try await service.sendEmail(email)) // already verified reconciles local state
            let info = try await client.getUserInfo().ok.body.json
            #expect(info.email == email)
            try await service.reportLastLogin()
        } catch {
            throw LiveFailure()
        }
    }

}
