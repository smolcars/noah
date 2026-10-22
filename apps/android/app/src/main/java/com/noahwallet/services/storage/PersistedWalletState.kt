package com.noahwallet.services.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PersistedWalletState(
    val isInitialized: Boolean? = null,
    val isWalletLoaded: Boolean? = null,
    val staticVtxoPubkey: String? = null,
    val isBiometricsEnabled: Boolean? = null,
    val isDebugModeEnabled: Boolean? = null,
    val isWalletSuspended: Boolean? = null,
    val hasSeenBatteryOptimizationPrompt: Boolean? = null,
) {
    companion object {
        const val key = "wallet-storage"
        const val version = 0
        /** Seed and database open Home unless a readable store explicitly says onboarding is unfinished. */
        fun onboarded(databaseExists: Boolean, wallet: () -> PersistedWalletState?) = databaseExists && wallet()?.isInitialized != false
    }
}

@Serializable
data class PersistedEnvelope<State>(val state: State, val version: Int? = null)

object PersistedStateCodec {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    inline fun <reified State> decode(value: String): State? = try {
        json.decodeFromString<PersistedEnvelope<State>>(value).state
    } catch (_: Exception) { null }

    inline fun <reified State> encode(state: State, version: Int): String =
        json.encodeToString(PersistedEnvelope(state, version))
}
