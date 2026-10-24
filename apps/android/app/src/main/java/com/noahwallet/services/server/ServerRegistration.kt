package com.noahwallet.services.server

import com.noahwallet.api.generated.client.*
import com.noahwallet.api.generated.models.*
import com.noahwallet.services.storage.ServerStateStorage
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ServerRegistration(
    private val client: HttpClient,
    private val storage: ServerStateStorage,
    private val peekAddress: suspend () -> String,
    private val device: () -> com.noahwallet.api.generated.models.DeviceInfo = DeviceInfo::current,
    private val now: () -> Long = System::currentTimeMillis,
    private val warn: (String) -> Unit,
    private val mailboxAuthorization: suspend (Long) -> AuthorizeMailboxPayload = { throw ServerFailure("""Wallet unavailable""") },
) {
    val state get() = storage.state
    private val version = MutableStateFlow<AppVersionInfo?>(null)
    val versionChanges = version.asStateFlow()
    val versionInfo get() = version.value
    private val versionCheck = Mutex()
    private val registration = Mutex()
    private val reporting = Mutex()
    private val mailbox = Mutex()
    private var checkedVersion = false

    suspend fun checkVersionOnce() = versionCheck.withLock {
        if (!checkedVersion) {
            try {
                version.value = V0AppVersionClient(client).checkAppVersion(AppVersionCheckPayload(device().appVersion ?: "0.0.1")).value()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { warn("Failed to check app version, allowing app to continue") }
            checkedVersion = true
        }
    }

    suspend fun ensureRegistered() = registration.withLock { register() }

    private suspend fun register(completeServices: (suspend () -> Unit)? = null) {
        if (state.isRegisteredWithServer == true) return
        val info = device()
        val response = V0RegisterClient(client).register(RegisterPayload(
            arkAddress = peekAddress(), deviceInfo = info.registrationPayload())).value()
        completeServices?.invoke()
        apply(response, info)
    }

    internal fun apply(response: RegisterResponse, info: com.noahwallet.api.generated.models.DeviceInfo) {
        // Write profile first so a failed write leaves registration retryable.
        storage.setDisplayName(response.displayName ?: "")
        storage.update { it.copy(isRegisteredWithServer = true, lightningAddress = response.lightningAddress,
            nostrPubkey = response.nostrPubkey, isBackupEnabled = true, emailAddress = response.email,
            isEmailVerified = response.isEmailVerified, lastReportedDeviceFingerprint = DeviceInfo.fingerprint(info),
            lastDeviceReportAt = now().toDouble()) }
    }

    suspend fun reportLastLogin() = reporting.withLock {
        val info = device()
        val previous = state
        val include = previous.isRegisteredWithServer == true && DeviceInfo.shouldReport(info,
            previous.lastReportedDeviceFingerprint, previous.lastDeviceReportAt, now())
        V0ReportLastLoginClient(client).reportLastLogin(ReportLastLoginPayload(if (include) info.loginPayload() else null)).value()
        if (include) storage.update { it.copy(lastReportedDeviceFingerprint = DeviceInfo.fingerprint(info), lastDeviceReportAt = now().toDouble()) }
    }

    suspend fun sendEmail(email: String): Boolean {
        ensureRegistered()
        val response = V0EmailSendVerificationClient(client).sendVerificationEmail(SendEmailVerificationPayload(email)).value()
        if (response.message == "Email already verified") {
            verifiedEmail(response.email ?: email)
            return true
        }
        return false
    }

    suspend fun verifyEmail(code: String, email: String) {
        val response = V0EmailVerifyClient(client).verifyEmail(VerifyEmailPayload(code)).value()
        if (!response.success) throw ServerFailure("Invalid or expired verification code.")
        verifiedEmail(response.email ?: email.trim())
    }

    suspend fun saveIdentity(username: String, npub: String) {
        val payload = LightningIdentity.payload(username, npub)
        ensureRegistered()
        val response = V0UpdateLightningIdentityClient(client).updateLightningIdentity(payload).value()
        storage.update { it.copy(lightningAddress = response.lightningAddress, nostrPubkey = response.nostrPubkey) }
    }

    fun dismissEmailPrompt() {
        try { storage.update { it.copy(isEmailPromptDismissed = true) } }
        catch (_: Exception) { warn("Failed to save email prompt dismissal") }
    }

    suspend fun resetRegistration() = registration.withLock { clearRegistration() }

    private fun clearRegistration() {
        storage.update { it.copy(isRegisteredWithServer = false, lightningAddress = null, nostrPubkey = null,
            isBackupEnabled = false, emailAddress = null, isEmailVerified = false, isEmailPromptDismissed = false,
            mailboxAuthorizationExpiry = null, lastReportedDeviceFingerprint = null, lastDeviceReportAt = null) }
    }

    suspend fun reRegister(loadWallet: suspend () -> Unit, registerPush: suspend () -> Unit) = registration.withLock {
        clearRegistration()
        register {
            loadWallet()
            registerPush()
            if (!mailbox.tryLock()) throw ServerFailure("Mailbox update is in progress.")
            try {
                if (state.isMailboxAuthorizationEnabled == false) throw ServerFailure("Mailbox authorization is disabled")
                authorizeMailbox()
            } finally { mailbox.unlock() }
        }
    }

    suspend fun setBackupEnabled(enabled: Boolean) {
        V0BackupSettingsClient(client).updateBackupSettings(BackupSettingsPayload(enabled)).value()
        storage.update { it.copy(isBackupEnabled = enabled) }
    }

    suspend fun setMailboxEnabled(enabled: Boolean) {
        if (!mailbox.tryLock()) throw ServerFailure("Mailbox update is in progress.")
        try {
            if (enabled) {
                authorizeMailbox()
                storage.update { it.copy(isMailboxAuthorizationEnabled = true) }
            } else {
                V0MailboxRevokeClient(client).revokeMailboxAuthorization().value()
                storage.update { it.copy(isMailboxAuthorizationEnabled = false, mailboxAuthorizationExpiry = null) }
            }
        } finally { mailbox.unlock() }
    }

    suspend fun refreshMailboxIfNeeded() {
        if (!mailbox.tryLock()) return
        try {
            if (state.isRegisteredWithServer != true || state.isMailboxAuthorizationEnabled == false ||
                (state.mailboxAuthorizationExpiry ?: 0.0) >= now() / 1000 + 60 * 86400) return
            authorizeMailbox()
        } finally { mailbox.unlock() }
    }

    private suspend fun authorizeMailbox() {
        val payload = mailboxAuthorization(now() / 1000 + 89 * 86400)
        V0MailboxAuthorizeClient(client).authorizeMailbox(payload).value()
        storage.update { it.copy(mailboxAuthorizationExpiry = payload.expiry.toDouble()) }
    }

    suspend fun saveDisplayName(name: String) {
        val trimmed = name.trim()
        V0UpdateProfileClient(client).updateProfile(UpdateProfilePayload(trimmed.takeIf { it.isNotEmpty() })).value()
        storage.setDisplayName(trimmed)
    }

    suspend fun refreshProfile() {
        val info = V0UserInfoClient(client).getUserInfo().value()
        storage.update { it.copy(emailAddress = info.email, nostrPubkey = info.nostrPubkey) }
    }

    private fun verifiedEmail(email: String) { storage.update { it.copy(emailAddress = email, isEmailVerified = true) } }
}
