package com.noahwallet.services.push

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.noahwallet.services.wallet.WalletBalance
import com.noahwallet.services.wallet.WalletConfiguration
import com.noahwallet.widgets.NoahWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

object WidgetDataWriter {
    /** The reused providers read Long values, directly from the per-variant preferences. */
    fun values(balance: WalletBalance, closestExpiryBlocks: Long?, variant: String): Map<String, Long> = mapOf(
        "totalBalance" to balance.totalSats.toLong(),
        "onchainBalance" to balance.onchainSats.toLong(),
        "offchainBalance" to balance.offchainSats.toLong(),
        "pendingBalance" to balance.pendingSats.toLong(),
        "closestExpiryBlocks" to (closestExpiryBlocks ?: -999L),
        "expiryThreshold" to WalletConfiguration.base(variant).vtxoRefreshExpiryThreshold.toLong(),
    )

    suspend fun clear(context: Context, variant: String): Unit = withContext(NonCancellable + Dispatchers.IO) {
        check(context.getSharedPreferences("com.noahwallet.$variant", Context.MODE_PRIVATE).edit().clear().commit())
        broadcast(context, variant)
    }

    private fun broadcast(context: Context, variant: String) {
        val name = variant.replaceFirstChar { it.uppercase() }
        context.sendBroadcast(Intent(NoahWidgetProvider.ACTION_WIDGET_DATA_CHANGED).setComponent(
            ComponentName(context.packageName, "com.noahwallet.widgets.NoahWidget${name}Provider")))
    }

    suspend fun write(context: Context, balance: WalletBalance, closestExpiryBlocks: Long?, variant: String, preserveExpiry: Boolean = false, checkStage: () -> Unit = {}): Unit = withContext(NonCancellable + Dispatchers.IO) {
        val editor = context.getSharedPreferences("com.noahwallet.$variant", Context.MODE_PRIVATE).edit()
        values(balance, closestExpiryBlocks, variant).forEach { (key, value) ->
            if (!preserveExpiry || key != "closestExpiryBlocks") editor.putLong(key, value)
        }
        checkStage()
        check(editor.commit()) { "Widget preferences could not be saved" }
        broadcast(context, variant)
    }
}
