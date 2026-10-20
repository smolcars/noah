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
import com.noahwallet.formatting.FiatCurrency
import com.noahwallet.services.server.Nostr
import com.noahwallet.services.storage.SettingsPreferences
import com.noahwallet.services.wallet.WalletConfiguration
import com.noahwallet.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.Currency
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
fun ProfileScreen(model: AppModel, navigate: (SettingsDestination) -> Unit) {
    var name by remember { mutableStateOf(SettingsPreferences.state(model.settings.string("profile-storage"))["displayName"]?.jsonPrimitive?.contentOrNull ?: "") }
    var server by remember { mutableStateOf(model.server.state) }
    var key by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            key = withContext(Dispatchers.IO) { com.noahwallet.services.server.ServerClient.loginKey(BuildConfig.APP_VARIANT, model.mnemonic().joinToString(" ")) }
            model.server.refreshProfile(); server = model.server.state
        } catch (_: Exception) { message = "Couldn't refresh your profile. Please try again." }
    }
    SettingsColumn {
        SectionTitle("Name")
        OutlinedTextField(name, { name = it }, label = { Text(tr("Name")) }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !model.busy)
        NoahButton("Save", !model.busy) {
            model.action("Couldn't save your name.") { model.server.saveDisplayName(name); name = name.trim(); message = "Saved" }
        }
        message?.let { Text(tr(it)) }
        SectionTitle("Lightning Address")
        server.lightningAddress?.let { CopyRow("Lightning Address", it) }
        SettingRow("Edit Lightning Address") { navigate(SettingsDestination.Lightning) }
        SectionTitle("Emergency Email")
        Text(tr(server.emailAddress ?: "No emergency email set"))
        Text(tr(if (server.isEmailVerified == true) "Verified" else "Not verified"))
        SettingRow("Edit Emergency Email") { navigate(SettingsDestination.Email) }
        SectionTitle("Public Keys")
        if (key.isNotEmpty()) CopyRow("Wallet public key", key)
        Nostr.npub(server.nostrPubkey).takeIf { it.isNotEmpty() }?.let { CopyRow("Nostr Public Key", it) }
    }
}

@Composable
fun PreferenceScreen(model: AppModel, kind: SettingsDestination, onSelected: () -> Unit = {}) {
    val options = when (kind) {
        SettingsDestination.Language -> listOf("en", "es", "ja", "ko", "sv")
        SettingsDestination.Currency -> FiatCurrency.supported.toList()
        else -> listOf("bip177", "sats")
    }
    val selected = when (kind) {
        SettingsDestination.Language -> model.localeIdentifier
        SettingsDestination.Currency -> model.preferredCurrency
        else -> model.amountUnit.name
    }
    SettingsColumn {
        options.forEach { code ->
            val label = when (kind) {
                SettingsDestination.Language -> Locale.forLanguageTag(code).getDisplayLanguage(Locale.forLanguageTag(code)).replaceFirstChar { it.titlecase() }
                SettingsDestination.Currency -> Currency.getInstance(code).displayName
                else -> if (code == "sats") "Satoshis" else "Bitcoin (BIP-177)"
            }
            SettingRow(label, code.uppercase() + if (selected == code) " · Selected" else "") {
                model.action("Couldn't save your preference.") {
                    when (kind) {
                        SettingsDestination.Language -> model.setLanguage(code)
                        SettingsDestination.Currency -> model.preference("preferredCurrency", code)
                        else -> model.preference("bitcoinAmountUnit", code)
                    }
                    onSelected()
                }
            }
        }
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
        CopyRow("Mempool explorer", "https://mempool.space" + if (BuildConfig.APP_VARIANT == "mainnet") "" else "/signet")
        CopyRow("Price API", "https://mempool.space/api/v1/prices")
        CopyRow("Historical price API", "https://mempool.space/api/v1/historical-price")
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
