import Foundation
import Testing
import HTTPTypes
import OpenAPIRuntime

@testable import Noah

@MainActor
struct ServerRegistrationTests {
    @Test func registrationReconcilesIdentityAndPreservesPreferences() throws {
        SettingsStorage.bootstrap()
        let root = FileManager.default.temporaryDirectory.appending(path: "registration-\(UUID())")
        defer { try? FileManager.default.removeItem(at: root) }
        let settings = try SettingsStorage(rootPath: root.path, instanceID: UUID().uuidString)
        settings.set(PersistedServerStateTests.rnJSON, forKey: PersistedServerState.key)
        try settings.setPersistedState(PersistedProfileState(displayName: "Old", preferredCurrency: "EUR", bitcoinAmountUnit: "sats"),
            key: PersistedProfileState.key, version: 0)
        let service = ServerRegistration(variant: .regtest, settings: settings)
        let response = try JSONDecoder().decode(Components.Schemas.RegisterResponse.self, from: Data(
            #"{"display_name":"New","email":null,"is_email_verified":false,"lightning_address":"new@localhost.com","nostr_pubkey":null,"status":"OK","user_status":"active"}"#.utf8))
        try service.apply(response, device: .init())
        #expect(service.state.isRegisteredWithServer == true)
        #expect(service.state.isBackupEnabled == true)
        #expect(service.state.emailAddress == nil)
        #expect(service.state.isEmailVerified == false)
        #expect(service.state.lightningAddress == "new@localhost.com")
        #expect(service.state.isMailboxAuthorizationEnabled == false)
        #expect(service.state.lastReportedDeviceFingerprint == "[null,null,null,null,null,null]")
        #expect(service.state.lastDeviceReportAt != nil)
        let profile = settings.persistedState(PersistedProfileState.self, key: PersistedProfileState.key)
        #expect(profile?.displayName == "New")
        #expect(profile?.preferredCurrency == "EUR")
        #expect(profile?.bitcoinAmountUnit == "sats")
    }

    actor Transport: ClientTransport {
        var bodies: [String] = []
        var paths: [String] = []
        var failLogin = false
        func failNextLogin() { failLogin = true }
        func send(_ request: HTTPRequest, body: HTTPBody?, baseURL: URL, operationID: String) async throws -> (HTTPResponse, HTTPBody?) {
            paths.append(request.path ?? "")
            if let body { bodies.append(try await String(collecting: body, upTo: 65536)) }
            if operationID == Operations.CheckAppVersion.id {
                return (.init(status: .ok), HTTPBody(#"{"update_required":false,"minimum_required_version":"0.0.1"}"#))
            }
            if failLogin { throw URLError(.notConnectedToInternet) }
            return (.init(status: .ok), HTTPBody(#"{"success":true}"#))
        }
    }

    @Test func checksVersionBeforeRegisteredFastPathAndOnlyRecordsSuccessfulReports() async throws {
        SettingsStorage.bootstrap()
        let root = FileManager.default.temporaryDirectory.appending(path: "report-login-\(UUID())")
        defer { try? FileManager.default.removeItem(at: root) }
        let settings = try SettingsStorage(rootPath: root.path, instanceID: UUID().uuidString)
        _ = try settings.updateServerState { $0.isRegisteredWithServer = true }
        let transport = Transport()
        let service = ServerRegistration(variant: .regtest, settings: settings,
            client: Client(serverURL: ServerEndpoint.baseURL(for: .regtest), transport: transport))
        try await service.ensureRegistered()
        try await service.ensureRegistered()
        #expect(await transport.paths == ["/v0/app_version"])
        try await service.reportLastLogin()
        #expect(service.state.lastReportedDeviceFingerprint == DeviceInfo.fingerprint(DeviceInfo.current()))
        #expect(await transport.bodies.last?.contains("device_info") == true)
        try await service.reportLastLogin()
        let body = try #require(await transport.bodies.last)
        let object = try #require(JSONSerialization.jsonObject(with: Data(body.utf8)) as? [String: Any])
        #expect(object.isEmpty)
        let before = service.state
        await transport.failNextLogin()
        do { try await service.reportLastLogin(); Issue.record("Expected network failure") } catch {}
        #expect(service.state == before)
    }
}
