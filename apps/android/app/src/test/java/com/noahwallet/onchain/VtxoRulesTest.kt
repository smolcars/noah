package com.noahwallet.onchain

import org.junit.Assert.*
import org.junit.Test

/** settings.md, VTXOs: grouping, the ⋯ filter and renew selection (locked excluded). */
class VtxoRulesTest {
    private fun vtxo(id: String, expiry: Long, state: String = "Spendable", expiring: Boolean = false, amount: Long = 1_000) =
        VtxoModel(id, amount, expiry, state, "round", 0, 0, true, "", 144, "anchor:0", "point:0", expiring, 100)

    private val soon = vtxo("soon", 150, expiring = true)
    private val expired = vtxo("expired", 90, expiring = true)
    private val later = vtxo("later", 3_000)
    private val earlier = vtxo("earlier", 2_000)
    private val locked = vtxo("locked", 140, state = "Locked", expiring = true)
    private val all = listOf(later, locked, soon, earlier, expired)

    @Test fun groupsInOrderSoonestFirst() {
        assertEquals(VtxoRules.Group.expiring, VtxoRules.group(soon))
        assertEquals(VtxoRules.Group.expiring, VtxoRules.group(expired))
        assertEquals(VtxoRules.Group.locked, VtxoRules.group(locked))
        assertEquals(VtxoRules.Group.spendable, VtxoRules.group(later))
        assertEquals(listOf(VtxoRules.Group.expiring to listOf(expired, soon), VtxoRules.Group.spendable to listOf(earlier, later),
            VtxoRules.Group.locked to listOf(locked)), VtxoRules.groups(all))
        assertEquals(emptyList<Any>(), VtxoRules.groups(emptyList()))
    }

    @Test fun filterKeepsWhatItNames() {
        assertEquals(all, VtxoRules.visible(all, VtxoRules.Filter.all))
        assertEquals(listOf(locked), VtxoRules.visible(all, VtxoRules.Filter.locked))
        assertEquals(listOf(soon, expired), VtxoRules.visible(all, VtxoRules.Filter.expiring))
        assertEquals(listOf(later, soon, earlier, expired), VtxoRules.visible(all, VtxoRules.Filter.spendable))
    }

    @Test fun selectionPreselectsExpiringAndNeverPicksLocked() {
        assertEquals(setOf("soon", "expired"), VtxoRules.preselected(all))
        assertFalse(VtxoRules.selectable(locked))
        assertEquals(setOf("soon"), VtxoRules.toggle(setOf("soon"), locked))
        assertEquals(setOf("soon", "later"), VtxoRules.toggle(setOf("soon"), later))
        assertEquals(emptySet<String>(), VtxoRules.toggle(setOf("soon"), soon))
    }

    @Test fun renewButtonOnlyForVtxosDueWithinTheThreshold() {
        assertEquals(listOf(soon), VtxoRules.due(all))
        assertEquals(emptyList<VtxoModel>(), VtxoRules.due(listOf(later, locked, expired)))
    }

    @Test fun timeLeftReadsLikeThePrototype() {
        assertEquals(VtxoRules.Left.UnderAnHour, VtxoRules.left(5))
        assertEquals(VtxoRules.Left.Hours(1), VtxoRules.left(6))
        assertEquals(VtxoRules.Left.Hours(24), VtxoRules.left(143))
        assertEquals(VtxoRules.Left.Days(1), VtxoRules.left(144))
        assertEquals(VtxoRules.Left.Days(29), VtxoRules.left(4_176))
    }
}
