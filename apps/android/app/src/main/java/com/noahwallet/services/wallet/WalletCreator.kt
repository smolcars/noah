package com.noahwallet.services.wallet

import android.content.Context
import com.noahwallet.BuildConfig
import com.noahwallet.services.storage.*
import io.github.niteshbalusu11.bark.generateMnemonic
import kotlinx.coroutines.*

/** Create-or-resume: save the seed before files, and clean up only a fresh failed attempt. */
class WalletCreator(private val dependencies: Dependencies) {
    sealed class Failure(message: String) : Exception(message) {
        class IncompleteExistingData : Failure("""Existing wallet data is incomplete""")
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
        val reserve: () -> WalletSession.Reservation,
        val release: (WalletSession.Reservation) -> Unit,
        val createNew: suspend (WalletSession.Reservation, String) -> Unit,
        val beginOnboarding: () -> Unit = {},
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

    private suspend fun plan(): String? {
        val seed = when (val result = dependencies.readSeed()) {
            is SecretResult.Unavailable -> throw Failure.SecureStorageUnavailable(result.reason)
            is SecretResult.Found -> result.value.takeIf { it.isNotEmpty() }
            SecretResult.NotFound -> null
        }
        val dataExists = dependencies.walletDataExists()
        if (seed != null && dataExists) return seed
        if (seed != null || dataExists) throw Failure.IncompleteExistingData()
        return null
    }

    private suspend fun run() {
        plan()?.let { dependencies.openExisting(it); return }
        val reservation = dependencies.reserve()
        try {
            if (plan() != null) throw WalletSession.OpenError(WalletSession.OpenFailure.busy)
            createFresh(reservation)
        } finally { dependencies.release(reservation) }
    }

    private suspend fun createFresh(reservation: WalletSession.Reservation) {
        val datadirExisted = dependencies.datadirExists()
        dependencies.beginOnboarding()
        val mnemonic = dependencies.generateMnemonic()
        dependencies.saveSeed(mnemonic)
        try {
            dependencies.createNew(reservation, mnemonic)
        } catch (failure: Throwable) {
            if (failure is WalletSession.OpenError && failure.reason == WalletSession.OpenFailure.teardownFailed) throw failure
            if (dependencies.removeAttemptFiles(datadirExisted)) dependencies.deleteSeed(mnemonic)
            throw failure
        }
    }

    companion object {
        fun live(context: Context, session: WalletSession = WalletSession.shared(context), settings: SettingsStorage = SettingsStorage(context)): WalletCreator {
            val variant = BuildConfig.APP_VARIANT
            val storage = SeedStorage(context.applicationContext, variant)
            val location = WalletDataLocation(context.applicationContext.filesDir, variant)
            return WalletCreator(Dependencies(
                readSeed = {
                    check(!location.deletionMarker.exists()) { "Restart Noah to finish deleting the previous wallet" }
                    storage.read()
                },
                saveSeed = { value ->
                    com.noahwallet.recurring.RecurringStore(SettingsStorage(context)).protectLegacyBeforeReplacement()
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
                reserve = {
                    val reservation = session.reserve()
                    try {
                        // The reservation excludes deletion while staging is checked and removed.
                        location.removeDiscardedDeletionData()
                        reservation
                    } catch (failure: Exception) {
                        session.release(reservation)
                        throw failure
                    }
                },
                release = session::release,
                createNew = { reservation, mnemonic -> session.createNew(reservation, mnemonic, true, BuildConfig.VERSION_NAME) },
                beginOnboarding = settings::beginOnboarding,
            ))
        }
    }
}
