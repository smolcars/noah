import CryptoKit
import Foundation
import Synchronization

/// Server JWT, ported from `getAccessToken` in `client/src/lib/api.ts`:
/// reuse the stored token until it is within the refresh window, otherwise
/// log in again with the wallet's offline key (`m/350/0`). Concurrent
/// callers share one login; a cancelled caller stops waiting for it at once
/// while it carries on for the others. A stored token is only reused if it
/// was issued to the current wallet (its `sub` is the login key of the seed
/// in the Keychain), so a new, restored or replaced wallet never acts as the
/// old one.
actor AuthTokenProvider {
    struct Dependencies: Sendable {
        var readToken: @Sendable () -> String?
        var saveToken: @Sendable (String) throws -> Void
        var clearToken: @Sendable () -> Void
        var readMnemonic: @Sendable () throws -> String
        /// k1 challenge → signed login → access token.
        var login: @Sendable (_ mnemonic: String) async throws -> String
        var now: @Sendable () -> Date
        /// Login public key for `mnemonic`, i.e. the token's `sub`. `nil`
        /// skips the check (tests with a fixed token).
        var loginKey: @Sendable (_ mnemonic: String) throws -> String? = { _ in nil }
    }

    private let dependencies: Dependencies
    private var cached: String?
    private var loginInFlight: Task<String, Error>?
    /// Last seed digest and its login key, so the derivation runs once per seed.
    private var loginKeyCache: (seedDigest: Data, key: String?)?
    private let log = AppLog("serverAuth")

    init(dependencies: Dependencies) {
        self.dependencies = dependencies
    }

    func token(forceRefresh: Bool = false) async throws -> String {
        // A cancelled caller (a background job past its budget) starts no auth work.
        try Task.checkCancellation()
        let mnemonic = try dependencies.readMnemonic()
        let expectedSubject = try loginKey(for: mnemonic)
        if forceRefresh {
            invalidate()
        } else if let current = cached ?? dependencies.readToken() {
            if let expectedSubject, (try? JWT.subject(of: current)) != expectedSubject {
                log.w("Stored server token belongs to another wallet, logging in again")
                invalidate()
            } else {
                switch Result(catching: { try JWT.needsRefresh(current, now: dependencies.now()) }) {
                case .success(false):
                    cached = current
                    return current
                case .success(true):
                    log.d("Stored server token is near expiry, refreshing")
                    invalidate()
                case .failure:
                    log.w("Stored server token is unreadable, logging in again")
                    invalidate()
                }
            }
        }

        let token = try await Self.value(of: loginInFlight ?? startLogin(mnemonic))
        cached = token
        return token
    }

    private func startLogin(_ mnemonic: String) -> Task<String, Error> {
        let attempt = Task { [dependencies] in
            let token = try await dependencies.login(mnemonic)
            try dependencies.saveToken(token)
            return token
        }
        loginInFlight = attempt
        // Cleared when the login ends, not when its first caller stops waiting.
        Task {
            _ = await attempt.result
            if loginInFlight == attempt { loginInFlight = nil }
        }
        return attempt
    }

    func invalidate() {
        cached = nil
        dependencies.clearToken()
    }

    /// The login's token, or `CancellationError` as soon as this caller is cancelled;
    /// the login itself keeps running.
    private static func value(of login: Task<String, Error>) async throws -> String {
        let waiter = Mutex<(continuation: CheckedContinuation<String, Error>?, early: Result<String, Error>?)>((nil, nil))
        @Sendable func finish(_ result: Result<String, Error>) {
            let continuation = waiter.withLock { state -> CheckedContinuation<String, Error>? in
                if let continuation = state.continuation {
                    state.continuation = nil
                    return continuation
                }
                if state.early == nil { state.early = result }
                return nil
            }
            continuation?.resume(with: result)
        }
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                let early = waiter.withLock { state -> Result<String, Error>? in
                    if let early = state.early { return early }
                    state.continuation = continuation
                    return nil
                }
                if let early {
                    continuation.resume(with: early)
                    return
                }
                Task { finish(await login.result) }
            }
        } onCancel: {
            finish(.failure(CancellationError()))
        }
    }

    private func loginKey(for mnemonic: String) throws -> String? {
        let digest = Data(SHA256.hash(data: Data(mnemonic.utf8)))
        if let loginKeyCache, loginKeyCache.seedDigest == digest { return loginKeyCache.key }
        let key = try dependencies.loginKey(mnemonic)
        loginKeyCache = (digest, key)
        return key
    }
}
