package com.noahwallet.widgets

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class WidgetSnapshotTest {
    private fun preferences(read: (String, Array<out Any?>?) -> Any?): SharedPreferences =
        Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) {
            _, method, args -> read(method.name, args)
        } as SharedPreferences

    @Test fun concurrentCommitCannotMixBalanceBucketsAndExpiryPolicy() {
        val before = mapOf("totalBalance" to 30L, "onchainBalance" to 10L, "offchainBalance" to 20L,
            "pendingBalance" to 5L, "closestExpiryBlocks" to 100L, "expiryThreshold" to 144L)
        val after = mapOf("totalBalance" to 300L, "onchainBalance" to 100L, "offchainBalance" to 200L,
            "pendingBalance" to 50L, "closestExpiryBlocks" to -999L, "expiryThreshold" to 12L)
        var current = before
        val prefs = preferences { method, args ->
            when (method) {
                "getAll" -> current.toMap().also { current = after }
                "getLong" -> (current[args!![0]] ?: args[1]).also { current = after }
                else -> error("Unexpected preference access: $method")
            }
        }
        assertEquals(WidgetSnapshot(30, 10, 20, 5, 100, 144), WidgetSnapshot.read(prefs))
        assertEquals(WidgetSnapshot(300, 100, 200, 50, -999, 12), WidgetSnapshot.read(prefs))
    }

    @Test fun concurrentClearKeepsTheCompleteOldSnapshotUntilNextRead() {
        var current = mapOf("totalBalance" to 30L, "onchainBalance" to 10L, "offchainBalance" to 20L,
            "pendingBalance" to 5L, "closestExpiryBlocks" to -999L, "expiryThreshold" to 24L)
        val prefs = preferences { method, args ->
            when (method) {
                "getAll" -> current.toMap().also { current = emptyMap() }
                "getLong" -> (current[args!![0]] ?: args[1]).also { current = emptyMap() }
                else -> error("Unexpected preference access: $method")
            }
        }
        assertEquals(WidgetSnapshot(30, 10, 20, 5, -999, 24), WidgetSnapshot.read(prefs))
        assertEquals(WidgetSnapshot(0, 0, 0, 0, 999999, 288), WidgetSnapshot.read(prefs))
    }
}
