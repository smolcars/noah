import Foundation
import HTTPTypes
import LocalAuthentication
@testable import Noah
import OpenAPIRuntime
import Testing

struct BiometricLockPolicyTests {
    @Test func onlyMissingPasscodeAllowsRecoveryPhraseWarning() {
        #expect(RecoveryPhraseAuthentication.unavailable(NSError(domain: LAError.errorDomain,
                                                                 code: LAError.passcodeNotSet.rawValue)) == .noScreenLock)
        for code in [LAError.authenticationFailed, .userCancel, .systemCancel, .appCancel,
                     .biometryNotAvailable, .biometryNotEnrolled, .biometryLockout, .notInteractive] {
            #expect(RecoveryPhraseAuthentication.unavailable(NSError(domain: LAError.errorDomain,
                                                                     code: code.rawValue)) == .denied)
        }
        #expect(RecoveryPhraseAuthentication.unavailable(nil) == .denied)
        #expect(RecoveryPhraseAuthentication.unavailable(NSError(domain: "other", code: LAError.passcodeNotSet.rawValue)) == .denied)
    }

    @Test func launchBackgroundAndStaleAuthentication() {
        var policy = BiometricLockPolicy(enabled: true)
        #expect(policy.locked)
        policy.authenticated(generation: policy.generation)
        #expect(!policy.locked)
        let old = policy.generation
        policy.background()
        #expect(policy.locked)
        policy.authenticated(generation: old)
        #expect(policy.locked, "a prompt begun before background must not unlock the foreground")
        policy.authenticated(generation: policy.generation)
        #expect(!policy.locked)
        policy.setEnabled(false)
        policy.background()
        #expect(!policy.locked)
        policy.setEnabled(true)
        #expect(!policy.locked, "enabling has already authenticated; lock next background")
        policy.background()
        #expect(policy.locked)
    }
}

struct FeedbackPayloadTests {
    @Test func screenshotBoundaryAndMime() throws {
        var png = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
        png.append(Data(count: FeedbackDraft.maximumScreenshotBytes - png.count))
        let attachment = try FeedbackDraft.attachment(png)
        #expect(Data(base64Encoded: attachment.base64Data)?.count == FeedbackDraft.maximumScreenshotBytes)
        #expect(attachment.contentType == "image/png")
        png.append(0)
        #expect(throws: IdentityValidationError.self) { try FeedbackDraft.attachment(png) }
        #expect(throws: IdentityValidationError.self) { try FeedbackDraft.attachment(Data("not an image".utf8)) }
        #expect(try FeedbackDraft.attachment(Data([0xFF, 0xD8, 0xFF])).contentType == "image/jpeg")
    }
    @Test func textLimitsUseUTF8AndPayloadMatchesRN() throws {
        var draft = FeedbackDraft(name: " Alice ", email: " ", subject: String(repeating: "é", count: 75), body: " message ")
        let payload = try draft.payload(device: .init(appVersion: "1", osName: "iOS"))
        #expect(payload.name == "Alice")
        #expect(payload.body == "message")
        #expect(payload.email == nil)
        #expect(payload.deviceInfo?.osName == "iOS")
        let data = try JSONEncoder().encode(payload)
        let json = try #require(String(bytes: data, encoding: .utf8))
        #expect(json.contains("device_info"))
        #expect(json.contains("app_version"))
        draft.subject += "a"
        #expect(throws: IdentityValidationError.self) { try draft.payload(device: .init()) }
        draft.subject = "ok"; draft.body = String(repeating: "x", count: 60_001)
        #expect(throws: IdentityValidationError.self) { try draft.payload(device: .init()) }
        draft.body = " "
        #expect(throws: IdentityValidationError.self) { try draft.payload(device: .init()) }
    }
}

