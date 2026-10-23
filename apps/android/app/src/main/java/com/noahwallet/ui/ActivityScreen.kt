package com.noahwallet.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.noahwallet.BuildConfig
import com.noahwallet.formatting.FiatCurrency
import com.noahwallet.history.HistoryCSV
import com.noahwallet.history.HistoryExportCleanup
import com.noahwallet.history.HistoryExportFiles
import com.noahwallet.history.HistoryMetadata
import com.noahwallet.history.HistoryTransaction
import com.noahwallet.history.RepeatPayment
import com.noahwallet.services.server.value
import com.noahwallet.services.wallet.ChainStatus
import com.noahwallet.services.wallet.WalletConfiguration
import com.noahwallet.ui.activity.*
import com.noahwallet.ui.home.HomeToasts
import com.noahwallet.ui.settings.SettingsDestination
import kotlinx.coroutines.*
import java.io.File
import java.util.Date
import java.util.Locale

/**
 * Activity (docs/native-rewrite/design/activity.md): live wallet history into [ActivityContent]. Refresh, sync,
 * export, Pay again and the explorer are today's; coins, exits and recurring schedules open Settings' screens.
 */
@Composable
fun ActivityScreen(app: AppModel, onRepeatPayment: (RepeatPayment) -> Unit, onReceive: () -> Unit = {},
                   onSettings: (SettingsDestination) -> Unit = {}, barHidden: (Boolean) -> Unit = {}) {
    val history = app.history
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberActivityState()
    val toasts = remember { HomeToasts() }
    val memory = app.homeMemory
    val currency = app.preferredCurrency.takeIf { it in FiatCurrency.supported } ?: "USD"
    var price by remember { mutableStateOf<Double?>(null) }
    // Today's quote, read as Activity appears (Home's source): the value of today's and pending payments.
    LaunchedEffect(currency, app.isWalletSuspended) {
        price = null
        if (app.isWalletSuspended) return@LaunchedEffect
        price = try {
            com.noahwallet.api.generated.client.V0PricesClient(app.services.client).fiatPrices(kotlinx.serialization.json.JsonObject(emptyMap()))
                .value().rates[currency]?.takeIf { it.isFinite() && it > 0 }
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
    }
    var refreshing by remember { mutableStateOf(false) }
    var tip by remember { mutableStateOf<Long?>(null) }
    val confirmedAt = remember { mutableStateMapOf<String, Long>() }
    var seen by remember { mutableStateOf(memory.seenRows) }
    val rows = history.transactions
    val ui = ActivityUi(
        records = rows, loading = history.isLoading, error = history.error, refreshing = refreshing, unit = app.amountUnit,
        locale = Locale.forLanguageTag(app.localeIdentifier), hidden = app.hidden,
        ownDomain = app.server.state.lightningAddress?.substringAfter('@', "")?.takeIf { it.isNotEmpty() }, variant = BuildConfig.APP_VARIANT,
        currency = currency, price = price, newRows = ActivityRules.newRows(rows, seen), tip = tip, confirmedAt = confirmedAt.toMap(),
        chainNeeded = WalletConfiguration.base(BuildConfig.APP_VARIANT).roundTxRequiredConfirmations.toLong(), revision = history.revision, now = Date(),
    )
    val actions = ActivityActions(
        refresh = {
            scope.launch { refreshing = true; try { history.refresh(app.preferredCurrency, true) } finally { refreshing = false } }
        },
        retry = { scope.launch { history.refresh(app.preferredCurrency) } },
        receive = onReceive,
        repeat = { state.selected = null; onRepeatPayment(it) },
        share = { text ->
            // The receipt only goes where the reader sends it; it's never logged.
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
        },
        export = { records -> exportCSV(app, context, records) },
        explorer = { url ->
            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); true }
            catch (_: android.content.ActivityNotFoundException) { false }
        },
        recurring = { id -> app.recurringSchedule = id; onSettings(SettingsDestination.Recurring) },
        exits = { onSettings(SettingsDestination.Exits) },
        coin = { id -> app.vtxoDetail = id; onSettings(SettingsDestination.Vtxos) },
        barHidden = barHidden,
        seen = { id -> memory.seenRows = ActivityRules.seen(memory.seenRows, id); seen = memory.seenRows },
        shown = { record -> scope.launch { readChain(app, record, { tip = it }) { txid, height -> confirmedAt[txid] = height } } },
    )
    com.noahwallet.ui.onboarding.OnboardingFrame(null, splitAtWindowCentre = true) {
        ActivityContent(ui, actions, state, toasts)
    }
}

/**
 * The block row's chain data for a pending on-chain payment, read once as it's shown (and again as its record
 * changes): the wallet's tip and, for an Ark-funded send, its transaction's block. No polling.
 */
private suspend fun readChain(app: AppModel, record: HistoryTransaction, tip: (Long) -> Unit, confirmed: (String, Long) -> Unit) {
    val pending = record.source == "onchain-wallet" && record.hasConfirmation != true || record.movementStatus == "pending" &&
        record.movementKind in listOf("send-onchain", "offboard")
    if (!pending) return
    try {
        tip(app.session.chainTip())
        val txid = HistoryMetadata.parse(record.metadataJson ?: "").offboardTxid?.takeIf { record.source == "ark" } ?: return
        app.session.chainStatus(txid).takeIf { it.kind == ChainStatus.Kind.confirmed }?.height?.let { confirmed(txid, it) }
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
}

/** Today's export: the CSV in the app's export folder, its one-day cleanup and the system share sheet. Null on success. */
private suspend fun exportCSV(app: AppModel, context: android.content.Context, records: List<HistoryTransaction>): String? {
    val csv = HistoryCSV.content(records, app.preferredCurrency)
    var export: File? = null
    var launched = false
    return try {
        app.services.scratch.withOperation {
            val file = withContext(Dispatchers.IO) { app.historyExports.create(csv).also { export = it } }
            androidx.work.WorkManager.getInstance(context).enqueue(
                androidx.work.OneTimeWorkRequestBuilder<HistoryExportCleanup>()
                    .setInitialDelay(HistoryExportFiles.retentionMillis, java.util.concurrent.TimeUnit.MILLISECONDS).build())
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Noah Wallet Transaction Export")
                clipData = ClipData.newUri(context.contentResolver, "Transactions", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Export transactions"))
            launched = true
        }
        null
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { "Couldn't export transactions. Please try again." }
    finally { if (!launched) withContext(NonCancellable + Dispatchers.IO) { export?.delete() } }
}
