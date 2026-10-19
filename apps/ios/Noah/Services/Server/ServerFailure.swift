import Foundation
import HTTPTypes
import OpenAPIRuntime

/// Only display the server's explicit user-facing message, never a generated
/// ClientError description (which contains authorization headers and inputs).
struct ServerFailure: Error, Sendable {
    let message: String
    var statusCode: Int? = nil
    var code: String? = nil

    static func message(for error: Error, fallback: String) -> String {
        if let error = error as? ServerFailure { return error.message }
        if let error = error as? IdentityValidationError { return error.errorDescription ?? fallback }
        if let error = error as? ClientError { return message(for: error.underlyingError, fallback: fallback) }
        return fallback
    }
}

struct ServerFailureMiddleware: ClientMiddleware {
    func intercept(
        _ request: HTTPRequest, body: HTTPBody?, baseURL: URL, operationID: String,
        next: @Sendable (HTTPRequest, HTTPBody?, URL) async throws -> (HTTPResponse, HTTPBody?)
    ) async throws -> (HTTPResponse, HTTPBody?) {
        let (response, body) = try await next(request, body, baseURL)
        guard response.status.code >= 400, let body else { return (response, body) }
        let data = try await Data(collecting: body, upTo: 64 * 1024)
        if let error = try? JSONDecoder().decode(Components.Schemas.ApiErrorResponse.self, from: data) {
            throw ServerFailure(message: error.message, statusCode: response.status.code, code: error.code)
        }
        return (response, HTTPBody(data))
    }
}
