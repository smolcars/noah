package com.noahwallet.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.noahwallet.BuildConfig
import com.noahwallet.formatting.BitcoinAmount
import com.noahwallet.localization.tr
import com.noahwallet.localization.trPlural
import com.noahwallet.services.logging.LogFile
import com.noahwallet.services.storage.PersistedWalletState
import com.noahwallet.services.wallet.EsploraSettings
import com.noahwallet.services.wallet.WalletConfiguration
import com.noahwallet.services.wallet.WalletSession.DebugAction
import com.noahwallet.ui.AppModel
import com.noahwallet.ui.activity.BarButton
import com.noahwallet.ui.activity.DetailRow
import com.noahwallet.ui.activity.ValueKind
import com.noahwallet.ui.activity.short
import com.noahwallet.ui.home.HomeType
import com.noahwallet.ui.home.tonal
import com.noahwallet.ui.onboarding.LocalPalette
import com.noahwallet.ui.onboarding.rememberHaptics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private fun host(url: String) = runCatching { java.net.URI(url).host }.getOrNull() ?: url

/**
 * Advanced (prototype `SCR.advanced`): the Ark server and Esplora, Logs, Reset registration (today's confirmation),
 * the Debug mode switch and Debug once unlocked, and Suspend wallet, which now asks first.
 */
@Composable
internal fun AdvancedPage(model: AppModel, nav: SettingsNav, live: SettingsLive, initialSheet: String? = null) {
    val haptics = rememberHaptics()
    val toasts = LocalSettingsToasts.current
    var sheet by rememberSaveable { mutableStateOf(initialSheet?.takeIf { it in setOf("reset", "suspend") }) }
    val selection = EsploraSettings(model.settings).selection(BuildConfig.APP_VARIANT)
    val config = WalletConfiguration.effective(BuildConfig.APP_VARIANT, selection.endpoint)
    val debugOff = tr("Debug off")
    val resumedText = tr("Wallet resumed")
    SettingsFrame(tr("Advanced"), nav.back) {
        SettingsGroup(tr("Network"), rows = listOf(
            { d -> SettingsRow(tr("Ark server"), SettingsIcons.server, value = host(config.arkServer), divider = d,
                modifier = Modifier.testTag("advanced-ark")) { nav.open(SettingsDestination.Ark) } },
            { d -> SettingsRow(tr("Esplora"), SettingsIcons.chain, value = when {
                BuildConfig.APP_VARIANT == "regtest" -> tr("Bitcoin Core")
                selection.endpoint != null -> tr("Custom")
                else -> config.esplora?.let(::host)
            }, divider = d, modifier = Modifier.testTag("advanced-esplora")) { nav.open(SettingsDestination.Esplora) } },
        ))
        SettingsGroup(tr("Diagnostics"), rows = listOfNotNull(
            { d -> SettingsRow(tr("Logs"), SettingsIcons.doc, divider = d, modifier = Modifier.testTag("advanced-logs")) { nav.open(SettingsDestination.Logs) } },
            { d -> SettingsRow(tr("Reset registration"), SettingsIcons.refresh, divider = d, enabled = !model.busy && !model.isWalletSuspended,
                modifier = Modifier.testTag("advanced-reset")) { sheet = "reset" } },
            // Decision 7: once unlocked, debug can be turned off; five taps on the version turn it back on.
            if (live.debug) ({ d -> SettingsRow(tr("Debug mode"), SettingsIcons.bug, divider = d, modifier = Modifier.testTag("advanced-debug-mode"),
                end = RowEnd.Toggle(true) {
                    val wallet = model.settings.persistedState<PersistedWalletState>(PersistedWalletState.key) ?: PersistedWalletState()
                    model.settings.setPersistedState(wallet.copy(isDebugModeEnabled = false), PersistedWalletState.key, 0)
                    live.debug = false; toasts.show(debugOff)
                }) }) else null,
            if (live.debug) ({ d -> SettingsRow(tr("Debug"), SettingsIcons.bug, divider = d, modifier = Modifier.testTag("advanced-debug")) {
                nav.open(SettingsDestination.Debug)
            } }) else null,
        ))
        SettingsGroup(tr("Wallet"), rows = listOf({ d ->
            SettingsRow(tr("Suspend wallet"), SettingsIcons.stop, sub = tr("Pauses all wallet activity"), divider = d, modifier = Modifier.testTag("advanced-suspend"),
                end = RowEnd.Toggle(model.isWalletSuspended, !model.busy) { suspend ->
                    if (suspend) sheet = "suspend"
                    else model.action("Couldn't change wallet suspension. Please try again.") { model.resumeWallet(); haptics.success(); toasts.show(resumedText) }
                })
        }))
    }
    when (sheet) {
        "reset" -> {
            val registered = tr("Registered again")
            SettingsSheet({ sheet = null }, tr("Reset registration?"), tr("Noah registers this wallet with the server again. Your wallet stays.")) { close ->
                SheetActions(tr("Cancel"), tr("Reset"), onCancel = close, enabled = !model.busy && !model.isWalletSuspended, loading = model.busy,
                    confirmTag = "advanced-reset-confirm", onConfirm = {
                        model.action("Couldn't register again. Please retry.") { model.reRegisterWithServer(); haptics.success(); toasts.show(registered); close() }
                    })
            }
        }
        "suspend" -> {
            val suspended = tr("Wallet suspended")
            SettingsSheet({ sheet = null }, tr("Suspend wallet?"), tr("Noah closes the wallet and stops payments until you turn this off."),
                modifier = Modifier.testTag("suspend-sheet")) { close ->
                SheetActions(tr("Cancel"), tr("Suspend"), onCancel = close, danger = true, enabled = !model.busy, loading = model.busy,
                    confirmTag = "advanced-suspend-confirm", onConfirm = {
                        model.action("Couldn't change wallet suspension. Please try again.") { model.suspendWallet(); haptics.warning(); toasts.show(suspended); close() }
                    })
            }
        }
    }
}

