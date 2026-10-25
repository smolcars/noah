package com.noahwallet.ui.settings

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.navigation3.runtime.NavKey
import com.noahwallet.BuildConfig
import com.noahwallet.formatting.BitcoinAmount
import com.noahwallet.formatting.BitcoinAmountUnit
import com.noahwallet.formatting.FiatCurrency
import com.noahwallet.localization.tr
import com.noahwallet.services.storage.PersistedWalletState
import com.noahwallet.services.storage.SettingsPreferences
import com.noahwallet.ui.AppModel
import com.noahwallet.ui.activity.DetailRow
import com.noahwallet.ui.activity.ValueKind
import com.noahwallet.ui.design.NoahTokens
import com.noahwallet.ui.home.HomeIcons
import com.noahwallet.ui.home.HomeType
import com.noahwallet.ui.launch.PlatformGateRules
import com.noahwallet.ui.onboarding.LocalPalette
import com.noahwallet.ui.onboarding.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/** The name Settings and Profile show: your display name, else your address's name. */
internal fun profileName(model: AppModel): String? =
    SettingsPreferences.state(model.settings.string("profile-storage"))["displayName"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

internal fun limitedProfileName(value: String): String = value.take(80).dropLastWhile { it.isHighSurrogate() }

/** Display preferences: language, currency and bitcoin unit. */
internal enum class Preference { language, currency, unit }

internal fun preferenceName(kind: Preference, code: String, locale: Locale): String = when (kind) {
    Preference.language -> Locale.forLanguageTag(code).getDisplayLanguage(Locale.forLanguageTag(code)).replaceFirstChar { it.titlecase() }
    Preference.currency -> java.util.Currency.getInstance(code).getDisplayName(locale)
    Preference.unit -> if (code == "sats") "Satoshis" else "Bitcoin (BIP-177)"
}

/** The version line under the wordmark, as About shows it too. */
internal fun versionText() = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

/** Tapping the version five times turns debug on (Advanced can turn it off again). */
@Composable
internal fun rememberDebugTaps(model: AppModel, live: SettingsLive): () -> Unit {
    val taps = remember { SettingsRules.DebugTaps() }
    val toasts = LocalSettingsToasts.current
    val haptics = rememberHaptics()
    val on = tr("Debug is on"); val unlocked = tr("Debug on")
    val more = (1..3).associateWith { tr("{n} more to turn on debug", it.toString()) }
    return {
        if (live.debug) toasts.show(on)
        else {
            val left = taps.tap(SystemClock.elapsedRealtime())
            haptics.selection()
            if (left == 0) {
                val wallet = model.settings.persistedState<PersistedWalletState>(PersistedWalletState.key) ?: PersistedWalletState()
                model.settings.setPersistedState(wallet.copy(isDebugModeEnabled = true), PersistedWalletState.key, 0)
                live.debug = true
                haptics.success(); toasts.show(unlocked)
            } else more[left]?.let(toasts::show)
        }
    }
}

/**
 * The list (prototype `SCR.root`): you, then Security, Bitcoin, Display, App and Help, Delete wallet on its own, and the
 * wordmark with the version. Rows show a live value; an orange dot marks what needs you. [selected] is the page open
 * beside the list on wide windows.
 */
@Composable
internal fun SettingsRoot(model: AppModel, live: SettingsLive, nav: SettingsNav, selected: NavKey?, initialSheet: String? = null) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val toasts = LocalSettingsToasts.current
    // Keyed by name in the list's own holder (SettingsHost): compact and split compose the list from different places, so
    // positional keys wouldn't carry its sheet and scroll across a fold.
    var sheet by rememberSaveable(key = "settings-sheet") { mutableStateOf(initialSheet?.takeIf { it in setOf("unit", "currency", "language", "delete") }) }
    val scroll = rememberSaveable(saver = ScrollState.Saver, key = "settings-scroll") { ScrollState(0) }
    val locale = Locale.forLanguageTag(model.localeIdentifier)
    val schedules = com.noahwallet.recurring.RecurringRuntime.shared(context).executor.schedules
    LaunchedEffect(Unit) { runCatching { com.noahwallet.recurring.RecurringRuntime.shared(context).executor.reload() } }
    val lockError = model.walletLock.error?.let { tr(it) }
    LaunchedEffect(lockError) { lockError?.let { toasts.show(it) } }
    val address = model.server.state.lightningAddress?.takeIf { it.isNotEmpty() }
    val name = profileName(model) ?: address?.substringBefore('@')
    val tap = rememberDebugTaps(model, live)
    fun money(sats: Long) = if (model.hidden) BitcoinAmount.format(0, model.amountUnit, locale).replace("0", "••••") else BitcoinAmount.format(sats, model.amountUnit, locale)
    val open = nav.open
    SettingsFrame(tr("Settings"), nav.back, scroll = scroll) {
        Me(name ?: tr("Profile"), address, selected == SettingsDestination.Profile) { open(SettingsDestination.Profile) }
        val backup = SettingsRules.backup(model.server.state.isBackupEnabled == true, live.backup.lastBackupStatus)
        SettingsGroup(tr("Security"), rows = listOfNotNull(
            { d -> SettingsRow(tr("Backup"), SettingsIcons.cloud, value = when (backup) {
                SettingsRules.Backup.failed -> tr("Failed"); SettingsRules.Backup.running -> tr("Backing up…")
                SettingsRules.Backup.on -> tr("On"); SettingsRules.Backup.off -> tr("Off")
            }, dot = if (backup == SettingsRules.Backup.failed) RowDot.failure else null, failed = backup == SettingsRules.Backup.failed,
                selected = selected == SettingsDestination.Backup, divider = d, modifier = Modifier.testTag("settings-backup")) { open(SettingsDestination.Backup) } },
            { d -> SettingsRow(tr("Recovery phrase"), SettingsIcons.key, value = if (!model.recoveryPhraseBackedUp) tr("Not saved") else null,
                dot = if (!model.recoveryPhraseBackedUp) RowDot.attention else null, selected = selected == SettingsDestination.Seed, divider = d,
                modifier = Modifier.testTag("settings-recovery")) { open(SettingsDestination.Seed) } },
            if (activity != null && (WalletLock.available(activity) || model.walletLock.enabled)) ({ d ->
                SettingsRow(tr("App lock"), SettingsIcons.finger, divider = d, modifier = Modifier.testTag("settings-app-lock"),
                    end = RowEnd.Toggle(model.walletLock.enabled, !model.walletLock.authenticating && !model.busy) {
                        model.action("Couldn't save authentication preference.") { model.walletLock.setEnabled(activity, it) }
                    })
            }) else null,
        ))
        val vtxos = SettingsRules.vtxos(live.overview?.vtxos)
        val recurring = SettingsRules.recurring(schedules)
        val onchain = model.summary?.balance?.onchain?.total?.toLong()
        SettingsGroup(tr("Bitcoin"), rows = listOf(
            { d -> SettingsRow(tr("VTXOs"), SettingsIcons.coins, value = when (vtxos) {
                SettingsRules.Vtxos.Renew -> tr("Renew"); is SettingsRules.Vtxos.Count -> vtxos.count.toString(); SettingsRules.Vtxos.Unknown -> null
            }, dot = if (vtxos == SettingsRules.Vtxos.Renew) RowDot.attention else null, selected = selected == SettingsDestination.Vtxos, divider = d,
                modifier = Modifier.testTag("settings-vtxos")) { open(SettingsDestination.Vtxos) } },
            { d -> SettingsRow(tr("On-chain"), SettingsIcons.chain, value = onchain?.let(::money), numeric = true,
                selected = selected == SettingsDestination.Board, divider = d, modifier = Modifier.testTag("settings-board-ark")) { open(SettingsDestination.Board) } },
            { d -> SettingsRow(tr("Recurring payments"), SettingsIcons.repeat, value = when {
                recurring.approve > 0 -> tr("Approve"); recurring.active > 0 -> tr("{n} active", recurring.active.toString()); else -> null
            }, dot = if (recurring.approve > 0) RowDot.attention else null, selected = selected == SettingsDestination.Recurring, divider = d,
                modifier = Modifier.testTag("settings-recurring-payments")) { open(SettingsDestination.Recurring) } },
            // Decision 5 (exit.md): only while an exit runs, from its start until it's claimed or canceled.
            { d -> SettingsRow(tr("Emergency exit"), SettingsIcons.exit, value = if (com.noahwallet.onchain.ExitList.live(live.overview?.exits)) tr("In progress") else null,
                selected = selected == SettingsDestination.Exits, divider = d, modifier = Modifier.testTag("settings-emergencyExit")) { open(SettingsDestination.Exits) } },
            { d -> SettingsRow(tr("Spend bitcoin nearby"), SettingsIcons.map, divider = d, modifier = Modifier.testTag("settings-btcmap")) { nav.map() } },
        ))
        SettingsGroup(tr("Display"), rows = listOf(
            { d -> SettingsRow(tr("Bitcoin unit"), glyph = if (model.amountUnit == BitcoinAmountUnit.sats) "s" else "₿",
                value = if (model.amountUnit == BitcoinAmountUnit.sats) tr("sats") else "₿", divider = d,
                modifier = Modifier.testTag("settings-bitcoinUnit")) { sheet = "unit" } },
            { d -> SettingsRow(tr("Currency"), SettingsIcons.cash, value = model.preferredCurrency, divider = d,
                modifier = Modifier.testTag("settings-currency")) { sheet = "currency" } },
            { d -> SettingsRow(tr("Language"), SettingsIcons.globe, value = preferenceName(Preference.language, model.localeIdentifier, locale), divider = d,
                modifier = Modifier.testTag("settings-language")) { sheet = "language" } },
            { d -> SettingsRow(tr("Hide amounts"), SettingsIcons.eyeOff, divider = d, modifier = Modifier.testTag("settings-hide-amounts"),
                end = RowEnd.Toggle(model.hidden) { model.toggleBalance() }) },
        ))
        val notifications = live.permission
        SettingsGroup(tr("App"), rows = listOf(
            { d -> SettingsRow(tr("Notifications"), if (notifications == PlatformGateRules.Permission.granted || notifications == PlatformGateRules.Permission.checking)
                SettingsIcons.bell else SettingsIcons.bellOff, value = when (notifications) {
                PlatformGateRules.Permission.granted -> tr("On"); PlatformGateRules.Permission.checking -> null; else -> tr("Off")
            }, failed = notifications == PlatformGateRules.Permission.denied || notifications == PlatformGateRules.Permission.undetermined,
                dot = if (notifications == PlatformGateRules.Permission.denied || notifications == PlatformGateRules.Permission.undetermined) RowDot.failure else null,
                selected = selected == SettingsDestination.Notifications, divider = d, modifier = Modifier.testTag("settings-notifications")) {
                open(SettingsDestination.Notifications)
            } },
            { d -> SettingsRow(tr("Advanced"), SettingsIcons.sliders, value = if (model.isWalletSuspended) tr("Suspended") else null,
                failed = model.isWalletSuspended, selected = selected == SettingsDestination.Advanced, divider = d,
                modifier = Modifier.testTag("settings-advanced")) { open(SettingsDestination.Advanced) } },
        ))
        SettingsGroup(tr("Help"), rows = listOf(
            { d -> SettingsRow(tr("Feedback"), SettingsIcons.chat, selected = selected == SettingsDestination.Feedback, divider = d,
                modifier = Modifier.testTag("settings-feedback")) { open(SettingsDestination.Feedback) } },
            { d -> SettingsRow(tr("About Noah"), SettingsIcons.ark, selected = selected == SettingsDestination.About, divider = d,
                modifier = Modifier.testTag("settings-about")) { open(SettingsDestination.About) } },
        ))
        SettingsGroup(rows = listOf({ d ->
            SettingsRow(tr("Delete wallet"), SettingsIcons.trash, danger = true, divider = d, enabled = !model.busy,
                modifier = Modifier.testTag("settings-delete-wallet")) { sheet = "delete" }
        }))
        Column(Modifier.fillMaxWidth().padding(top = 38.dp, bottom = 6.dp).clickable(role = Role.Button, onClickLabel = tr("Version")) { tap() }
            .testTag("settings-version"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Noah", style = TextStyle(fontFamily = NoahTokens.Display, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 20.sp,
                letterSpacing = (-0.03).em), color = palette.foreground)
            Text(versionText() + if (live.debug) " · " + tr("debug") else "", style = SettingsType.tick, color = palette.muted)
        }
    }
    when (sheet) {
        "unit" -> UnitSheet(model) { sheet = null }
        "currency" -> CurrencySheet(model) { sheet = null }
        "language" -> LanguageSheet(model) { sheet = null }
        "delete" -> DeleteWalletSheet(model, live, onRecovery = { sheet = null; open(SettingsDestination.Seed) }) { sheet = null }
    }
}

