package com.noahwallet.services

import android.content.Context
import com.noahwallet.BuildConfig
import com.noahwallet.services.logging.AppLog
import com.noahwallet.services.server.*
import com.noahwallet.services.storage.*
import com.noahwallet.services.wallet.WalletSession
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One shared session, token provider and server client for launch and future background work. */
class AppServices private constructor(context: Context) {
    val session = WalletSession.shared(context)
    private val log = AppLog(context, "server")
    private val variant = BuildConfig.APP_VARIANT
    private val tokenItem = KeychainPassword.serverAuthToken(context, variant)
    private val seed = SeedStorage(context, variant)
    private val publicClient = ServerClient.make(ServerEndpoint.baseURL(variant))
    val tokens = AuthTokenProvider(
        readToken = { (tokenItem.read() as? SecretResult.Found)?.value },
        saveToken = tokenItem::saveAuthToken,
        clearToken = tokenItem::clearAuthToken,
        readMnemonic = { (seed.read() as? SecretResult.Found)?.value ?: throw ServerFailure("Wallet seed unavailable") },
        login = { ServerClient.login(publicClient, variant, it) },
    )
    val client = ServerClient.make(ServerEndpoint.baseURL(variant), tokens)
    val registration = ServerRegistration(client, ServerStateStorage(SettingsStorage(context)),
        peekAddress = { session.peekAddress(0u) }, warn = log::w)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startup = Mutex()
    private var started = false

    fun startServerServices() {
        scope.launch {
            startup.withLock {
                if (started || !session.isOpen) return@withLock
                try {
                    registration.ensureRegistered()
                    registration.reportLastLogin()
                    started = true
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { log.w("Server setup failed; it will retry on the next launch or resume") }
            }
        }
    }

    companion object {
        @Volatile private var instance: AppServices? = null
        fun shared(context: Context): AppServices = instance ?: synchronized(this) {
            instance ?: AppServices(context.applicationContext).also { instance = it }
        }
    }
}