/**
 * Ark server (prototype `SCR.arkinfo`): every row today's Ark info shows, with plainer labels, then the wallet's
 * endpoints and explorer APIs. Bitcoind RPC only on regtest.
 */
@Composable
internal fun ArkServerPage(model: AppModel, nav: SettingsNav) {
    val palette = LocalPalette.current
    val toasts = LocalSettingsToasts.current
    val locale = Locale.forLanguageTag(model.localeIdentifier)
    var info by remember { mutableStateOf<io.github.niteshbalusu11.bark.ArkInfo?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun load() { loading = true; try { info = withContext(Dispatchers.IO) { model.session.arkInfo() } } catch (e: CancellationException) { throw e } catch (_: Exception) { } finally { loading = false } }
    LaunchedEffect(Unit) { if (!model.isWalletSuspended) load() }
    val selection = EsploraSettings(model.settings).selection(BuildConfig.APP_VARIANT)
    val config = WalletConfiguration.effective(BuildConfig.APP_VARIANT, selection.endpoint)
    val number = java.text.NumberFormat.getIntegerInstance(locale)
    fun sats(value: Long) = BitcoinAmount.format(value, model.amountUnit, locale)
    val network = when (BuildConfig.APP_VARIANT) { "mainnet" -> tr("Mainnet"); "signet" -> tr("Signet"); else -> tr("Regtest") }
    SettingsFrame(tr("Ark server"), nav.back, chip = {
        Text(network, Modifier.tonal(RoundedCornerShape(50), palette.soft).padding(horizontal = 9.dp, vertical = 3.dp), style = HomeType.header, color = palette.foreground)
    }) {
        Column(Modifier.padding(top = 6.dp)) {
            DetailRow(tr("Address"), host(config.arkServer), copy = config.arkServer, toasts = toasts)
            info?.let {
                DetailRow(tr("Server key"), short(it.serverPubkey), ValueKind.mono, copy = it.serverPubkey, toasts = toasts)
                DetailRow(tr("Mailbox key"), short(it.mailboxPubkey), ValueKind.mono, copy = it.mailboxPubkey, toasts = toasts, last = true)
            }
        }
        val current = info
        if (current == null) {
            FinePrint(if (loading) tr("Loading…") else tr("Ark server info is unavailable. Try refreshing."))
            if (!loading && !model.isWalletSuspended) SettingsLink(tr("Try again")) { scope.launch { load() } }
        } else {
            SectionLabel(tr("Moving to Ark"))
            DetailRow(tr("Smallest move"), sats(current.minBoardAmountSats.toLong()), ValueKind.number, toasts = toasts)
            DetailRow(tr("Largest VTXO"), current.maxVtxoAmountSats?.let { sats(it.toLong()) } ?: tr("No limit"), ValueKind.number, toasts = toasts)
            DetailRow(tr("Confirmations"), trPlural("{n} blocks", current.requiredBoardConfirmations.toLong()), toasts = toasts, last = true)
            SectionLabel(tr("Rounds and expiry"))
            DetailRow(tr("Round every"), tr("{n} seconds", number.format(current.roundIntervalSecs.toLong())), toasts = toasts)
            DetailRow(tr("Round nonces"), number.format(current.nbRoundNonces.toLong()), ValueKind.number, toasts = toasts)
            DetailRow(tr("VTXO lifetime"), trPlural("{n} blocks", current.vtxoLifetime.toLong()), toasts = toasts)
            DetailRow(tr("Exit delta"), trPlural("{n} blocks", current.vtxoExitDelta.toLong()), toasts = toasts)
            DetailRow(tr("Lightning send delta"), trPlural("{n} blocks", current.htlcSendExpiryDelta.toLong()), toasts = toasts, last = true)
            SectionLabel(tr("Lightning"))
            DetailRow(tr("Receive anti-DoS"), if (current.lnReceiveAntiDosRequired) tr("Required") else tr("Not required"), toasts = toasts, last = true)
        }
        SectionLabel(tr("Endpoints"))
        DetailRow(tr("Esplora"), if (selection.endpoint == null) tr("Noah default") else tr("Custom"), toasts = toasts) { nav.open(SettingsDestination.Esplora) }
        config.esplora?.let { DetailRow(tr("Esplora API"), host(it), copy = it, toasts = toasts) }
        config.bitcoind?.let { DetailRow(tr("Bitcoind RPC"), host(it), copy = it, toasts = toasts) }
        val height = ArkInfoEndpoints.blockHeight(BuildConfig.APP_VARIANT, selection.endpoint)
        DetailRow(tr("Block height API"), host(height), copy = height, toasts = toasts)
        val explorer = "https://mempool.space" + if (BuildConfig.APP_VARIANT == "mainnet") "" else "/signet"
        DetailRow(tr("Explorer"), host(explorer), copy = explorer, toasts = toasts)
        val prices = com.noahwallet.services.server.ServerEndpoint.priceURLs(
            com.noahwallet.services.server.ServerEndpoint.baseURL(BuildConfig.APP_VARIANT, BuildConfig.REGTEST_SERVER_URL))
        DetailRow(tr("Price API"), host(prices.first), copy = prices.first, toasts = toasts)
        DetailRow(tr("Historical price API"), host(prices.second), copy = prices.second, toasts = toasts, last = true)
    }
}

/** Esplora (prototype `SCR.esplora`): where Noah reads the chain; test and save, reset, as today. Regtest uses Bitcoin Core. */
@Composable
internal fun EsploraPage(model: AppModel, nav: SettingsNav) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val toasts = LocalSettingsToasts.current
    val store = remember { EsploraSettings(model.settings) }
    var selection by remember { mutableStateOf(store.selection(BuildConfig.APP_VARIANT)) }
    val effective = WalletConfiguration.effective(BuildConfig.APP_VARIANT, selection.endpoint).esplora.orEmpty()
    var input by rememberSaveable { mutableStateOf(selection.input ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    val regtest = BuildConfig.APP_VARIANT == "regtest"
    val editable = !model.busy && !regtest
    val switched = tr("Switched"); val defaulted = tr("Using the default")
    fun apply(value: String?) {
        error = null
        model.action("Endpoint not changed") {
            try {
                model.services.switchEsploraEndpoint(value) { reopened ->
                    withContext(Dispatchers.Main) {
                        selection = store.selection(BuildConfig.APP_VARIANT)
                        error = if (reopened) "Endpoint not changed. The wallet is using the previous endpoint."
                            else "Endpoint not changed. Restart Noah to reopen the previous endpoint."
                    }
                }
                selection = store.selection(BuildConfig.APP_VARIANT)
                if (selection.endpoint == null) input = ""
                haptics.success(); toasts.show(if (selection.endpoint == null) defaulted else switched)
                model.services.startServerServices()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: com.noahwallet.services.wallet.EsploraOpenWait.Timeout) { error = "The server isn't responding; switching back…" }
            catch (failure: Exception) { haptics.error(); error = LogFile.redact(failure.message ?: "Endpoint not changed") }
            finally {
                selection = store.selection(BuildConfig.APP_VARIANT)
                model.services.reconcileBackupTriggers()
            }
        }
    }
    SettingsFrame(tr("Esplora"), nav.back, line = tr("Where Noah reads the Bitcoin chain."), foot = if (regtest) null else ({
        FootPill(tr("Use default"), Modifier.testTag("esplora-reset"), outlined = true, weight = .75f,
            enabled = editable && (selection.endpoint != null || selection.input != null)) { apply(null) }
        FootPill(if (model.busy) tr("Testing") else tr("Test and save"), Modifier.testTag("esplora-save"), enabled = editable && input.isNotBlank(), loading = model.busy) { apply(input) }
    })) {
        Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (regtest) tr("Bitcoin Core") else effective, style = SettingsType.current, color = palette.foreground, maxLines = 2)
            Text(if (regtest) tr("Regtest wallets use Bitcoin Core.") else if (selection.endpoint == null) tr("Noah default") else tr("Custom server"),
                style = HomeType.line, color = palette.muted)
        }
        if (!regtest) {
            SettingsField(input, { input = it; error = null }, "https://mempool.space/api", Modifier.testTag("esplora-endpoint-input"), label = tr("Custom server"),
                failed = error != null, enabled = editable, keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
            error?.let { FinePrint(tr(it), failed = true) } ?: FinePrint(tr("Noah checks it’s on the right network before switching."))
            selection.attention?.let { FinePrint(tr(it), failed = true) }
            Warning(tr("A custom server can see your addresses. Use one you trust."), SettingsIcons.eye)
        }
    }
}

/** Logs (prototype `SCR.logs`): today's app log with time and level, share and refresh. */
@Composable
internal fun LogsPage(model: AppModel, nav: SettingsNav) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val snapshot = remember { LogSnapshot() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    suspend fun load() { snapshot.load(read = { withContext(Dispatchers.IO) { LogFile.shared(context).lines() } }) }
    LaunchedEffect(Unit) { load() }
    LaunchedEffect(snapshot.updatedAt) { if (snapshot.lines.isNotEmpty()) listState.scrollToItem(snapshot.lines.lastIndex) }
    val share = rememberFileShare(model, tr("Share app logs"), "text/plain")
    val updated = snapshot.updatedAt?.let { tr("Updated at {time}", DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))) }
    SettingsFrame(tr("Logs"), nav.back, line = updated, scroll = null, actions = {
        BarButton(SettingsIcons.refresh, tr("Refresh app logs"), Modifier.testTag("logs-refresh")) { haptics.light(); scope.launch { load() } }
        BarButton(SettingsIcons.share, tr("Share app logs"), Modifier.testTag("logs-share")) {
            haptics.light()
            share {
                val scratch = model.services.scratch
                val folder = scratch.makeFolder("share")
                try {
                    val file = java.io.File(folder, "noah.log").apply { writeText(LogFile.shared(context).lines().joinToString("\n")) }
                    SharedFile(file) { scratch.discard(folder) }
                } catch (failure: Exception) { scratch.discard(folder); throw failure }
            }
        }
    }) {
        snapshot.error?.let { FinePrint(tr(it), failed = true) }
        if (!snapshot.loading && snapshot.error == null && snapshot.lines.isEmpty()) FinePrint(tr("No logs found."))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 14.dp).testTag("logs-list"), state = listState) {
            items(snapshot.lines) { line -> SelectionContainer { Text(logLine(line, palette), style = SettingsType.log, color = palette.foreground) } }
            item { FinePrint(tr("Shared logs never include your phrase or keys."), Modifier.padding(bottom = 24.dp)) }
        }
    }
}

