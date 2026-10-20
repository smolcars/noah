package com.noahwallet.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.noahwallet.history.RepeatPayment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.noahwallet.BuildConfig
import com.noahwallet.formatting.BitcoinAmount
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import com.noahwallet.services.server.value
import androidx.lifecycle.repeatOnLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(model: AppModel, onViewAll: () -> Unit, onRepeatPayment: (RepeatPayment) -> Unit, onQR: () -> Unit, settings: () -> Unit) {
    var balanceExpanded by rememberSaveable { mutableStateOf(false) }
    var selectedID by rememberSaveable { mutableStateOf<String?>(null) }
    var map by rememberSaveable { mutableStateOf(false) }
    var fact by remember { mutableStateOf(com.noahwallet.ui.home.bitcoinFacts.random()) }
    var price by remember { mutableStateOf<Double?>(null) }
    var refreshRevision by remember { mutableIntStateOf(0) }
    var email by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(model.server.state.isEmailPromptDismissed == true) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val currency = model.preferredCurrency.takeIf { it in com.noahwallet.formatting.FiatCurrency.supported } ?: "USD"
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) { model.refresh() }
    LaunchedEffect(currency, model.isWalletSuspended, lifecycle) {
        price = null
        if (model.isWalletSuspended) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                try { price = com.noahwallet.api.generated.client.V0PricesClient(model.services.client)
                .fiatPrices(kotlinx.serialization.json.JsonObject(emptyMap())).value().rates[currency]?.takeIf { it.isFinite() && it > 0 } }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { }
                kotlinx.coroutines.delay(300_000)
            }
        }
    }
    if (map) {
        com.noahwallet.btcmap.BtcMapScreen { map = false }
        return
    }
    if (email) {
        BackHandler { if (!model.busy) email = false }
        EmergencyEmailScreen(model) { email = false }
        return
    }
    HistoryDetailsHost(model, selectedID, { selectedID = null }, onRepeatPayment) {
        PullToRefreshBox(refreshing, onRefresh = {
            scope.launch { refreshing = true; try { model.refresh(true); model.history.refresh(model.preferredCurrency); fact = com.noahwallet.ui.home.bitcoinFacts.random(); refreshRevision++ } finally { refreshing = false } }
        }) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Noah", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(settings) { Text("Settings") }
                    TextButton({ map = true }) { Text("Bitcoin Map") }
                    if (!model.isWalletSuspended) TextButton(onQR) { Text("QR Code") }
                }
                com.noahwallet.ui.home.UpgradeNotices()
                // Like RN, a suspended wallet shows only the suspended card.
                if (model.isWalletSuspended) {
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Wallet Suspended", style = MaterialTheme.typography.titleLarge)
                        Text("All wallet operations are disabled. Resume your wallet in Settings.")
                        TextButton(settings) { Text("Open Settings") }
                    } }
                    return@Column
                }
                if (BuildConfig.APP_VARIANT != "mainnet") Text(BuildConfig.APP_VARIANT.uppercase(),
                    Modifier.background(if (BuildConfig.APP_VARIANT == "regtest") Color(0xFFFFCC00) else Color(0xFFFF9500), CircleShape)
                        .padding(horizontal = 10.dp, vertical = 4.dp), color = Color.Black,
                    style = MaterialTheme.typography.labelMedium)
                com.noahwallet.ui.home.HomeBanners(model, refreshRevision)
                val locale = java.util.Locale.forLanguageTag(model.localeIdentifier)
                val fiat = com.noahwallet.ui.home.HomeRules.fiat(model.summary?.balance?.totalSats?.toLong(), price, currency, locale, model.hidden)
                if (fiat != null) Text(fiat, style = MaterialTheme.typography.headlineMedium)
                else CircularProgressIndicator(Modifier.size(24.dp))
                Text(if (model.hidden) "••••" else model.summary?.let {
                    BitcoinAmount.format(it.balance.totalSats.toLong(), model.amountUnit, java.util.Locale.forLanguageTag(model.localeIdentifier))
                } ?: "Loading…", style = MaterialTheme.typography.displaySmall)
                TextButton({ balanceExpanded = !balanceExpanded }) { Text(if (balanceExpanded) "Hide balance details" else "Show balance details") }
                model.summary?.balance?.let { balance ->
                    fun display(sats: ULong) = com.noahwallet.services.wallet.WalletBalance.display(sats, model.hidden, model.amountUnit, locale)
                    if (balance.pendingSats > 0uL) Text("Pending balance: ${display(balance.pendingSats)}")
                    if (balanceExpanded) Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Balance Details", style = MaterialTheme.typography.titleMedium)
                            balance.details.forEach { row ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(row.label, Modifier.weight(1f)); Text(display(row.sats))
                                }
                            }
                        }
                    }
                }
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
                        if (model.history.transactions.isEmpty()) {
                            Text(model.history.error ?: if (model.history.isLoading) "Loading transactions…" else "No activity yet")
                        } else model.history.transactions.take(3).forEach { row ->
                            TransactionRow(row, model.amountUnit, recent = true) { selectedID = row.id }
                        }
                        TextButton(onViewAll, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text("View all") }
                    }
                }
                Text(fact, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