/** You (prototype `.me`): the orange-ringed initial, your name with the network badge off mainnet, and your address. */
@Composable
private fun Me(name: String, address: String?, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp)
        .drawBehind { if (pressed || selected) drawRoundRect(palette.soft, Offset(-12.dp.toPx(), -8.dp.toPx()), Size(size.width + 24.dp.toPx(), size.height + 18.dp.toPx()), CornerRadius(20.dp.toPx())) }
        .clickable(interaction, null, role = Role.Button, onClickLabel = tr("Profile")) { haptics.light(); onClick() }
        .semantics(mergeDescendants = true) { if (selected) this.selected = true }.testTag("settings-profile"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsAvatar(60.dp, Modifier.meAvatar(), you = true, initial = address?.take(1) ?: name.take(1))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, Modifier.weight(1f, fill = false), style = SettingsType.name, color = palette.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                NetworkBadge(BuildConfig.APP_VARIANT)
            }
            address?.let { Text(it, style = HomeType.line, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Icon(painterResource(HomeIcons.chevron), null, Modifier.offset(x = 4.dp).size(18.dp), tint = palette.muted)
    }
}

/** Saves a choice, then closes its sheet a moment later so the selection shows (prototype: 260 ms). */
@Composable
private fun rememberChoice(model: AppModel, close: () -> Unit): (() -> Unit) -> Unit {
    val haptics = rememberHaptics()
    return { save -> haptics.selection(); model.action("Couldn't save your preference.") { save(); delay(260); close() } }
}

