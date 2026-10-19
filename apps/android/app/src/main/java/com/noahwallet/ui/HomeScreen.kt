package com.noahwallet.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.noahwallet.BuildConfig
import com.noahwallet.formatting.BitcoinAmount
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(model: AppModel) {
    var email by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(model.server.state.isEmailPromptDismissed == true) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { model.refresh() }
    if (email) {
        BackHandler { if (!model.busy) email = false }
        EmergencyEmailScreen(model) { email = false }
        return
    }
    PullToRefreshBox(refreshing, onRefresh = {
        scope.launch { refreshing = true; try { model.refresh(true) } finally { refreshing = false } }
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Text("Noah", style = MaterialTheme.typography.headlineMedium)
            if (BuildConfig.APP_VARIANT != "mainnet") SuggestionChip(onClick = {}, label = { Text(BuildConfig.APP_VARIANT.uppercase()) })
            Text(if (model.hidden) "••••••" else model.summary?.let {
                BitcoinAmount.format(it.balance.totalSats.toLong(), model.amountUnit)
            } ?: "Loading…", style = MaterialTheme.typography.displaySmall)
            TextButton(model::toggleBalance) { Text(if (model.hidden) "Show balance" else "Hide balance") }
            if (!dismissed && model.server.state.isEmailVerified != true) Card {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Add Emergency Email", style = MaterialTheme.typography.titleLarge)
                    Text("Optional alerts can help us reach you if your VTXOs need attention before they expire.")
                    TextButton({ email = true }) { Text("Add email") }
                    TextButton({ model.server.dismissEmailPrompt(); dismissed = true }) { Text("Dismiss emergency email prompt") }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Recent Activity", style = MaterialTheme.typography.titleLarge)
                    val count = model.summary?.movementCount ?: 0
                    Text(if (count > 0) "$count payments" else "No activity yet")
                    if (count == 0) Text("Receive or send bitcoin to start your history.")
                }
            }
            Text("In code we trust.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
