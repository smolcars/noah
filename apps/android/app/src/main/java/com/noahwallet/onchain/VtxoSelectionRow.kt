package com.noahwallet.onchain

import com.noahwallet.localization.tr
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
internal fun VtxoSelectionRow(id: String, amount: String, status: String, checked: Boolean,
    enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(vtxoSelectionModifier(id, amount, status, checked, enabled, onCheckedChange)) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(tr("${id.take(12)}… · $status"))
            Text(tr(amount))
        }
    }
}

internal fun vtxoSelectionModifier(id: String, amount: String, status: String, checked: Boolean,
    enabled: Boolean, onCheckedChange: (Boolean) -> Unit): Modifier = Modifier.fillMaxWidth()
    .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
    .semantics(mergeDescendants = true) {
        contentDescription = "VTXO ${id.take(12)}…, $amount, $status, ${if (checked) "checked" else "not checked"}"
    }
