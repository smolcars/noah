package com.noahwallet.ui.settings

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.noahwallet.BuildConfig
import com.noahwallet.localization.tr
import com.noahwallet.send.UsdcSwaps
import com.noahwallet.ui.AppModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Beta: Send USDC. Turning it on opens Satora's client (creating its swap phrase), saves the flag, then shows the phrase;
 * turning it off only clears the flag, keeping the phrase and any swaps.
 */
@Composable
internal fun BetaPage(model: AppModel, nav: SettingsNav) {
    val context = LocalContext.current.applicationContext
    var on by remember { mutableStateOf(UsdcSwaps.enabled(model.settings)) }
    var failure by remember { mutableStateOf<String?>(null) }
    SettingsFrame(tr("Beta"), nav.back, line = tr("Early features that may change or go away.")) {
        SettingsGroup(rows = listOfNotNull(
            { d -> SettingsRow(tr("Send USDC"), SettingsIcons.cash, sub = tr("Pay USDC addresses on Arbitrum through Satora's swap service"),
                divider = d, modifier = Modifier.testTag("beta-usdc"), end = RowEnd.Toggle(on, !model.busy) { value ->
                    if (value) model.action("Couldn't turn on USDC sends.", inline = { failure = it }) {
                        withContext(Dispatchers.IO) { UsdcSwaps.open(context); model.settings.set("true", UsdcSwaps.key) }
                        on = true; nav.open(SettingsDestination.SwapPhrase)
                    } else model.action("Couldn't save your preference.", inline = { failure = it }) {
                        withContext(Dispatchers.IO) { model.settings.set("false", UsdcSwaps.key) }
                        on = false
                    }
                }) },
            if (on) ({ d -> SettingsRow(tr("Swap phrase"), SettingsIcons.key, divider = d, modifier = Modifier.testTag("beta-swap-phrase")) {
                nav.open(SettingsDestination.SwapPhrase)
            } }) else null,
        ))
        failure?.let { FinePrint(tr(it), failed = true) }
        if (BuildConfig.APP_VARIANT != "mainnet") FinePrint(tr("USDC sends work on mainnet only."))
    }
}
