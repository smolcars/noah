import BarkBoltFFI
import Foundation
import HTTPTypes
import OpenAPIRuntime
import OpenAPIURLSession

/// Typed client for the Noah server, generated from `api/openapi.json`,
/// with bearer auth and one retry on an expired or rejected token
/// (`post` in `client/src/lib/api.ts`).
enum ServerClient {
    /// Operations without `security` in api/openapi.json. Everything else
    /// gets the bearer token; a test keeps this in sync with the spec.
    static let publicOperations: Set<String> = [
        Operations.Root.id, Operations.Health.id, Operations.LnurlpRequest.id, Operations.Nip05Request.id,
        Operations.GetK1.id, Operations.AuthLogin.id, Operations.CheckAppVersion.id,
    ]

    static func make(variant: AppVariant, tokens: AuthTokenProvider) -> Client {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 30
        return Client(
            serverURL: ServerEndpoint.baseURL(for: variant),
            transport: URLSessionTransport(configuration: .init(session: URLSession(configuration: configuration))),
            middlewares: [ServerFailureMiddleware(), BearerAuthMiddleware(tokens: tokens)])
    }

    /// Unauthenticated client for the login exchange itself.
    static func makePublic(variant: AppVariant) -> Client {
        Client(serverURL: ServerEndpoint.baseURL(for: variant), transport: URLSessionTransport())
    }

    /// Live login: GET /v0/getk1, sign it with the offline key at index 0,
    /// POST /v0/auth/login.
    /// The offline login key (`m/350/0`) the server puts in the token's `sub`.
    static func loginKey(variant: AppVariant, mnemonic: String) throws -> String {
        try derivePublicKeyFromMnemonic(mnemonic: StoredSeed.normalized(mnemonic), network: WalletConfiguration.base(for: variant).network, index: 0)
    }

    static func signChallenge(_ challenge: String, variant: AppVariant, mnemonic: String) throws -> String {
        try signMessageWithMnemonic(message: challenge, mnemonic: StoredSeed.normalized(mnemonic),
                                    network: WalletConfiguration.base(for: variant).network, index: 0)
    }

    static func login(variant: AppVariant, mnemonic: String) async throws -> String {
        let client = makePublic(variant: variant)
        let k1 = try await client.getK1().ok.body.json.k1
        let key = try loginKey(variant: variant, mnemonic: mnemonic)
        let signature = try signChallenge(k1, variant: variant, mnemonic: mnemonic)
        return try await client.authLogin(body: .json(.init(k1: k1, key: key, sig: signature)))
            .ok.body.json.accessToken
    }
}

extension AuthTokenProvider.Dependencies {
    static func live(variant: AppVariant) -> Self {
        let tokenItem = KeychainPassword.serverAuthToken(for: variant)
        let seedItem = KeychainPassword.mnemonic(for: variant)
        struct SeedUnavailable: Error {}
        return Self(
            readToken: {
                if case .found(let token) = tokenItem.read(), !token.isEmpty { return token }
                return nil
            },
            saveToken: { try tokenItem.save($0) },
            clearToken: { tokenItem.remove() },
            readMnemonic: {
                guard case .found(let mnemonic) = seedItem.read() else { throw SeedUnavailable() }
                return mnemonic
            },
            login: { try await ServerClient.login(variant: variant, mnemonic: $0) },
            now: { Date() },
            loginKey: { try ServerClient.loginKey(variant: variant, mnemonic: $0) })
    }
}

/// Adds `Authorization: Bearer` to gated operations and retries once with a
/// fresh token when the server answers 401 with an auth error code.
struct BearerAuthMiddleware: ClientMiddleware {
    static let retryCodes: Set<String> = ["AUTH_REQUIRED", "INVALID_TOKEN", "TOKEN_EXPIRED"]

    let tokens: AuthTokenProvider

    func intercept(
        _ request: HTTPRequest,
        body: HTTPBody?,
        baseURL: URL,
        operationID: String,
        next: @Sendable (HTTPRequest, HTTPBody?, URL) async throws -> (HTTPResponse, HTTPBody?)) async throws -> (HTTPResponse, HTTPBody?) {
        guard !ServerClient.publicOperations.contains(operationID) else {
            return try await next(request, body, baseURL)
        }

        var authorized = request
        authorized.headerFields[.authorization] = "Bearer \(try await tokens.token())"
        let (response, responseBody) = try await next(authorized, body, baseURL)
        guard response.status == .unauthorized else { return (response, responseBody) }

        var errorBody: Data?
        if let responseBody {
            errorBody = try await Data(collecting: responseBody, upTo: 64 * 1024)
        }
        guard let code = errorBody.flatMap(Self.errorCode), Self.retryCodes.contains(code) else {
            return (response, errorBody.map(HTTPBody.init))
        }
        authorized.headerFields[.authorization] = "Bearer \(try await tokens.token(forceRefresh: true))"
        return try await next(authorized, body, baseURL)
    }

    private static func errorCode(_ data: Data) -> String? {
        (try? JSONDecoder().decode(Components.Schemas.ApiErrorResponse.self, from: data))?.code
    }
}
