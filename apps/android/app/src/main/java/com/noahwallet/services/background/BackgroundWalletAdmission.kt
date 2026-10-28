package com.noahwallet.services.background

import com.noahwallet.services.launch.LaunchState
import com.noahwallet.services.storage.PersistedWalletState

internal object BackgroundWalletAdmission {
    @Suppress("LongParameterList") // one input per admission gate, each varied by its test
    fun allowed(launch: LaunchState, wallet: PersistedWalletState?, onboarded: Boolean,
        restorePending: Boolean, deletionPending: Boolean, storageFailed: Boolean): Boolean =
        launch == LaunchState.existingWallet && onboarded && wallet?.isInitialized != false &&
            wallet?.isWalletSuspended != true && !restorePending && !deletionPending && !storageFailed
}
