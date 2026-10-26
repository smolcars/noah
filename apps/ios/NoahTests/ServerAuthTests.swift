import Foundation
import Testing

@testable import Noah

struct ServerAuthTests {
    /// Unsigned test token with the given expiry.
    static func token(expiresIn seconds: TimeInterval, now: Date) -> String {
        let payload = try! JSONSerialization.data(withJSONObject: ["exp": Int(now.timeIntervalSince1970 + seconds)])
        let encoded = payload.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return "e30.\(encoded).sig"
    }

    @Test func refreshWindowMatchesReactNative() throws {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        #expect(try !JWT.needsRefresh(Self.token(expiresIn: 3 * 3600 + 61, now: now), now: now))
        #expect(try JWT.needsRefresh(Self.token(expiresIn: 3 * 3600 + 60, now: now), now: now))
        #expect(throws: JWT.Failure.malformed) { try JWT.needsRefresh("not-a-jwt", now: now) }
    }

    final class Fake: @unchecked Sendable {
        let lock = NSLock()
        var stored: String?
        var logins = 0
        var nextToken: String
        var gate: CheckedContinuation<Void, Never>?
        var holdLogin = false
        init(nextToken: String) { self.nextToken = nextToken }
        func sync<T>(_ body: () -> T) -> T { lock.lock(); defer { lock.unlock() }; return body() }

        func dependencies(now: Date) -> AuthTokenProvider.Dependencies {
            .init(
                readToken: { self.sync { self.stored } },
                saveToken: { token in self.sync { self.stored = token } },
                clearToken: { self.sync { self.stored = nil } },
                readMnemonic: { "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about" },
                login: { _ in
                    if self.sync({ self.holdLogin }) {
                        await withCheckedContinuation { continuation in self.sync { self.gate = continuation } }
                    }
                    return self.sync { self.logins += 1; return self.nextToken }
                },
                now: { now })
        }
    }

    @Test func reusesFreshTokenAndReplacesExpiringOne() async throws {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let fresh = Self.token(expiresIn: 72 * 3600, now: now)
        let fake = Fake(nextToken: fresh)
        fake.stored = Self.token(expiresIn: 60, now: now)
        let provider = AuthTokenProvider(dependencies: fake.dependencies(now: now))

        #expect(try await provider.token() == fresh)
        #expect(try await provider.token() == fresh)
        #expect(fake.logins == 1)
        #expect(fake.stored == fresh)
    }

    /// Unsigned test token for a given subject (login key).
    static func token(subject: String, expiresIn seconds: TimeInterval, now: Date) -> String {
        let payload = try! JSONSerialization.data(withJSONObject: [
            "sub": subject, "exp": Int(now.timeIntervalSince1970 + seconds),
        ])
        let encoded = payload.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return "e30.\(encoded).sig"
    }

    /// After the seed changes (new, restored or replaced wallet), a still-valid
    /// token for the old wallet must not be used: the server would treat the
    /// app as the old user.
    @Test func tokenForAnotherWalletIsNeverReused() async throws {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let current = Self.token(subject: "02current", expiresIn: 72 * 3600, now: now)
        let fake = Fake(nextToken: current)
        fake.stored = Self.token(subject: "02previous", expiresIn: 72 * 3600, now: now)
        var dependencies = fake.dependencies(now: now)
        dependencies.loginKey = { _ in "02current" }
        let provider = AuthTokenProvider(dependencies: dependencies)

        #expect(try await provider.token() == current)
        #expect(fake.logins == 1)
        #expect(fake.stored == current)
        // The matching token is then reused as before.
        #expect(try await provider.token() == current)
        #expect(fake.logins == 1)
    }

    @Test func loginKeyMatchesTheOfflineLoginDerivation() throws {
        let mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        let key = try ServerClient.loginKey(variant: .regtest, mnemonic: mnemonic)
        #expect(key.count == 66 && (key.hasPrefix("02") || key.hasPrefix("03")))
        #expect(try JWT.subject(of: Self.token(subject: key, expiresIn: 60, now: Date())) == key)
    }

    @Test func forcedRefreshLogsInAgain() async throws {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let fake = Fake(nextToken: Self.token(expiresIn: 72 * 3600, now: now))
        fake.stored = Self.token(expiresIn: 72 * 3600, now: now)
        let provider = AuthTokenProvider(dependencies: fake.dependencies(now: now))
        _ = try await provider.token(forceRefresh: true)
        #expect(fake.logins == 1)
    }

    @Test func concurrentCallersShareOneLogin() async throws {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let fake = Fake(nextToken: Self.token(expiresIn: 72 * 3600, now: now))
        fake.holdLogin = true
        let provider = AuthTokenProvider(dependencies: fake.dependencies(now: now))
        let first = Task { try await provider.token() }
        while fake.sync({ fake.gate == nil }) { await Task.yield() }
        let second = Task { try await provider.token() }
        for _ in 0..<50 { await Task.yield() }
        fake.sync { fake.gate?.resume(); fake.gate = nil }
        #expect(try await first.value == second.value)
        #expect(fake.logins == 1)
    }

    @Test(.timeLimit(.minutes(1))) func cancelledCallerStopsWaitingWhileTheSharedLoginCarriesOn() async throws {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let fake = Fake(nextToken: Self.token(expiresIn: 72 * 3600, now: now))
        fake.holdLogin = true
        let provider = AuthTokenProvider(dependencies: fake.dependencies(now: now))
        let first = Task { try await provider.token() }
        while fake.sync({ fake.gate == nil }) { await Task.yield() }
        first.cancel()
        await #expect(throws: CancellationError.self) { try await first.value }
        // Its login is still the shared one: a later caller joins it instead of starting another.
        let second = Task { try await provider.token() }
        for _ in 0..<50 { await Task.yield() }
        fake.sync { fake.gate?.resume(); fake.gate = nil }
        #expect(try await second.value == fake.nextToken)
        #expect(fake.sync { fake.logins } == 1)
        #expect(fake.sync { fake.stored } == fake.nextToken)
    }

    @Test(.timeLimit(.minutes(1))) func cancelledCallerStartsNoLogin() async throws {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let fake = Fake(nextToken: Self.token(expiresIn: 72 * 3600, now: now))
        fake.holdLogin = true
        let provider = AuthTokenProvider(dependencies: fake.dependencies(now: now))
        let cancelled = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            return try await provider.token()
        }
        await #expect(throws: CancellationError.self) { try await cancelled.value }
        for _ in 0..<50 { await Task.yield() }
        #expect(fake.sync { fake.gate == nil })
        fake.sync { fake.gate?.resume(); fake.gate = nil }
    }

    /// The public set must match operations without `security` in the spec,
    /// so a new gated endpoint can never be sent without a token.
    @Test func publicOperationsMatchTheSpec() throws {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appending(path: "api/openapi.json")
        let spec = try #require(try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        let paths = try #require(spec["paths"] as? [String: [String: Any]])
        var publicIDs = Set<String>()
        for operations in paths.values {
            for case let operation as [String: Any] in operations.values where operation["security"] == nil {
                publicIDs.insert(try #require(operation["operationId"] as? String))
            }
        }
        #expect(publicIDs == ServerClient.publicOperations)
    }
}
