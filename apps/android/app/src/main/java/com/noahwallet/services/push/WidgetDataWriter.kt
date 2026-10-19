package com.noahwallet.services.push

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.noahwallet.services.wallet.WalletBalance
import com.noahwallet.services.wallet.WalletConfiguration
import com.noahwallet.widgets.NoahWidgetProvider

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

    fun write(context: Context, balance: WalletBalance, closestExpiryBlocks: Long?, variant: String) {
        val editor = context.getSharedPreferences("com.noahwallet.$variant", Context.MODE_PRIVATE).edit()
        values(balance, closestExpiryBlocks, variant).forEach { (key, value) -> editor.putLong(key, value) }
        check(editor.commit()) { "Widget preferences could not be saved" }
        val name = variant.replaceFirstChar { it.uppercase() }
        context.sendBroadcast(Intent(NoahWidgetProvider.ACTION_WIDGET_DATA_CHANGED).setComponent(
            ComponentName(context.packageName, "com.noahwallet.widgets.NoahWidget${name}Provider")))
    }
}