/** Bitcoin unit: two cards showing your balance both ways. */
@Composable
private fun UnitSheet(model: AppModel, dismiss: () -> Unit) {
    val palette = LocalPalette.current
    val locale = Locale.forLanguageTag(model.localeIdentifier)
    val sample = model.summary?.balance?.totalSats?.toLong()?.takeIf { !model.hidden && it > 0 } ?: 1234L
    SettingsSheet(dismiss, tr("Bitcoin unit"), tr("How amounts are written.")) { close ->
        val choose = rememberChoice(model, close)
        for ((unit, label) in listOf(BitcoinAmountUnit.bip177 to tr("Bitcoin (BIP-177)"), BitcoinAmountUnit.sats to tr("Satoshis"))) {
            val on = model.amountUnit == unit
            Box(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .border(if (on) 2.dp else 1.5.dp, if (on) palette.foreground else palette.hairline, RoundedCornerShape(22.dp))
                .selectable(on, role = Role.RadioButton) { choose { model.preference("bitcoinAmountUnit", unit.name) } }
                .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp).testTag("preference-${unit.name}")) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(BitcoinAmount.format(sample, unit, locale), style = TextStyle(fontFamily = NoahTokens.Display, fontWeight = FontWeight.SemiBold,
                        fontSize = 30.sp, lineHeight = 33.sp, letterSpacing = (-0.02).em), color = palette.foreground, maxLines = 1)
                    Text(label, style = HomeType.line, color = palette.muted)
                }
                if (on) Box(Modifier.align(Alignment.TopEnd).size(24.dp).background(palette.foreground, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(SettingsIcons.check), null, Modifier.size(15.dp), tint = palette.background)
                }
            }
        }
    }
}

