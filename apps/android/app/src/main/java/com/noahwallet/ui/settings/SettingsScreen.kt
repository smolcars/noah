package com.noahwallet.ui.settings

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.navigation3.runtime.NavKey
import com.noahwallet.BuildConfig
import com.noahwallet.services.storage.*
import com.noahwallet.api.generated.models.AuthorizeMailboxPayload
import com.noahwallet.ui.*
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
enum class SettingsDestination(val title: String) : NavKey {
    Settings("Settings"), Profile("Profile"), Language("Language"), Currency("Currency"), Unit("Bitcoin Unit"),
    Backup("Backup & Restore"), Seed("Your Recovery Phrase"), Ark("Ark Info"), Esplora("Edit Esplora API"), Export("Export Database"),
    Logs("Logs"), Feedback("Send Feedback"), Story("Noah's Ark Story"), Debug("Debug"),
    Recurring("Recurring payments"), Vtxos("VTXOs"), Board("Board to Ark"), Exits("Emergency Exit"),
    Email("Emergency Email"), Lightning("Lightning Address"), Battery("Battery Optimization"), UnifiedPush("UnifiedPush"),
}

@Composable
fun SettingsPage(model: AppModel, destination: SettingsDestination, navigate: (SettingsDestination) -> Unit) {
    when (destination) {
        SettingsDestination.Settings -> SettingsScreen(model, navigate)
        SettingsDestination.Profile -> ProfileScreen(model, navigate)
        SettingsDestination.Language, SettingsDestination.Currency, SettingsDestination.Unit -> PreferenceScreen(model, destination)
        SettingsDestination.Backup -> BackupSettingsScreen(model)
        SettingsDestination.Seed -> MnemonicScreen(model, fromSettings = true)
        SettingsDestination.Ark -> ArkInfoScreen(model, navigate)
        SettingsDestination.Esplora -> EsploraSettingsScreen(model)
        SettingsDestination.Export -> ExportScreen(model)
        SettingsDestination.Logs -> LogScreen(model)
        SettingsDestination.Feedback -> FeedbackScreen(model)
        SettingsDestination.Story -> StoryScreen()
        SettingsDestination.Debug -> if (model.settings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isDebugModeEnabled == true) DebugScreen(model)
        SettingsDestination.Email -> EmergencyEmailScreen(model) { navigate(SettingsDestination.Profile) }
        SettingsDestination.Lightning -> LightningScreen(model) { navigate(SettingsDestination.Profile) }
        SettingsDestination.Battery -> BatteryScreen()
        SettingsDestination.UnifiedPush -> com.noahwallet.ui.launch.UnifiedPushScreen(fromOnboarding = false) { navigate(SettingsDestination.Settings) }
        SettingsDestination.Recurring -> com.noahwallet.recurring.RecurringPaymentsScreen(model)
        SettingsDestination.Vtxos -> com.noahwallet.onchain.VtxosScreen(model)
        SettingsDestination.Board -> com.noahwallet.onchain.BoardArkScreen(model)
        SettingsDestination.Exits -> com.noahwallet.onchain.UnilateralExitScreen(model)
    }
}

@Composable
fun SettingsColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
fun SettingRow(title: String, detail: String = "", enabled: Boolean = true, onClick: (() -> Unit)? = null) {
    ListItem(headlineContent = { Text(title) }, supportingContent = if (detail.isEmpty()) null else ({ Text(detail) }),
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background,
            headlineColor = if (enabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant))
}

@Composable
fun SectionTitle(title: String) {
    Text(title, Modifier.padding(top = 20.dp, bottom = 4.dp), style = MaterialTheme.typography.titleLarge)
}