/** A log line with its time and level muted, errors in red. */
private fun logLine(line: String, palette: com.noahwallet.ui.design.NoahTokens.Palette) = buildAnnotatedString {
    val parts = Regex("""^(\S+ \S+)\s+(\w+)\s+(.*)$""").find(line)?.groupValues
    if (parts == null) { append(line); return@buildAnnotatedString }
    withStyle(SpanStyle(color = palette.muted)) { append(parts[1].substringAfter(' ').take(12)) }
    append(' ')
    withStyle(SpanStyle(color = if (parts[2].startsWith("""E""")) palette.failure else palette.muted)) { append(parts[2].padEnd(5)) }
    append(' '); append(parts[3])
}

/**
 * Debug (prototype `SCR.debug`), once unlocked: today's actions (bark's info and debug info, push token, maintenance,
 * VTXO tools), destructive ones behind typed "delete", and the result with copy.
 */
@Composable
internal fun DebugPage(model: AppModel, nav: SettingsNav) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val toasts = LocalSettingsToasts.current
    var action by rememberSaveable { mutableStateOf(DebugAction.ark) }
    var push by rememberSaveable { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var confirmation by rememberSaveable { mutableStateOf("") }
    val height = LocalConfiguration.current.screenHeightDp
    fun execute() { model.action("Action failed. Check your input and connection before trying again.") {
        result = null
        try {
            result = if (push) {
                var token: String? = null
                val outcome = com.noahwallet.services.push.PushRuntime(context, model.services).register { token = it }
                token ?: outcome.name
            } else model.session.debug(action, input)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { result = com.noahwallet.services.logging.SafeErrorDescription.of(failure) }
    } }
    val destructive = !push && action.destructive
    SettingsFrame(tr("Debug"), nav.back, line = tr("For developers. Some actions change your wallet."), foot = {
        FootPill(tr("Run"), Modifier.testTag("debug-run"), danger = destructive, loading = model.busy,
            enabled = !model.busy && !model.isWalletSuspended && (push || !action.needsInput || input.isNotBlank())) {
            if (destructive) { confirmation = ""; confirming = true } else execute()
        }
    }) {
        FieldLabel(tr("Action"))
        PickerRow(if (push) tr("Get Push Token") else tr(action.title), Modifier.testTag("debug-action"), enabled = !model.busy) { picking = true }
        if (!push && action.needsInput) SettingsField(input, { input = it }, if (action == DebugAction.offboard) "bc1q…" else "…",
            label = if (action == DebugAction.offboard) tr("Bitcoin address") else tr("VTXO hex or ID"), enabled = !model.busy,
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
        result?.let { value ->
            FieldLabel(tr("Result"))
            SelectionContainer {
                Text(value, Modifier.padding(top = 8.dp).fillMaxWidth().background(palette.soft, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 14.dp)
                    .testTag("debug-result"), style = SettingsType.result, color = palette.foreground)
            }
            val copied = tr("Copied")
            SettingsLink(tr("Copy")) {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Result", value))
                toasts.show(copied)
            }
        }
    }
    if (picking) SettingsSheet({ picking = false }, tr("Action")) { close ->
        Column(Modifier.padding(top = 6.dp).heightIn(max = (height * .56f).dp).verticalScroll(rememberScrollState())) {
            DebugAction.entries.forEach { candidate ->
                RadioRow(tr(candidate.title), selected = !push && action == candidate, danger = candidate.destructive) {
                    action = candidate; push = false; input = ""; result = null; close()
                }
            }
            RadioRow(tr("Get Push Token"), selected = push, divider = false) { push = true; result = null; close() }
        }
    }
    if (confirming) SettingsSheet({ confirming = false }, tr("{action}?", tr(action.title)), tr("This changes your wallet and can lose funds.")) { close ->
        SettingsField(confirmation, { confirmation = it }, tr("Type delete to confirm"), Modifier.testTag("debug-confirm-input"),
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
        SheetActions(tr("Cancel"), tr("Confirm"), onCancel = close, danger = true, enabled = SettingsRules.deleteConfirmed(confirmation),
            confirmTag = "debug-confirm", onConfirm = { confirming = false; execute() })
    }
}
