package com.noahwallet.services.wallet

import android.content.Context
import com.noahwallet.BuildConfig
import com.noahwallet.services.storage.WalletDataLocation
import io.github.niteshbalusu11.bark.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One owner per process: bark locks the datadir. All wallet operations go through this session. */
class WalletSession internal constructor(
    private val variant: String,
    private val location: WalletDataLocation,
    private val inspect: (File) -> WalletDatabase.Properties = WalletDatabase::properties,
    private val opener: suspend (String, String, Boolean, String, Boolean) -> Handles = sdkOpener(variant),
) {
    enum class OpenFailure { walletDatabaseMissing, notABarkWallet, networkMismatch, busy, walletDatabaseExists }
    class OpenError(val reason: OpenFailure, val found: String? = null) : Exception(reason.name)
    class SessionError : Exception("Wallet not open")

    data class Summary(val network: Network, val fingerprint: String, val balance: WalletBalance, val movementCount: Int) {
        val spendableSats: ULong get() = balance.ark.spendable
    }

    internal interface Handles {
        suspend fun peekAddress(index: UInt): String
        suspend fun summary(): Summary
        suspend fun sync()
        suspend fun maintenanceDelegated()
        suspend fun bolt11Invoice(amountSats: ULong): String
        suspend fun claimLightningReceives()
        suspend fun closestExpiryBlocks(): Long?
        suspend fun createSnapshot(destination: File): WalletSnapshotInfo
        suspend fun stopDaemonWait()
        fun close()
    }

    private sealed interface State {
        data object Closed : State
        class Opening(val attempt: Deferred<Unit>) : State
        class Open(val handles: Handles) : State
        data object Closing : State
    }

    private val lock = Any()
    private val operations = Mutex()
    // A UI caller's cancellation must not abandon a native open and leak its datadir lock.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var state: State = State.Closed
    val isOpen: Boolean get() = synchronized(lock) { state is State.Open }

    suspend fun openExisting(mnemonic: String, runDaemon: Boolean, appVersion: String) {
        beginOpen(mnemonic, runDaemon, appVersion, create = false, join = false)?.await()
    }

    suspend fun ensureOpen(mnemonic: String, runDaemon: Boolean, appVersion: String) {
        beginOpen(mnemonic, runDaemon, appVersion, create = false, join = true)?.await()
    }

    suspend fun createNew(mnemonic: String, runDaemon: Boolean, appVersion: String) {
        beginOpen(mnemonic, runDaemon, appVersion, create = true, join = false)?.await()
    }

    private suspend fun beginOpen(
        mnemonic: String, runDaemon: Boolean, appVersion: String, create: Boolean, join: Boolean,
    ): Deferred<Unit>? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            when (val current = state) {
                is State.Open -> if (join) return@synchronized null else throw OpenError(OpenFailure.busy)
                is State.Opening -> if (join) return@synchronized current.attempt else throw OpenError(OpenFailure.busy)
                State.Closing -> throw OpenError(OpenFailure.busy)
                State.Closed -> Unit
            }
            if (create) {
                if (location.databaseExists) throw OpenError(OpenFailure.walletDatabaseExists)
                check(location.directory.isDirectory || location.directory.mkdirs()) { "Cannot create wallet directory" }
            } else {
                if (!location.databaseExists) throw OpenError(OpenFailure.walletDatabaseMissing)
                val properties = try { inspect(location.database) } catch (_: Exception) {
                    throw OpenError(OpenFailure.notABarkWallet)
                }
                if (properties.network != WalletConfiguration.base(variant).networkName) {
                    throw OpenError(OpenFailure.networkMismatch, properties.network)
                }
            }
            val attempt = scope.async(start = CoroutineStart.LAZY) {
                try {
                    val handles = opener(mnemonic, location.directory.path, runDaemon, appVersion, create)
                    synchronized(lock) { state = State.Open(handles) }
                } catch (failure: Throwable) {
                    synchronized(lock) { state = State.Closed }
                    throw failure
                }
            }
            state = State.Opening(attempt)
            attempt.start()
            attempt
        }
    }

    suspend fun summary(): Summary? = operations.withLock {
        synchronized(lock) { (state as? State.Open)?.handles }?.summary()
    }

    suspend fun sync() = operations.withLock {
        synchronized(lock) { (state as? State.Open)?.handles }?.sync()
        Unit
    }

    suspend fun peekAddress(index: UInt): String = operations.withLock {
        val handles = synchronized(lock) { (state as? State.Open)?.handles } ?: throw SessionError()
        handles.peekAddress(index)
    }

    suspend fun maintenanceDelegated() = withOpen { it.maintenanceDelegated() }
    suspend fun bolt11Invoice(amountSats: ULong): String = withOpen { it.bolt11Invoice(amountSats) }
    suspend fun claimLightningReceives() = withOpen { it.claimLightningReceives() }
    suspend fun closestExpiryBlocks(): Long? = withOpen { it.closestExpiryBlocks() }

    private suspend fun <T> withOpen(block: suspend (Handles) -> T): T = operations.withLock {
        val handles = synchronized(lock) { (state as? State.Open)?.handles } ?: throw SessionError()
        block(handles)
    }

    suspend fun createSnapshot(destination: File): WalletSnapshotInfo = operations.withLock {
        val handles = synchronized(lock) { (state as? State.Open)?.handles } ?: throw SessionError()
        handles.createSnapshot(destination)
    }

    suspend fun close() {
        val handles = synchronized(lock) {
            when (val current = state) {
                State.Closed -> return
                is State.Open -> current.handles.also { state = State.Closing }
                else -> throw OpenError(OpenFailure.busy)
            }
        }
        withContext(NonCancellable + Dispatchers.IO) {
            operations.withLock {
                try {
                    handles.stopDaemonWait()
                } catch (failure: Throwable) {
                    synchronized(lock) { state = State.Open(handles) }
                    throw failure
                }
                handles.close()
                synchronized(lock) { state = State.Closed }
            }
        }
    }

    companion object {
        @Volatile private var instance: WalletSession? = null

        fun shared(context: Context): WalletSession = instance ?: synchronized(this) {
            instance ?: WalletSession(BuildConfig.APP_VARIANT,
                WalletDataLocation(context.applicationContext.filesDir, BuildConfig.APP_VARIANT))
                .also { instance = it }
        }

        private fun sdkOpener(variant: String): suspend (String, String, Boolean, String, Boolean) -> Handles =
            { mnemonic, datadir, runDaemon, appVersion, create ->
                val configuration = WalletConfiguration.base(variant)
                val config = configuration.sdkConfig(WalletConfiguration.userAgent(variant, appVersion))
                // Existing wallets go through the SDK's read-only identity check (properties,
                // network, seed fingerprint) before anything writes; only creation may use default.
                val onchain = if (create) {
                    OnchainWallet.default(configuration.network, mnemonic, config, datadir)
                } else {
                    OnchainWallet.openExisting(configuration.network, mnemonic, config, datadir)
                }
                val wallet = try {
                    Wallet.openWithOnchain(configuration.network, mnemonic, config,
                        WalletOpenArgs(runDaemon = runDaemon, datadir = datadir, createIfNotExists = create), onchain)
                } catch (failure: Throwable) {
                    onchain.close()
                    throw failure
                }
                SdkHandles(wallet, onchain)
            }
    }

    private class SdkHandles(val wallet: Wallet, val onchain: OnchainWallet) : Handles {
        override suspend fun peekAddress(index: UInt): String = wallet.peekAddress(index)
        override suspend fun summary(): Summary {
            val ark = wallet.balance()
            val chain = onchain.balance()
            return Summary(wallet.network(), wallet.fingerprint(), WalletBalance(
                WalletBalance.Ark(ark.spendableSats, ark.needsRefreshSats, ark.pendingLightningSendSats,
                    ark.claimableLightningReceiveSats, ark.pendingInRoundSats, ark.pendingExitSats,
                    ark.pendingBoardSats, ark.pendingArkoorSendSats, ark.pendingOffboardSats,
                    pending = ark.pendingSats, total = ark.totalSats),
                WalletBalance.Onchain(chain.confirmedSats, chain.pendingSats, chain.totalSats),
            ), wallet.history().size)
        }
        override suspend fun sync() { onchain.sync(); wallet.sync() }
        override suspend fun maintenanceDelegated() { wallet.maintenanceDelegated() }
        override suspend fun bolt11Invoice(amountSats: ULong) = wallet.bolt11Invoice(amountSats, null).invoice
        override suspend fun claimLightningReceives() { wallet.tryClaimAllLightningReceives(false) }
        override suspend fun closestExpiryBlocks(): Long? {
            val vtxos = wallet.vtxos()
            if (vtxos.isEmpty()) return null
            val tip = onchain.tipHeight().toLong()
            return vtxos.minOf { it.expiryHeight.toLong() - tip }
        }
        override suspend fun createSnapshot(destination: File) = wallet.createSnapshot(destination.path)
        override suspend fun stopDaemonWait() { wallet.stopDaemonWait() }
        override fun close() { try { wallet.close() } finally { onchain.close() } }
    }
}
