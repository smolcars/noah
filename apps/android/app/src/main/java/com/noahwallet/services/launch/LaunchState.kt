package com.noahwallet.services.launch

import android.content.Context
import com.noahwallet.BuildConfig
import com.noahwallet.services.storage.SecretResult
import com.noahwallet.services.storage.SeedStorage
import com.noahwallet.services.storage.WalletDataLocation

enum class LaunchState {
    existingWallet, onboarding, seedWithoutWalletData, walletDataWithoutSeed, secureStorageUnavailable, restoreIncomplete;

    enum class SeedStatus {
        present, absent, unavailable;

        companion object {
            fun from(result: SecretResult): SeedStatus = when (result) {
                is SecretResult.Found -> if (com.noahwallet.services.storage.StoredMnemonic.normalize(result.value) == null) unavailable else present
                SecretResult.NotFound -> absent
                is SecretResult.Unavailable -> unavailable
            }
        }
    }

    companion object {
        fun resolve(seed: SeedStatus, walletDatabaseExists: Boolean, restorePending: Boolean = false,
            deletionPending: Boolean = false): LaunchState = if (restorePending || deletionPending) restoreIncomplete else when (seed) {
            SeedStatus.unavailable -> secureStorageUnavailable
            SeedStatus.present -> if (walletDatabaseExists) existingWallet else seedWithoutWalletData
            SeedStatus.absent -> if (walletDatabaseExists) walletDataWithoutSeed else onboarding
        }

        suspend fun current(context: Context, variant: String = BuildConfig.APP_VARIANT): LaunchState {
            return resolve(
                SeedStatus.from(SeedStorage(context, variant).read()),
                WalletDataLocation(context.filesDir, variant).databaseExists,
                WalletDataLocation(context.filesDir, variant).restorePending,
                WalletDataLocation(context.filesDir, variant).deletionMarker.exists(),
            )
        }
    }
}