@Composable
fun SettingToggle(title: String, detail: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, change, enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
private fun SettingsScreen(model: AppModel, navigate: (SettingsDestination) -> Unit) {
    var wallet by remember { mutableStateOf(model.settings.persistedState<PersistedWalletState>(PersistedWalletState.key) ?: PersistedWalletState()) }
    var autoBoard by remember { mutableStateOf(SettingsPreferences.transaction(model.settings.string("transaction-storage"))["isAutoBoardingEnabled"]?.jsonPrimitive?.booleanOrNull != false) }
    var server by remember { mutableStateOf(model.server.state) }
    var taps by remember { mutableIntStateOf(0) }
    var lastTap by remember { mutableLongStateOf(0L) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var deleteText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var threshold by remember { mutableStateOf<ULong?>(null) }
    val activity = LocalContext.current as FragmentActivity
    val uri = LocalUriHandler.current
    LaunchedEffect(model.isWalletSuspended) { if (com.noahwallet.onchain.onchainReady(model)) threshold = runCatching { model.session.boardingMinimum()?.toULong() }.getOrNull() }
    LaunchedEffect(taps) { if (taps > 0) { delay(2000); taps = 0 } }
    SettingsColumn {
        SettingRow("Noah's Ark Story", "Listen to the story behind Noah") { navigate(SettingsDestination.Story) }
        SectionTitle("Account")
        SettingRow("Profile", "Name, Lightning address, emergency email, and public key") { navigate(SettingsDestination.Profile) }
        SettingRow("Language", model.localeIdentifier.uppercase()) { navigate(SettingsDestination.Language) }
        SettingRow("Currency", model.preferredCurrency) { navigate(SettingsDestination.Currency) }
        SettingRow("Bitcoin Unit", if (model.amountUnit.name == "sats") "Satoshis" else "Bitcoin (BIP-177)") { navigate(SettingsDestination.Unit) }
        SectionTitle("Info")
        SettingRow("Ark Info", "Ark server, wallet, and explorer configuration") { navigate(SettingsDestination.Ark) }
        SectionTitle("Wallet")
        SettingRow("Show Seed Phrase", "Keep your recovery phrase safe. Never share it.") { navigate(SettingsDestination.Seed) }
        SettingRow("Show VTXOs", "Inspect and refresh your Ark funds") { navigate(SettingsDestination.Vtxos) }
        SettingRow("Board to Ark", "Move onchain bitcoin into Ark") { navigate(SettingsDestination.Board) }
        SettingRow("Emergency Exit", "Recover Ark funds onchain") { navigate(SettingsDestination.Exits) }
        SettingRow("Recurring Payments", "Automatic payments from your Ark balance") { navigate(SettingsDestination.Recurring) }
        SettingRow("Backup & Restore", "Automatic and manual encrypted backups") { navigate(SettingsDestination.Backup) }
        SettingToggle("Auto-Board to Ark", threshold?.let { "Ask to board when onchain funds cover ${maxOf(it, 20_000u)} sats, fees, and a 5,000 sats reserve." } ?: "Auto-board threshold unavailable",
            autoBoard, !model.busy) { value ->
            model.action("Couldn't save auto-board preference.") {
                model.settings.set(SettingsPreferences.autoBoard(model.settings.string("transaction-storage"), value), "transaction-storage")
                autoBoard = value
            }
        }
        if (WalletLock.available(activity) || model.walletLock.enabled) SettingToggle("Biometric Authentication", "Require authentication to unlock your wallet", model.walletLock.enabled, !model.walletLock.authenticating) {
            model.action("Couldn't save authentication preference.") { model.walletLock.setEnabled(activity, it) }
        }
        model.walletLock.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        SettingToggle("Mailbox Notifications", "Allow Noah to wake the app to claim incoming payments", server.isMailboxAuthorizationEnabled != false, !model.busy && !model.isWalletSuspended) {
            model.action("Couldn't update mailbox notifications.") {
                model.server.setMailboxEnabled(it)
                server = model.server.state
            }
        }
        if (com.noahwallet.ui.launch.PlatformAccess.unifiedPush(LocalContext.current)) SettingRow("UnifiedPush", "Configure push notifications") { navigate(SettingsDestination.UnifiedPush) }
        SettingRow("Battery Optimization", "Allow background wallet notifications") { navigate(SettingsDestination.Battery) }
        SectionTitle("Debug")
        SettingRow("Show Logs", "Application logs") { navigate(SettingsDestination.Logs) }
        SettingRow("Reset Server Registration", "Register this wallet and push notifications again", !model.busy && !model.isWalletSuspended) { confirmation = "reset" }
        SettingRow("Send Feedback", "Report bugs or share feedback") { navigate(SettingsDestination.Feedback) }
        if (wallet.isDebugModeEnabled == true) SettingRow("Debug Screen", "Advanced developer actions") { navigate(SettingsDestination.Debug) }
        SectionTitle("Danger Zone")
        SettingToggle("Suspend Wallet", "Close the wallet and disable all wallet operations until re-enabled", model.isWalletSuspended, !model.busy) { suspend ->
            model.action("Couldn't change wallet suspension. Please try again.") { if (suspend) model.suspendWallet() else model.resumeWallet() }
        }
        SettingRow("Export Database", "Create an encrypted .noahbackup file", !model.isWalletSuspended) { navigate(SettingsDestination.Export) }
        TextButton({ deleteText = ""; confirmation = "delete" }, enabled = !model.busy) { Text("Delete Wallet", color = MaterialTheme.colorScheme.error) }
        HorizontalDivider()
        TextButton({
            if (wallet.isDebugModeEnabled != true) {
                val now = SystemClock.elapsedRealtime()
                taps = if (now - lastTap < 2000) taps + 1 else 1
                lastTap = now
                if (taps >= 5) {
                    wallet = (model.settings.persistedState<PersistedWalletState>(PersistedWalletState.key) ?: PersistedWalletState()).copy(isDebugModeEnabled = true)
                    model.settings.setPersistedState(wallet, PersistedWalletState.key, 0); taps = 0
                }
            }
        }) { Text("v${BuildConfig.VERSION_NAME}" + if (taps > 0) " (${5 - taps} taps to unlock debug)" else "") }
        Text("Made with love from Noah team", style = MaterialTheme.typography.bodySmall)
        TextButton({ uri.openUri("https://t.me/blixtwallet/605026") }) { Text("Telegram support") }
        TextButton({ uri.openUri("https://github.com/smolcars/noah") }) { Text("Noah on GitHub") }
        message?.let { Text(it) }
        if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    confirmation?.let { action ->
        AlertDialog(onDismissRequest = { if (!model.busy) confirmation = null },
            title = { Text(if (action == "delete") "Delete Wallet" else "Reset Server Registration") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (action == "delete") "This removes your wallet and recovery phrase from this device. Make sure you have saved your recovery phrase. Type delete to confirm."
                    else "Clear local server registration and register again? Your wallet will be kept.")
                if (action == "delete") OutlinedTextField(deleteText, { deleteText = it }, label = { Text("delete") }, enabled = !model.busy, singleLine = true)
            } },
            dismissButton = { TextButton({ confirmation = null }, enabled = !model.busy) { Text("Cancel") } },
            confirmButton = { TextButton({
                model.action(if (action == "delete") "Couldn't delete wallet. Restart Noah if a restore is pending, then try again." else "Couldn't register again. Please retry.") {
                    if (action == "delete") model.deleteWallet() else { model.reRegisterWithServer(); message = "Registered with the server again"; server = model.server.state }
                    confirmation = null
                }
            }, enabled = !model.busy && (action != "delete" || deleteText.trim().equals("delete", true))) {
                Text(if (model.busy) "Please wait…" else if (action == "delete") "Delete Wallet" else "Reset", color = MaterialTheme.colorScheme.error)
            } })
    }
}
