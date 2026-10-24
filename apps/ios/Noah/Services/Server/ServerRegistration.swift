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
    private let registrationAddress: (() async throws -> String)?
    private let mailboxAuthorization: ((Int64) async throws -> Components.Schemas.AuthorizeMailboxPayload)?
    private let log = AppLog("serverRegistration")
    @ObservationIgnored private var registration: Task<Void, Error>?
    private var checkedVersion = false
    private var updatingMailbox = false

    init(variant: AppVariant, settings: SettingsStorage = .shared, session: WalletSession = .shared,
         client: Client? = nil, registrationAddress: (() async throws -> String)? = nil,
         mailboxAuthorization: ((Int64) async throws -> Components.Schemas.AuthorizeMailboxPayload)? = nil) {
        self.settings = settings
        self.session = session
        self.registrationAddress = registrationAddress
        self.mailboxAuthorization = mailboxAuthorization
        self.client = client ?? ServerClient.make(variant: variant,
            tokens: AuthTokenProvider(dependencies: .live(variant: variant)))
        state = settings.persistedState(PersistedServerState.self, key: PersistedServerState.key) ?? .init()
    }

    func ensureRegistered(completeServices: (@MainActor () async throws -> Void)? = nil) async throws {
        if let registration { return try await registration.value }
        let attempt = Task {
            if !checkedVersion {
                do {
                    versionInfo = try await client.checkAppVersion(body: .json(.init(
                        clientVersion: DeviceInfo.current().appVersion ?? "0.0.1"))).ok.body.json
                } catch {
                    log.w(#"Failed to check app version, allowing app to continue"#)
                }
                checkedVersion = true
            }
            guard state.isRegisteredWithServer != true else { return }
            let address: String
            if let registrationAddress { address = try await registrationAddress() }
            else { address = try await session.peekAddress(index: 0) }
            let device = DeviceInfo.current()
            let result = try await client.register(body: .json(.init(
                arkAddress: address, deviceInfo: device.registrationPayload, lnAddress: nil))).ok.body.json
            try await completeServices?()
            try apply(result, device: device)
            log.d(#"Successfully registered with server"#)
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
        if response.message == #"Email already verified"# { // The server's message, not UI copy.
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

    /// RN `resetRegistration`: forget the server identity so the next
    /// `ensureRegistered` registers again (Settings → re-register).
    func resetRegistration() throws {
        try update {
            $0.isRegisteredWithServer = false
            $0.lightningAddress = nil
            $0.nostrPubkey = nil
            $0.isBackupEnabled = false
            $0.emailAddress = nil
            $0.isEmailVerified = false
            $0.isEmailPromptDismissed = false
            $0.mailboxAuthorizationExpiry = nil
            $0.lastReportedDeviceFingerprint = nil
            $0.lastDeviceReportAt = nil
        }
    }

    /// Publish success only after the explicit reset flow has registered every service.
    func reRegister(loadWallet: @escaping @MainActor () async throws -> Void,
                    registerPush: @escaping @MainActor () async throws -> Void) async throws {
        guard registration == nil else { throw IdentityValidationError("Registration is in progress.") }
        try resetRegistration()
        try await ensureRegistered {
            try await loadWallet()
            try await registerPush()
            guard self.state.isMailboxAuthorizationEnabled != false else {
                throw IdentityValidationError("Mailbox authorization is disabled")
            }
            guard !self.updatingMailbox else { throw IdentityValidationError("Mailbox update is in progress.") }
            self.updatingMailbox = true
            defer { self.updatingMailbox = false }
            try await self.authorizeMailbox()
        }
    }

    /// Re-reads `server-storage` (after the wallet was deleted).
    func reload() {
        state = settings.persistedState(PersistedServerState.self, key: PersistedServerState.key) ?? .init()
    }

    func dismissEmailPrompt() {
        do { try update { $0.isEmailPromptDismissed = true } }
        catch { log.w(#"Failed to save email prompt dismissal"#) }
    }

    func setMailboxEnabled(_ enabled: Bool) async throws {
        guard !updatingMailbox else { throw IdentityValidationError("Mailbox update is in progress.") }
        updatingMailbox = true
        defer { updatingMailbox = false }
        if enabled {
            try await authorizeMailbox()
            try update { $0.isMailboxAuthorizationEnabled = true }
        } else {
            _ = try await client.revokeMailboxAuthorization().ok
            try update { $0.isMailboxAuthorizationEnabled = false; $0.mailboxAuthorizationExpiry = nil }
        }
    }

    func refreshMailboxIfNeeded() async throws {
        guard !updatingMailbox, state.isRegisteredWithServer == true,
              state.isMailboxAuthorizationEnabled != false,
              (state.mailboxAuthorizationExpiry ?? 0) < Date().timeIntervalSince1970 + 60 * 86400 else { return }
        updatingMailbox = true
        defer { updatingMailbox = false }
        try await authorizeMailbox()
    }

    private func authorizeMailbox() async throws {
        let expiry = Int64(Date().timeIntervalSince1970) + 89 * 86400
        let payload: Components.Schemas.AuthorizeMailboxPayload
        if let mailboxAuthorization { payload = try await mailboxAuthorization(expiry) }
        else {
            let authorization = try await session.withOpenWallet { try $0.wallet.mailboxAuthorization(authorizationExpiry: expiry) }
            payload = .init(encoded: authorization.encoded, expiry: authorization.expiry, mailboxId: authorization.mailboxId)
        }
        _ = try await client.authorizeMailbox(body: .json(payload)).ok
        try update { $0.mailboxAuthorizationExpiry = Double(payload.expiry) }
    }

    func setBackupEnabled(_ enabled: Bool) async throws {
        _ = try await client.updateBackupSettings(body: .json(.init(backupEnabled: enabled))).ok
        try update { $0.isBackupEnabled = enabled }
    }

    func saveDisplayName(_ name: String) async throws {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        _ = try await client.updateProfile(body: .json(.init(displayName: name.isEmpty ? nil : name))).ok
        var profile = settings.persistedState(PersistedProfileState.self, key: PersistedProfileState.key) ?? .init()
        profile.displayName = name
        try settings.setPersistedState(profile, key: PersistedProfileState.key, version: PersistedProfileState.version)
    }

    func refreshProfile() async throws {
        let info = try await client.getUserInfo().ok.body.json
        try update { $0.emailAddress = info.email; $0.nostrPubkey = info.nostrPubkey }
    }

    private func verifiedEmail(_ email: String) throws {
        try update { $0.emailAddress = email; $0.isEmailVerified = true }
    }

    private func update(_ mutation: (inout PersistedServerState) -> Void) throws {
        state = try settings.updateServerState(mutation)
    }
}
