package com.noahwallet.services.launch

import android.content.Context
import com.noahwallet.BuildConfig
import com.noahwallet.services.storage.SecretResult
import com.noahwallet.services.storage.SecretFailure
import com.noahwallet.services.storage.StoredMnemonic
import com.noahwallet.services.storage.SeedStorage
import com.noahwallet.services.storage.WalletDataLocation

enum class LaunchState {
    existingWallet, onboarding, seedWithoutWalletData, walletDataWithoutSeed, secureStorageUnavailable, restoreIncomplete, invalidSeed, seedConflict;

    enum class SeedStatus {
        present, absent, unavailable, invalid, conflict;

        companion object {
            fun from(result: SecretResult): SeedStatus = when (result) {
                is SecretResult.Found -> if (StoredMnemonic.normalize(result.value) == null) invalid else present
                SecretResult.NotFound -> absent
                is SecretResult.Unavailable -> when (result.reason) {
                    SecretFailure.invalidMnemonic -> invalid
                    SecretFailure.seedConflict, SecretFailure.unexpectedAccount -> conflict
                    else -> unavailable
                }
            }
        }
    }

    enum class Diagnostic { none, invalidMnemonic, sourceUnavailable, sourceConflict, walletFingerprintMismatch, multipleDatabases }
    data class Inspection(val state: LaunchState, val reason: Diagnostic)

    companion object {
        fun inspect(sources: List<SecretResult>, walletDatabaseExists: Boolean,
                    fingerprintMatches: Boolean = true, multipleDatabases: Boolean = false): Inspection {
            if (multipleDatabases) return Inspection(seedConflict, Diagnostic.multipleDatabases)
            val status = SeedStatus.from(StoredMnemonic.agree(sources.map(StoredMnemonic::validate)))
            if (status == SeedStatus.present && walletDatabaseExists && !fingerprintMatches)
                return Inspection(seedConflict, Diagnostic.walletFingerprintMismatch)
            val reason = when (status) {
                SeedStatus.invalid -> Diagnostic.invalidMnemonic
                SeedStatus.conflict -> Diagnostic.sourceConflict
                SeedStatus.unavailable -> Diagnostic.sourceUnavailable
                else -> Diagnostic.none
            }
            return Inspection(resolve(status, walletDatabaseExists), reason)
        }

        fun openFailure(failure: Exception): LaunchState = when {
            failure is com.noahwallet.services.wallet.WalletSession.OpenError &&
                failure.reason == com.noahwallet.services.wallet.WalletSession.OpenFailure.seedConflict -> seedConflict
            failure is io.github.niteshbalusu11.bark.BarkError &&
                failure.message?.contains("incorrect mnemonic for existing wallet") == true -> seedConflict
            else -> secureStorageUnavailable
        }

        fun resolve(seed: SeedStatus, walletDatabaseExists: Boolean, restorePending: Boolean = false,
            deletionPending: Boolean = false): LaunchState = if (restorePending || deletionPending) restoreIncomplete else when (seed) {
            SeedStatus.invalid -> invalidSeed
            SeedStatus.conflict -> seedConflict
            SeedStatus.unavailable -> secureStorageUnavailable
            SeedStatus.present -> if (walletDatabaseExists) existingWallet else seedWithoutWalletData
            SeedStatus.absent -> if (walletDatabaseExists) walletDataWithoutSeed else onboarding
        }

        suspend fun current(context: Context, variant: String = BuildConfig.APP_VARIANT): LaunchState {
            val location = WalletDataLocation(context.filesDir, variant)
            if (location.restorePending || location.deletionMarker.exists()) return restoreIncomplete
            return inspect(listOf(SeedStorage(context, variant).read()), location.hasAnyData,
                multipleDatabases = location.hasMultipleDatabases).state
        }
    }
}
