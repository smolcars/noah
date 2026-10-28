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
    private fun stored(values: Map<String, Any>) = preferences { method, _ ->
        if (method == "getAll") values else error("Unexpected preference access: $method")
    }
    private val legacy = mapOf("totalBalance" to 30L, "onchainBalance" to 10L, "offchainBalance" to 20L,
        "pendingBalance" to 5L, "closestExpiryBlocks" to 100L, "expiryThreshold" to 144L)

    /**
     * An RN or earlier native snapshot renders the new design: the balance in ₿, no fiat, calm water and no status
     * line. Its pending total also counts outgoing payments, so it never shows as arriving.
     */
    @Test fun oldKeysRenderTheBalanceOnly() {
        assertEquals(WidgetSnapshot(balance = 30), WidgetSnapshot.read(stored(legacy)))
        val snapshot = WidgetSnapshot.read(stored(legacy))!!
        assertFalse(snapshot.sats); assertFalse(snapshot.hidden); assertEquals(0L, snapshot.arriving)
        assertNull(snapshot.daily); assertNull(snapshot.fiatCurrency); assertNull(snapshot.renewDue)
        assertEquals(WidgetSnapshot(balance = 7), WidgetSnapshot.read(stored(mapOf("totalBalance" to 7L))))
    }

    @Test fun noSnapshotIsNoWallet() {
        assertNull(WidgetSnapshot.read(stored(emptyMap())))
        assertNull(WidgetSnapshot.read(stored(mapOf("expiryThreshold" to 144L))))
    }

    @Test fun newSnapshotRoundTripsAndWinsOverOldKeys() {
        val snapshot = WidgetSnapshot(balance = 252_742, arriving = 100_000, daily = List(31) { 200_000L + it * 1_700 }.dropLast(1) + 252_742L,
            writtenAt = 1_761_577_200_000, amountUnit = "sats", hidden = true, fiatCurrency = "EUR",
            fiatPrice = 112_000.0, renewDue = 1_761_640_000_000)
        assertEquals(snapshot, WidgetSnapshot.decode(snapshot.encode()))
        assertEquals(snapshot, WidgetSnapshot.read(stored(legacy + (WidgetSnapshot.KEY to snapshot.encode()))))
        assertTrue(snapshot.encode().contains("\"version\":1"))
    }

    @Test fun unreadableSnapshotFallsBackToOldKeysOrNoWallet() {
        assertEquals(WidgetSnapshot(balance = 30), WidgetSnapshot.read(stored(legacy + (WidgetSnapshot.KEY to "{not json"))))
        assertNull(WidgetSnapshot.read(stored(mapOf(WidgetSnapshot.KEY to "{\"version\":1}"))))
        // A wave must have its 31 days.
        assertNull(WidgetSnapshot.decode(WidgetSnapshot(balance = 1, daily = listOf(1, 1)).encode()))
        // A later version's extra fields don't hide what this one understands.
        assertEquals(WidgetSnapshot(version = 2, balance = 9), WidgetSnapshot.decode("{\"version\":2,\"balance\":9,\"future\":[1]}"))
    }

    @Test fun concurrentCommitCannotMixTwoWrites() {
        val before = WidgetSnapshot(balance = 30, arriving = 5).encode()
        val after = WidgetSnapshot(balance = 300, arriving = 50, hidden = true).encode()
        var current: Map<String, Any> = mapOf(WidgetSnapshot.KEY to before)
        val prefs = preferences { method, args ->
            when (method) {
                "getAll" -> current.toMap().also { current = mapOf(WidgetSnapshot.KEY to after) }
                "getString", "getLong" -> (current[args!![0]] ?: args[1]).also { current = mapOf(WidgetSnapshot.KEY to after) }
                else -> error("Unexpected preference access: $method")
            }
        }
        assertEquals(WidgetSnapshot(balance = 30, arriving = 5), WidgetSnapshot.read(prefs))
        assertEquals(WidgetSnapshot(balance = 300, arriving = 50, hidden = true), WidgetSnapshot.read(prefs))
    }

    @Test fun concurrentClearKeepsTheCompleteOldSnapshotUntilNextRead() {
        var current: Map<String, Any> = legacy
        val prefs = preferences { method, args ->
            when (method) {
                "getAll" -> current.toMap().also { current = emptyMap() }
                "getLong" -> (current[args!![0]] ?: args[1]).also { current = emptyMap() }
                else -> error("Unexpected preference access: $method")
            }
        }
        assertEquals(WidgetSnapshot(balance = 30), WidgetSnapshot.read(prefs))
        assertNull(WidgetSnapshot.read(prefs))
    }
}
