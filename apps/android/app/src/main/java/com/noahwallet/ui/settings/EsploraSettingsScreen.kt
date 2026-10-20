package com.noahwallet.ui.settings

import com.noahwallet.localization.tr
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import com.noahwallet.BuildConfig
import com.noahwallet.services.wallet.EsploraSettings
import com.noahwallet.services.wallet.WalletConfiguration
import com.noahwallet.ui.AppModel
import com.noahwallet.ui.NoahButton
import kotlinx.coroutines.CancellationException

@Composable
fun EsploraSettingsScreen(model: AppModel) {
    val store = remember { EsploraSettings(model.settings) }
    var selection by remember { mutableStateOf(store.selection(BuildConfig.APP_VARIANT)) }
    val defaultEndpoint = WalletConfiguration.base(BuildConfig.APP_VARIANT).esplora.orEmpty()
    val effectiveEndpoint = WalletConfiguration.effective(BuildConfig.APP_VARIANT, selection.endpoint).esplora.orEmpty()
    var input by remember { mutableStateOf(selection.input ?: effectiveEndpoint) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val sessionRevision by model.session.walletChanges.collectAsState()
    LaunchedEffect(sessionRevision) {
        if (model.session.isOpen && error == "The server isn't responding; switching back…") {
            selection = store.selection(BuildConfig.APP_VARIANT)
            error = "Endpoint not changed. The wallet is using the previous endpoint."
        }
    }
    val editable = !model.busy && BuildConfig.APP_VARIANT != "regtest"
    fun apply(value: String?) {
        message = null; error = null
        model.action("Endpoint not changed") {
            try {
                model.services.switchEsploraEndpoint(value)
                selection = store.selection(BuildConfig.APP_VARIANT)
                input = WalletConfiguration.effective(BuildConfig.APP_VARIANT, selection.endpoint).esplora.orEmpty()
                message = if (selection.endpoint == null) "The wallet is now using Noah's default Esplora endpoint." else "The wallet is now using the new Esplora endpoint."
                model.services.startServerServices()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: com.noahwallet.services.wallet.EsploraOpenWait.Timeout) { error = "The server isn't responding; switching back…" }
            catch (failure: Exception) { error = failure.message ?: "Endpoint not changed" }
            finally {
                selection = store.selection(BuildConfig.APP_VARIANT)
                model.services.reconcileBackupTriggers()
            }
        }
    }
    SettingsColumn {
        Text("Used for Bitcoin network data throughout the wallet.")
        if (BuildConfig.APP_VARIANT == "regtest") Text("Regtest wallets use Bitcoin Core instead of Esplora.")
        SectionTitle("Active endpoint")
        Text(effectiveEndpoint)
        Text(if (selection.endpoint == null) "Noah default" else "Custom endpoint")
        OutlinedTextField(input, { input = it; error = null; message = null },
            label = { Text("Esplora API base URL") }, placeholder = { Text("https://mempool.space/api") },
            singleLine = true, enabled = editable, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().testTag("esplora-endpoint-input"))
        Text("Noah will test /block-height/0 and verify the Bitcoin network before changing the wallet configuration. You may also paste the full test or tip URL.")
        selection.attention?.let { Text(tr(it), color = MaterialTheme.colorScheme.error) }
        if (model.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Testing Endpoint...") }
        message?.let { Text("Endpoint updated"); Text(it) }
        error?.let { Text("Endpoint not changed"); Text(tr(it), color = MaterialTheme.colorScheme.error) }
        NoahButton("Test & Save Endpoint", editable && input.isNotBlank()) { apply(input) }
        NoahButton("Reset to Noah Default", editable && (selection.endpoint != null || selection.input != null), secondary = true) { apply(null) }
        SectionTitle("Default endpoint")
        Text(defaultEndpoint)
        Text("A custom Esplora server can observe the Bitcoin addresses and transactions requested by this wallet. Only use a server you trust.")
    }
}