/** Currency: every supported currency by name, with its code and symbol. */
@Composable
private fun CurrencySheet(model: AppModel, dismiss: () -> Unit) {
    val locale = Locale.forLanguageTag(model.localeIdentifier)
    val height = LocalConfiguration.current.screenHeightDp
    SettingsSheet(dismiss, tr("Currency"), tr("For prices next to your bitcoin.")) { close ->
        val choose = rememberChoice(model, close)
        val codes = FiatCurrency.supported.toList()
        Column(Modifier.padding(top = 10.dp).heightIn(max = (height * .56f).dp).verticalScroll(rememberScrollState())) {
            codes.forEachIndexed { index, code ->
                val currency = java.util.Currency.getInstance(code)
                RadioRow(currency.getDisplayName(locale), "$code · ${currency.getSymbol(locale)}", model.preferredCurrency == code,
                    divider = index < codes.lastIndex, modifier = Modifier.testTag("preference-$code")) {
                    choose { model.preference("preferredCurrency", code) }
                }
            }
        }
    }
}

/** Language: each language in its own name. */
@Composable
private fun LanguageSheet(model: AppModel, dismiss: () -> Unit) {
    val languages = listOf("en", "es", "ja", "ko", "sv")
    SettingsSheet(dismiss, tr("Language")) { close ->
        val choose = rememberChoice(model, close)
        Column(Modifier.padding(top = 10.dp)) {
            languages.forEachIndexed { index, code ->
                RadioRow(preferenceName(Preference.language, code, Locale.forLanguageTag(code)), code.uppercase(Locale.ROOT), model.localeIdentifier == code,
                    divider = index < languages.lastIndex, modifier = Modifier.testTag("preference-$code")) {
                    choose { model.setLanguage(code) }
                }
            }
        }
    }
}

