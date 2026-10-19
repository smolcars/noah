package com.noahwallet.services.wallet

import android.content.Context
import com.noahwallet.BuildConfig
import com.noahwallet.services.storage.*
import io.github.niteshbalusu11.bark.generateMnemonic
import kotlinx.coroutines.*

/** Create-or-resume: save the seed before files, and clean up only a fresh failed attempt. */
class WalletCreator(private val dependencies: Dependencies) {
    sealed class Failure(message: String) : Exception(message) {
        class IncompleteExistingData : Failure("Existing wallet data is incomplete")
        class SecureStorageUnavailable(val reason: SecretFailure) : Failure("Secure storage unavailable")
    }

    class Dependencies(
        val readSeed: suspend () -> SecretResult,
        val saveSeed: suspend (String) -> Unit,
        val deleteSeed: suspend (String) -> Unit,
        val walletDataExists: () -> Boolean,
        val datadirExists: () -> Boolean,
        val removeAttemptFiles: (Boolean) -> Boolean,
        val generateMnemonic: () -> String,
        val openExisting: suspend (String) -> Unit,
        val createNew: suspend (String) -> Unit,
        val close: suspend () -> Unit,
    )

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var inFlight: Deferred<Unit>? = null

    suspend fun createOrResume() {
        val attempt = synchronized(lock) {
            inFlight ?: scope.async(start = CoroutineStart.LAZY) {
                try { run() } finally { synchronized(lock) { inFlight = null } }
            }.also { inFlight = it; it.start() }
        }
        attempt.await()
    }

    private suspend fun run() {
        val seed = when (val result = dependencies.readSeed()) {
            is SecretResult.Unavailable -> throw Failure.SecureStorageUnavailable(result.reason)
            is SecretResult.Found -> result.value.takeIf { it.isNotEmpty() }
            SecretResult.NotFound -> null
        }
        val dataExists = dependencies.walletDataExists()
        if (seed != null && dataExists) {
            dependencies.openExisting(seed)
            return
        }
        if (seed != null || dataExists) throw Failure.IncompleteExistingData()
        val datadirExisted = dependencies.datadirExists()
        val mnemonic = dependencies.generateMnemonic()
        dependencies.saveSeed(mnemonic)
        try {
            dependencies.createNew(mnemonic)
        } catch (failure: Throwable) {
            // Keep the seed and files if closing fails: an SDK handle may still own them.
            try { dependencies.close() } catch (_: Exception) { throw failure }
            if (dependencies.removeAttemptFiles(datadirExisted)) dependencies.deleteSeed(mnemonic)
            throw failure
        }
    }

    companion object {
        fun live(context: Context, session: WalletSession = WalletSession.shared(context)): WalletCreator {
            val variant = BuildConfig.APP_VARIANT
            val storage = SeedStorage(context.applicationContext, variant)
            val location = WalletDataLocation(context.applicationContext.filesDir, variant)
            return WalletCreator(Dependencies(
                readSeed = { storage.read() },
                saveSeed = { value ->
                    val result = storage.save(value)
                    if (result !is SecretResult.Found || result.value != value) {
                        throw Failure.SecureStorageUnavailable(
                            (result as? SecretResult.Unavailable)?.reason ?: SecretFailure.migrationFailed)
                    }
                },
                deleteSeed = { storage.delete(it) },
                walletDataExists = { location.hasAnyData },
                datadirExists = { location.directory.exists() },
                removeAttemptFiles = location::removeAttemptFiles,
                generateMnemonic = ::generateMnemonic,
                openExisting = { session.ensureOpen(it, true, BuildConfig.VERSION_NAME) },
                createNew = { session.createNew(it, true, BuildConfig.VERSION_NAME) },
                close = { session.close() },
            ))
        }
    }
}
