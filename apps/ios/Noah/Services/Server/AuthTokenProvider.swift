import Foundation

/// Server JWT, ported from `getAccessToken` in `client/src/lib/api.ts`:
/// reuse the stored token until it is within the refresh window, otherwise
/// log in again with the wallet's offline key (`m/350/0`). Concurrent
/// callers share one login.
actor AuthTokenProvider {
    struct Dependencies: Sendable {
        var readToken: @Sendable () -> String?
        var saveToken: @Sendable (String) throws -> Void
        var clearToken: @Sendable () -> Void
        var readMnemonic: @Sendable () throws -> String
        /// k1 challenge → signed login → access token.
        var login: @Sendable (_ mnemonic: String) async throws -> String
        var now: @Sendable () -> Date
    }

    private let dependencies: Dependencies
    private var cached: String?
    private var loginInFlight: Task<String, Error>?
    private let log = AppLog("serverAuth")

    init(dependencies: Dependencies) {
        self.dependencies = dependencies
    }

    func token(forceRefresh: Bool = false) async throws -> String {
        if forceRefresh {
            invalidate()
        } else if let current = cached ?? dependencies.readToken() {
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

        if let loginInFlight {
            return try await loginInFlight.value
        }
        let attempt = Task { [dependencies] in
            let token = try await dependencies.login(try dependencies.readMnemonic())
            try dependencies.saveToken(token)
            return token
        }
        loginInFlight = attempt
        defer { loginInFlight = nil }
        let token = try await attempt.value
        cached = token
        return token
    }

    func invalidate() {
        cached = nil
        dependencies.clearToken()
    }
}
