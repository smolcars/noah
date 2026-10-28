package com.noahwallet.ui.activity

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.noahwallet.R
import com.noahwallet.history.HistoryFilter
import com.noahwallet.localization.tr
import com.noahwallet.localization.trPlural
import com.noahwallet.ui.design.NoahTokens
import com.noahwallet.ui.home.HomeType
import com.noahwallet.ui.home.Pill
import com.noahwallet.ui.home.tonal
import com.noahwallet.ui.onboarding.LocalPalette
import com.noahwallet.ui.onboarding.OnboardingSheet
import com.noahwallet.ui.onboarding.rememberHaptics
import kotlinx.coroutines.launch

/**
 * ⋯ → Export CSV: "Export activity", how many payments the file holds (narrowed by the filter, as today's export)
 * and the file, then the system share sheet. "Exporting…" while it works; today's export error shows inline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExportSheet(ui: ActivityUi, state: ActivityState, actions: ActivityActions, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val rows = remember(ui.records, state.filter) { ActivityRules.exported(ui.records, state.filter) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(Unit) { haptics.light() }
    OnboardingSheet({ if (!working) onDismiss() }, sheet) {
        Column(Modifier.testTag("activity-export-sheet")) {
            Text(tr("Export activity"), Modifier.semantics { heading() }, style = NoahTokens.Typography.sheetTitle, color = palette.foreground)
            val count = rows.size
            val sentence = when (state.filter) {
                HistoryFilter.All -> trPlural("{n} payments as a CSV file.", count)
                HistoryFilter.Lightning -> trPlural("{n} Lightning payments as a CSV file.", count)
                HistoryFilter.Ark -> trPlural("{n} Ark payments as a CSV file.", count)
                HistoryFilter.Onchain -> trPlural("{n} on-chain payments as a CSV file.", count)
            }
            Text(sentence, Modifier.padding(top = 6.dp).testTag("activity-export-count"), style = HomeType.line, color = palette.muted)
            Row(Modifier.padding(top = 20.dp).fillMaxWidth().tonal(RoundedCornerShape(20.dp),
                if (palette === NoahTokens.Dark) androidx.compose.ui.graphics.Color(0xFF2A2A2C) else palette.soft)
                .padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(painterResource(R.drawable.ic_activity_doc), null, Modifier.size(26.dp), tint = palette.foreground)
                Column {
                    Text("noah_transactions.csv", style = ActivityType.fileName, color = palette.foreground)
                    Text(tr("Dates, amounts, fees, prices, IDs and destinations"), style = HomeType.small, color = palette.muted)
                }
            }
            error?.let { Text(tr(it), Modifier.padding(top = 12.dp).testTag("activity-export-error"), style = HomeType.line, color = palette.failure) }
            Row(Modifier.padding(top = 22.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill(tr("Cancel"), Modifier.weight(1f), height = 52.dp, outlined = true, style = ActivityType.pill, enabled = !working) { haptics.light()
                    onDismiss() }
                Pill(tr(if (working) "Exporting…" else "Export"), Modifier.weight(1f).testTag("activity-export-confirm"), height = 52.dp,
                    style = ActivityType.pill,
                    enabled = rows.isNotEmpty() && !working) {
                    working = true; error = null
                    scope.launch {
                        val failure = try { actions.export(rows) } finally { working = false }
                        if (failure == null) { haptics.success(); sheet.hide(); onDismiss() } else { haptics.error(); error = failure }
                    }
                }
            }
        }
    }
}
