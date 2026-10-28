package com.noahwallet.onchain

import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import org.junit.Assert.*
import org.junit.Test

class VtxoSelectionSemanticsTest {
    @Test fun rowExposesOneCheckboxWithIdentityAmountStatusAndSelection() {
        for (checked in listOf(false, true)) for (enabled in listOf(false, true)) {
            val configurations = mutableListOf<SemanticsConfiguration>()
            vtxoSelectionModifier("1234567890abcdef:0", "30,000 sats", "Active", checked, enabled) {}
                .foldIn(Unit) { _, element ->
                    val node = (element as? ModifierNodeElement<*>)?.create()
                    if (node is SemanticsModifierNode) {
                        val config = SemanticsConfiguration()
                        with(node) { config.applySemantics() }
                        if (config.contains(SemanticsProperties.ContentDescription)) assertTrue(node.shouldMergeDescendantSemantics)
                        configurations.add(config)
                    }
                }
            val checkbox = configurations.single { it.getOrNull(SemanticsProperties.Role) == Role.Checkbox }
            assertEquals(ToggleableState(checked), checkbox[SemanticsProperties.ToggleableState])
            assertNotNull(checkbox[SemanticsActions.OnClick].action)
            assertEquals(!enabled, checkbox.contains(SemanticsProperties.Disabled))
            val label = configurations.single { it.contains(SemanticsProperties.ContentDescription) }
            assertEquals(listOf("VTXO 1234567890ab…, 30,000 sats, Active, ${if (checked) "checked" else "not checked"}"),
                label[SemanticsProperties.ContentDescription])
        }
    }
}
