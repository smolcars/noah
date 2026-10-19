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
                is SecretResult.Found -> if (result.value.isEmpty()) absent else present
                SecretResult.NotFound -> absent
                is SecretResult.Unavailable -> unavailable
            }
        }
    }

    companion object {
        fun resolve(seed: SeedStatus, walletDatabaseExists: Boolean): LaunchState = when (seed) {
            SeedStatus.unavailable -> secureStorageUnavailable
            SeedStatus.present -> if (walletDatabaseExists) existingWallet else seedWithoutWalletData
            SeedStatus.absent -> if (walletDatabaseExists) walletDataWithoutSeed else onboarding
        }

        suspend fun current(context: Context, variant: String = BuildConfig.APP_VARIANT): LaunchState = resolve(
            SeedStatus.from(SeedStorage(context, variant).read()),
            WalletDataLocation(context.filesDir, variant).databaseExists,
        )
    }
}
