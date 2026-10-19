import Foundation
import Observation

/// Shared by launch, onboarding and Home. Mutations persist the RN zustand state.
@MainActor
@Observable
final class ServerRegistration {
    private(set) var state: PersistedServerState
    private(set) var isRegistering = false
    private(set) var versionInfo: Components.Schemas.AppVersionInfo?
    private let client: Client
    private let settings: SettingsStorage
    private let session: WalletSession
    private let log = AppLog("serverRegistration")
    @ObservationIgnored private var registration: Task<Void, Error>?
    private var checkedVersion = false

    init(variant: AppVariant, settings: SettingsStorage = .shared, session: WalletSession = .shared,
         client: Client? = nil) {
        self.settings = settings
        self.session = session
        self.client = client ?? ServerClient.make(variant: variant,
            tokens: AuthTokenProvider(dependencies: .live(variant: variant)))
        state = settings.persistedState(PersistedServerState.self, key: PersistedServerState.key) ?? .init()
    }

    func ensureRegistered() async throws {
        if let registration { return try await registration.value }
        let attempt = Task {
            if !checkedVersion {
                do {
                    versionInfo = try await client.checkAppVersion(body: .json(.init(
                        clientVersion: DeviceInfo.current().appVersion ?? "0.0.1"))).ok.body.json
                } catch {
                    log.w("Failed to check app version, allowing app to continue")
                }
                checkedVersion = true
            }
            guard state.isRegisteredWithServer != true else { return }
            let address = try await session.peekAddress(index: 0)
            let device = DeviceInfo.current()
            let result = try await client.register(body: .json(.init(
                arkAddress: address, deviceInfo: device.registrationPayload, lnAddress: nil))).ok.body.json
            try apply(result, device: device)
            log.d("Successfully registered with server")
        }
        registration = attempt
        isRegistering = true
        defer { registration = nil; isRegistering = false }
        try await attempt.value
    }

    func apply(_ response: Components.Schemas.RegisterResponse, device: Components.Schemas.DeviceInfo) throws {
        try update {
            $0.isRegisteredWithServer = true
            $0.lightningAddress = response.lightningAddress
            $0.nostrPubkey = response.nostrPubkey
            $0.isBackupEnabled = true
            $0.emailAddress = response.email
            $0.isEmailVerified = response.isEmailVerified
            $0.lastReportedDeviceFingerprint = DeviceInfo.fingerprint(device)
            $0.lastDeviceReportAt = Date().timeIntervalSince1970 * 1000
        }
        var profile = settings.persistedState(PersistedProfileState.self, key: PersistedProfileState.key) ?? .init()
        profile.displayName = response.displayName ?? ""
        try settings.setPersistedState(profile, key: PersistedProfileState.key, version: PersistedProfileState.version)
    }

    func reportLastLogin() async throws {
        let device = DeviceInfo.current()
        let include = state.isRegisteredWithServer == true && DeviceInfo.shouldReport(device,
            fingerprint: state.lastReportedDeviceFingerprint, reportedAt: state.lastDeviceReportAt)
        _ = try await client.reportLastLogin(body: .json(.init(deviceInfo: include ? device.loginPayload : nil))).ok
        if include {
            try update {
                $0.lastReportedDeviceFingerprint = DeviceInfo.fingerprint(device)
                $0.lastDeviceReportAt = Date().timeIntervalSince1970 * 1000
            }
        }
    }

    /// Does not change the saved email until verification succeeds.
    func sendEmail(_ email: String) async throws -> Bool {
        try await ensureRegistered()
        let response = try await client.sendVerificationEmail(body: .json(.init(email: email))).ok.body.json
        if response.message == "Email already verified" {
            try verifiedEmail(response.email ?? email)
            return true
        }
        return false
    }

    func verifyEmail(code: String, email: String) async throws {
        let response = try await client.verifyEmail(body: .json(.init(code: code))).ok.body.json
        guard response.success else { throw IdentityValidationError("Invalid or expired verification code.") }
        try verifiedEmail(response.email ?? email.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    func saveIdentity(username: String, npub: String) async throws {
        let payload = try LightningIdentity.payload(username: username, npub: npub)
        try await ensureRegistered()
        let response = try await client.updateLightningIdentity(body: .json(payload)).ok.body.json
        try update {
            $0.lightningAddress = response.lightningAddress
            $0.nostrPubkey = response.nostrPubkey
        }
    }

    func dismissEmailPrompt() {
        do { try update { $0.isEmailPromptDismissed = true } }
        catch { log.w("Failed to save email prompt dismissal") }
    }

    private func verifiedEmail(_ email: String) throws {
        try update { $0.emailAddress = email; $0.isEmailVerified = true }
    }

    private func update(_ mutation: (inout PersistedServerState) -> Void) throws {
        state = try settings.updateServerState(mutation)
    }
}
