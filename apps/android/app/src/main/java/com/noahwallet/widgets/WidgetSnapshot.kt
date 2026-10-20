package com.noahwallet.widgets

import android.content.SharedPreferences

internal data class WidgetSnapshot(val total: Long, val onchain: Long, val offchain: Long,
    val pending: Long, val closestExpiryBlocks: Long, val expiryThreshold: Long) {
    companion object {
        fun read(prefs: SharedPreferences): WidgetSnapshot {
            val values = prefs.all
            fun value(key: String, fallback: Long) = values[key] as? Long ?: fallback
            return WidgetSnapshot(value("totalBalance", 0L), value("onchainBalance", 0L),
                value("offchainBalance", 0L), value("pendingBalance", 0L),
                value("closestExpiryBlocks", 999999L), value("expiryThreshold", 288L))
        }
    }
}
