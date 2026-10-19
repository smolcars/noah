import Foundation

/// Expo push token exchange, as expo-notifications 58 does it
/// (`getExpoPushTokenAsync` / `updateDevicePushTokenAsync`). Same
/// installation ID, app ID, project and environment → the same Expo token
/// the RN app registered with the Noah server.
struct ExpoPushToken: Sendable {
    /// EAS project (`extra.eas.projectId` in client/app.config.ts).
    static let projectID = "6e79dffb-dcd4-4f3d-b596-638b16377eb0"
    static let baseURL = URL(string: "https://exp.host/--/api/v2/")!

    var session: URLSession = .shared
    var baseURL: URL = ExpoPushToken.baseURL

    struct Request: Encodable, Equatable {
        let type = "apns"
        let deviceId: String
        let development: Bool
        let appId: String
        let deviceToken: String
        var projectId: String?
    }

    struct Failed: Error, Equatable {
        let status: Int
    }

    static func request(
        installationID: String, deviceToken: Data, appID: String, development: Bool
    ) -> Request {
        Request(
            deviceId: installationID.lowercased(),
            development: development,
            appId: appID,
            deviceToken: deviceToken.map { String(format: "%02x", $0) }.joined(),
            projectId: projectID)
    }

    /// POST push/getExpoPushToken → `ExponentPushToken[…]`.
    func fetch(_ request: Request) async throws -> String {
        struct Response: Decodable {
            struct Payload: Decodable { let expoPushToken: String }
            let data: Payload
        }
        let data = try await post(path: "push/getExpoPushToken", body: request)
        return try JSONDecoder().decode(Response.self, from: data).data.expoPushToken
    }

    /// POST push/updateDeviceToken, when APNs hands out a new device token.
    func updateDeviceToken(_ request: Request) async throws {
        var update = request
        update.projectId = nil
        _ = try await post(path: "push/updateDeviceToken", body: update)
    }

    private func post(path: String, body: some Encodable) async throws -> Data {
        var urlRequest = URLRequest(url: baseURL.appending(path: path), timeoutInterval: 30)
        urlRequest.httpMethod = "POST"
        urlRequest.setValue("application/json", forHTTPHeaderField: "content-type")
        urlRequest.httpBody = try JSONEncoder().encode(body)
        let (data, response) = try await session.data(for: urlRequest)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else { throw Failed(status: status) }
        return data
    }
}