/**
 * Delete wallet: what you'd need to restore (balance, phrase, last backup), then typed "delete". The sheet locks while
 * deleting; success returns to onboarding as today.
 */
@Composable
internal fun DeleteWalletSheet(model: AppModel, live: SettingsLive, onRecovery: () -> Unit, dismiss: () -> Unit) {
    val palette = LocalPalette.current
    val locale = Locale.forLanguageTag(model.localeIdentifier)
    val toasts = LocalSettingsToasts.current
    var typed by rememberSaveable(key = "delete-typed") { mutableStateOf("") }
    // From the model: a fold or rotation mid-delete keeps the sheet locked and still hears how it ended.
    val deleting = model.deletingWallet
    val error = model.deleteWalletError
    val checklist = SettingsRules.deleteChecklist(model.summary?.balance?.totalSats, model.recoveryPhraseBackedUp,
        live.backup.lastBackupAt, live.backup.lastBackupStatus)
    val confirmed = SettingsRules.deleteConfirmed(typed)
    val haptics = rememberHaptics()
    LaunchedEffect(confirmed) { if (confirmed) haptics.selection() }
    SettingsSheet({ if (!deleting) { model.deleteWalletError = null; dismiss() } }, tr("Delete wallet"),
        tr("This removes the wallet and its recovery phrase from this phone. It can’t be undone."), locked = deleting,
        modifier = Modifier.testTag("delete-wallet-sheet")) { close ->
        Column(Modifier.padding(top = 12.dp)) {
            DetailRow(tr("In this wallet"), checklist.balance?.let { if (model.hidden) "₿••••" else BitcoinAmount.format(it, model.amountUnit, locale) } ?: "—",
                ValueKind.number, toasts = toasts)
            DetailRow(tr("Recovery phrase"), if (checklist.phraseSaved) tr("Saved") else tr("Not saved"), toasts = toasts, failed = !checklist.phraseSaved,
                onClick = if (checklist.phraseSaved || deleting) null else onRecovery)
            DetailRow(tr("Last backup"), when {
                checklist.backupFailed -> tr("Failed")
                checklist.lastBackupAt != null -> java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, locale)
                    .format(java.util.Date(checklist.lastBackupAt))
                else -> tr("Never")
            }, toasts = toasts, failed = checklist.backupFailed, last = true)
        }
        SettingsField(typed, { typed = it; model.deleteWalletError = null }, tr("Type delete to confirm"), Modifier.testTag("delete-wallet-input"), enabled = !deleting,
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
        error?.let { Text(it, Modifier.padding(top = 10.dp), style = HomeType.line, color = palette.failure) }
        SheetActions(tr("Cancel"), if (deleting) tr("Deleting") else tr("Delete"), onCancel = close, enabled = confirmed && (!model.busy || deleting),
            danger = true, loading = deleting, confirmTag = "delete-wallet-confirm", onConfirm = model::startDeletingWallet)
    }
}
