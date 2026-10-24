package com.noahwallet.ui.settings

import com.noahwallet.localization.tr
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.noahwallet.BuildConfig
import com.noahwallet.services.wallet.WalletConfiguration
import com.noahwallet.ui.*
import java.util.Locale

@Composable
fun CopyRow(title: String, value: String) {
    val context = LocalContext.current
    var copied by remember(value) { mutableStateOf(false) }
    SettingRow(title, if (copied) "Copied" else value) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(title, value))
        copied = true
    }
}

@Composable
fun ArkInfoScreen(model: AppModel, navigate: (SettingsDestination) -> Unit) {
    var info by remember { mutableStateOf<io.github.niteshbalusu11.bark.ArkInfo?>(null) }
    var loading by remember { mutableStateOf(false) }
    suspend fun load() { loading = true; try { info = model.session.arkInfo() } finally { loading = false } }
    LaunchedEffect(Unit) { runCatching { load() } }
    val selection = com.noahwallet.services.wallet.EsploraSettings(model.settings).selection(BuildConfig.APP_VARIANT)
    val config = WalletConfiguration.effective(BuildConfig.APP_VARIANT, selection.endpoint)
    SettingsColumn {
        NoahButton("Refresh", !loading && !model.isWalletSuspended, secondary = true) { model.action("Couldn't load Ark info.") { load() } }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (info == null) Text(tr("Ark server info is unavailable. Try refreshing."))
        info?.let {
            SettingRow("Network", it.network.name.lowercase(Locale.ROOT))
            SectionTitle("Keys")
            CopyRow("Server pubkey", it.serverPubkey); CopyRow("Mailbox pubkey", it.mailboxPubkey)
            SectionTitle("Boarding limits")
            SettingRow("Minimum board amount", "${it.minBoardAmountSats} sats")
            SettingRow("Maximum VTXO amount", it.maxVtxoAmountSats?.let { "$it sats" } ?: "Unlimited")
            SettingRow("Required board confirmations", "${it.requiredBoardConfirmations} blocks")
            SectionTitle("Rounds & Expiry")
            SettingRow("Round interval", "${it.roundIntervalSecs} seconds")
            SettingRow("Round nonces", it.nbRoundNonces.toString())
            SettingRow("VTXO exit delta", "${it.vtxoExitDelta} blocks")
            SettingRow("VTXO lifetime", "${it.vtxoLifetime} blocks")
            SettingRow("HTLC send expiry delta", "${it.htlcSendExpiryDelta} blocks")
            SectionTitle("Lightning")
            SettingRow("Receive anti-DoS", if (it.lnReceiveAntiDosRequired) "Yes" else "No")
        }
        SectionTitle("Wallet endpoints")
        CopyRow("Ark server", config.arkServer)
        config.esplora?.let { CopyRow(if (selection.endpoint == null) "Esplora API · Noah default" else "Esplora API · Custom endpoint", it) }
        config.bitcoind?.let { CopyRow("Bitcoind RPC", it) }
        SettingRow("Esplora Settings") { navigate(SettingsDestination.Esplora) }
        SectionTitle("Explorer APIs")
        CopyRow("Block height API", ArkInfoEndpoints.blockHeight(BuildConfig.APP_VARIANT, selection.endpoint))
        val prices = com.noahwallet.services.server.ServerEndpoint.priceURLs(
            com.noahwallet.services.server.ServerEndpoint.baseURL(BuildConfig.APP_VARIANT, BuildConfig.REGTEST_SERVER_URL))
        CopyRow("Mempool explorer", "https://mempool.space" + if (BuildConfig.APP_VARIANT == "mainnet") "" else "/signet")
        CopyRow("Price API", prices.first)
        CopyRow("Historical price API", prices.second)
    }
}

@Composable
fun BatteryScreen() {
    val context = LocalContext.current
    var exempt by remember { mutableStateOf((context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) exempt = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    SettingsColumn {
        Text(tr("Allow Noah to receive payment notifications and maintain your wallet while it is in the background."))
        Text(tr(if (exempt) "Battery optimization is disabled for Noah." else "Battery optimization may delay background payments."))
        NoahButton("Open battery settings", secondary = true) { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}