@MainActor
struct SettingsFeatureTests {
    @Test func liveLockResetDoesNotCarryPolicyIntoTheNextWallet() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "lock-reset-\(UUID())")
        defer { try? FileManager.default.removeItem(at: root) }
        let settings = try SettingsStorage(rootPath: root.path)
        try settings.setPersistedState(PersistedWalletState(isBiometricsEnabled: true), key: PersistedWalletState.key, version: 0)
        let model = AppModel(settings: settings)
        let liveLock = model.walletLock
        #expect(liveLock.policy.locked)
        let oldGeneration = liveLock.policy.generation
        liveLock.reset()
        #expect(model.walletLock === liveLock)
        #expect(!liveLock.policy.enabled && !liveLock.policy.locked)
        liveLock.policy.background()
        liveLock.policy.authenticated(generation: oldGeneration)
        #expect(!liveLock.policy.enabled && !liveLock.policy.locked)
        #expect(liveLock.policy.generation != oldGeneration)
    }

    @Test func successfulManualBackupSurvivesFailedListRefresh() async throws {
        var uploaded = false
        let refreshed = try await BackupSettingsView.backUpAndRefresh(backup: { uploaded = true },
                                                                      refresh: { throw URLError(.notConnectedToInternet) })
        #expect(uploaded)
        #expect(!refreshed)
        var listed = false
        await #expect(throws: URLError.self) {
            try await BackupSettingsView.backUpAndRefresh(backup: { throw URLError(.notConnectedToInternet) },
                                                          refresh: { listed = true })
        }
        #expect(!listed)
        #expect(try await BackupSettingsView.backUpAndRefresh(backup: {}, refresh: {}) == true)
    }

    @Test func rnStoresRoundTripAndPreferenceEditsPreserveIdentity() throws {
        SettingsStorage.bootstrap()
        let root = FileManager.default.temporaryDirectory.appending(path: "settings-\(UUID())")
        defer { try? FileManager.default.removeItem(at: root) }
        let storage = try SettingsStorage(rootPath: root.path, instanceID: UUID().uuidString)
        let profileJSON = #"{"state":{"displayName":"Alice","preferredCurrency":"EUR","bitcoinAmountUnit":"sats"},"version":0}"#
        storage.set(profileJSON, forKey: PersistedProfileState.key)
        var profile = try #require(storage.persistedState(PersistedProfileState.self, key: PersistedProfileState.key))
        #expect(profile.amountUnit == .sats)
        profile.preferredCurrency = "JPY"
        try storage.setPersistedState(profile, key: PersistedProfileState.key, version: 0)
        let encoded = try Data(#require(storage.string(forKey: PersistedProfileState.key)).utf8)
        let roundTrip = try JSONDecoder().decode(PersistedEnvelope<PersistedProfileState>.self, from: encoded)
        #expect(roundTrip.version == 0)
        #expect(roundTrip.state.displayName == "Alice")
        #expect(roundTrip.state.bitcoinAmountUnit == "sats")
        #expect(roundTrip.state.preferredCurrency == "JPY")
        storage.set(#"{"state":{"isAutoBoardingEnabled":false,"hasAttemptedAutoBoarding":true},"version":0}"#, forKey: PersistedTransactionState.key)
        let migrated = PersistedTransactionState.load(from: storage)
        #expect(!migrated.isAutoBoardingEnabled)
        #expect(!migrated.hasAttemptedAutoBoarding)
        let transactionJSON = #"{"state":{"isAutoBoardingEnabled":false,"hasAttemptedAutoBoarding":true},"version":1}"#
        let transaction = try JSONDecoder().decode(PersistedEnvelope<PersistedTransactionState>.self, from: Data(transactionJSON.utf8))
        var state = transaction.state
        state.setAutoBoarding(false)
        #expect(state.hasAttemptedAutoBoarding)
        state.setAutoBoarding(true)
        #expect(!state.hasAttemptedAutoBoarding)
        #expect(state.isAutoBoardingEnabled)
        try storage.setPersistedState(state, key: PersistedTransactionState.key, version: 1)
        #expect(storage.persistedState(PersistedTransactionState.self, key: PersistedTransactionState.key) == state)
    }

    @Test func legacyAttemptedTrueAllowsCurrentFundingOnceAndToggleCannotReplayIt() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let storage = try SettingsStorage(rootPath: root.appending(path: "mmkv").path)
        try storage.setDurably(#"{"state":{"isAutoBoardingEnabled":true,"hasAttemptedAutoBoarding":true},"version":1}"#,
                               forKey: PersistedTransactionState.key)
        let funding = AutoBoardFunding.Snapshot(walletID: "wallet", outpoints: ["current:0"])
        let ledger = AutoBoardFunding(storage: storage)
        #expect(PersistedTransactionState.load(from: storage).isAutoBoardingEnabled)
        #expect(try ledger.hasUnhandled(funding))
        #expect(try ledger.markHandled(funding))
        var state = PersistedTransactionState.load(from: storage)
        state.setAutoBoarding(false); state.setAutoBoarding(true)
        try storage.setPersistedState(state, key: PersistedTransactionState.key, version: 1)
        #expect(try !AutoBoardFunding(storage: storage).hasUnhandled(funding))
    }

    actor Transport: ClientTransport {
        var fail = false
        var bodies: [String] = []
        func setFailure(_ value: Bool) { fail = value }
        func send(_: HTTPRequest, body: HTTPBody?, baseURL _: URL, operationID _: String) async throws -> (HTTPResponse, HTTPBody?) {
            if let body { bodies.append(try await String(collecting: body, upTo: 65536)) }
            if fail { throw URLError(.notConnectedToInternet) }
            return (.init(status: .ok), HTTPBody(#"{"success":true}"#))
        }
    }

    @Test func backupSettingsCommitOnlyAfterServerSuccess() async throws {
        SettingsStorage.bootstrap()
        let root = FileManager.default.temporaryDirectory.appending(path: "backup-settings-\(UUID())")
        defer { try? FileManager.default.removeItem(at: root) }
        let storage = try SettingsStorage(rootPath: root.path, instanceID: UUID().uuidString)
        _ = try storage.updateServerState { $0.isBackupEnabled = false; $0.lightningAddress = "alice@example.com" }
        let transport = Transport()
        let serverURL = try #require(URL(string: "http://localhost"))
        let server = ServerRegistration(variant: .regtest, settings: storage,
                                        client: Client(serverURL: serverURL, transport: transport))
        await transport.setFailure(true)
        await #expect(throws: (any Error).self) { try await server.setBackupEnabled(true) }
        #expect(server.state.isBackupEnabled == false)
        await transport.setFailure(false)
        try await server.setBackupEnabled(true)
        #expect(server.state.isBackupEnabled == true)
        #expect(storage.persistedState(PersistedServerState.self, key: PersistedServerState.key)?.isBackupEnabled == true)
        try await server.setBackupEnabled(false)
        #expect(server.state.isBackupEnabled == false)
        #expect(server.state.lightningAddress == "alice@example.com")
        let body = try #require(await transport.bodies.last)
        #expect(try JSONDecoder().decode(Components.Schemas.BackupSettingsPayload.self, from: Data(body.utf8)).backupEnabled == false)
    }
}

extension BackupCoordinatorTests {
    @Test func deletingRememberedUploadInvalidatesAnInFlightUnchangedPass() async throws {
        let world = World()
        let clock = ManualClock()
        let coordinator = BackupCoordinator(dependencies: world.dependencies(clock: clock))
        try await coordinator.flush(.manual, requireEnabled: false)
        #expect(world.state.lastBackupId == "backup-sha-1")
        world.hold()
        let pass = Task { try await coordinator.flush(.manual, requireEnabled: false) }
        while !world.isHolding { await Task.yield() }
        let deletion = Task { await coordinator.forgetUploadedBackup("backup-sha-1") }
        world.releaseHeld()
        try await pass.value
        await deletion.value
        #expect(world.state.lastUploadedSnapshotSha256 == nil)
        #expect(world.state.lastBackupId == nil)
        #expect(world.state.backupPending)
        try await coordinator.flush(.manual, requireEnabled: false)
        #expect(world.calls.last == .some(nil), "same database must be uploaded again after deletion")
        #expect(world.state.lastBackupStatus == .success)
        await coordinator.cancelScheduled()
    }
}
