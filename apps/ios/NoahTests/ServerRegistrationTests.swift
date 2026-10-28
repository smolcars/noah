import Foundation
import HTTPTypes
@testable import Noah
import OpenAPIRuntime
import Testing

@MainActor
struct ServerRegistrationTests {
    actor ResetTransport: ClientTransport {
        var paths: [String] = []
        let failMailbox: Bool
        init(failMailbox: Bool) { self.failMailbox = failMailbox }
        func send(_ request: HTTPRequest, body _: HTTPBody?, baseURL _: URL, operationID: String) throws -> (HTTPResponse, HTTPBody?) {
            paths.append(request.path ?? "")
            if operationID == Operations.CheckAppVersion.id {
                return (.init(status: .ok), HTTPBody(#"{"update_required":false,"minimum_required_version":"0.0.1"}"#))
            }
            if operationID == Operations.Register.id {
                return (
                    .init(status: .ok),
                    HTTPBody(
                        #"{"display_name":"New","email":null,"is_email_verified":false,"lightning_address":"new@localhost.com""#
                            + #","nostr_pubkey":null,"status":"OK","user_status":"active"}"#))
            }
            if failMailbox { throw URLError(.notConnectedToInternet) }
            return (.init(status: .ok), HTTPBody(#"{"success":true}"#))
        }
    }

    @Test(arguments: ["load", "push", "mailbox", "disabled", "success"])
    func resetRegistrationPropagatesServiceFailuresAndCommitsLast(stop: String) async throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "reset-registration-\(UUID())")
        defer { try? FileManager.default.removeItem(at: root) }
        let settings = try SettingsStorage(rootPath: root.path)
        _ = try settings.updateServerState { $0.isRegisteredWithServer = true; $0.isMailboxAuthorizationEnabled = stop != "disabled" }
        let transport = ResetTransport(failMailbox: stop == "mailbox")
        let serverURL = try #require(URL(string: "http://localhost"))
        let service = ServerRegistration(variant: .regtest, settings: settings,
                                         client: Client(serverURL: serverURL, transport: transport),
                                         registrationAddress: { "ark-test" },
                                         mailboxAuthorization: { .init(encoded: "encoded", expiry: $0, mailboxId: "mailbox") })
        var steps: [String] = []
        do {
            try await service.reRegister(loadWallet: {
                #expect(service.state.isRegisteredWithServer == false)
                steps.append("load")
                if stop == "load" { throw URLError(.notConnectedToInternet) }
            }, registerPush: {
                #expect(service.state.isRegisteredWithServer == false)
                steps.append("push")
                if stop == "push" { try PushRegistration.Outcome.permissionDenied.requireCompleted() }
            })
            #expect(stop == "success")
        } catch { #expect(stop != "success") }
        #expect(steps == (stop == "load" ? ["load"] : ["load", "push"]))
        #expect(service.state.isRegisteredWithServer == (stop == "success"))
        #expect(service.state.isBackupEnabled == (stop == "success"))
        #expect((service.state.mailboxAuthorizationExpiry != nil) == (stop == "success"))
        let paths = await transport.paths
        #expect(paths.contains("/v0/mailbox/authorize") == (stop == "success" || stop == "mailbox"))
    }

    @Test func explicitPushRegistrationOnlySkipsUnsupportedDevices() throws {
        try PushRegistration.Outcome.registered(expoToken: "test").requireCompleted()
        try PushRegistration.Outcome.deviceNotSupported.requireCompleted()
        #expect(throws: IdentityValidationError.self) { try PushRegistration.Outcome.permissionDenied.requireCompleted() }
    }

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
            (#"{"display_name":"New","email":null,"is_email_verified":false,"lightning_address":"new@localhost.com","#
                + #""nostr_pubkey":null,"status":"OK","user_status":"active"}"#).utf8))
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
        func send(_ request: HTTPRequest, body: HTTPBody?, baseURL _: URL, operationID: String) async throws -> (HTTPResponse, HTTPBody?) {
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
        await #expect(throws: (any Error).self) { try await service.reportLastLogin() }
        #expect(service.state == before)
    }
}
