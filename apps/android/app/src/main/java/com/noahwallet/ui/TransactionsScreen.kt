package com.noahwallet.ui

import com.noahwallet.localization.tr
import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.noahwallet.R
import com.noahwallet.formatting.BitcoinAmount
import com.noahwallet.formatting.BitcoinAmountUnit
import com.noahwallet.history.*
import kotlinx.coroutines.*
import java.io.File
import java.text.DateFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(app: AppModel, onRepeatPayment: (RepeatPayment) -> Unit) {
    val history = app.history
    var filterName by rememberSaveable { mutableStateOf(HistoryFilter.All.name) }
    val filter = HistoryFilter.valueOf(filterName)
    var selectedID by rememberSaveable { mutableStateOf<String?>(null) }
    var sharing by remember { mutableStateOf(false) }
    var shareError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val filtered = history.transactions.filter(filter::includes)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tr("Transactions"), style = MaterialTheme.typography.headlineLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ scope.launch { history.refresh(app.preferredCurrency, true) } }, enabled = !history.isRefreshing, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text(tr("Refresh")) }
                    TextButton({
                        sharing = true; shareError = null
                        val csv = HistoryCSV.content(filtered, app.preferredCurrency)
                        scope.launch {
                            var export: File? = null
                            var launched = false
                            try {
                                app.services.scratch.withOperation {
                                    val file = withContext(Dispatchers.IO) {
                                        app.historyExports.create(csv).also { export = it }
                                    }
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
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { shareError = "Couldn't export transactions. Please try again." }
                            finally {
                                if (!launched) withContext(NonCancellable + Dispatchers.IO) { export?.delete() }
                                sharing = false
                            }
                        }
                    }, enabled = !history.isRefreshing && !sharing, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text(tr(if (sharing) "Exporting…" else "Export transactions")) }
                }
            }
            shareError?.let { Text(tr(it), color = MaterialTheme.colorScheme.error) }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                HistoryFilter.entries.forEachIndexed { index, option ->
                    SegmentedButton(selected = filter == option, onClick = { filterName = option.name },
                        shape = SegmentedButtonDefaults.itemShape(index, HistoryFilter.entries.size), icon = {}) {
                        Text(tr(option.name), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }
        }
        HistoryDetailsHost(app, selectedID, { selectedID = null }, onRepeatPayment, showEmptyPane = true) {
            PullToRefreshBox(history.isRefreshing, { scope.launch { history.refresh(app.preferredCurrency, true) } }, Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    when {
                        history.isLoading -> item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.semantics { contentDescription = "Loading transactions" }) } }
                        history.error != null -> item {
                            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(tr("Couldn't load transactions"), style = MaterialTheme.typography.titleLarge)
                                Text(tr(history.error!!))
                                TextButton({ scope.launch { history.refresh(app.preferredCurrency) } }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text(tr("Retry")) }
                            }
                        }
                        filtered.isEmpty() -> item {
                            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(tr("No transactions yet"), style = MaterialTheme.typography.titleLarge)
                                Text(tr("Your payments will appear here."))
                            }
                        }
                        else -> items(filtered, key = { it.id }) { row ->
                            TransactionRow(row, app.amountUnit, selected = row.id == selectedID) { selectedID = row.id }
                            HorizontalDivider(Modifier.padding(start = 64.dp, end = 20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TransactionRow(row: HistoryTransaction, unit: BitcoinAmountUnit, recent: Boolean = false, selected: Boolean = false, onClick: () -> Unit) {
    val color = transactionColor(row)
    Surface(color = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).semantics(mergeDescendants = true) {}
            .padding(horizontal = 20.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(transactionIcon(row)), null, Modifier.size(28.dp), tint = color)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(tr(row.displayLabel), style = MaterialTheme.typography.titleLarge)
                Text(tr(row.formattedDate(recent)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                val prefix = if (row.isCanceled || row.isInternalTransfer) "" else if (row.direction == "outgoing") "−" else "+"
                Text(tr(prefix + BitcoinAmount.format(row.amount, unit, androidx.compose.ui.platform.LocalConfiguration.current.locales[0])), style = MaterialTheme.typography.titleLarge, color = color)
                if (!recent) row.statusLabel?.let { Text(tr(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

internal fun transactionIcon(row: HistoryTransaction) = when {
    row.movementKind == "onboard" -> R.drawable.ic_receive
    row.movementKind == "offboard" -> R.drawable.ic_send
    row.type in listOf("Bolt11", "Lnurl") -> R.drawable.ic_history_lightning
    row.type == "Arkoor" -> R.drawable.ic_history_ark
    else -> R.drawable.ic_history_chain
}
@Composable
internal fun transactionColor(row: HistoryTransaction): Color {
    val dark = isSystemInDarkTheme()
    return when {
        row.isCanceled -> MaterialTheme.colorScheme.onSurfaceVariant
        row.isInternalTransfer -> if (dark) Color(0xFFFDBA74) else Color(0xFF945200)
        row.direction == "outgoing" -> if (dark) Color(0xFFFF8080) else Color(0xFFB42318)
        else -> if (dark) Color(0xFF4ADE80) else Color(0xFF137A36)
    }
}
internal fun HistoryTransaction.formattedDate(recent: Boolean = false): String = confirmationLabel ?: timestamp?.let {
    if (recent) DateFormat.getDateInstance(DateFormat.MEDIUM).format(it)
    else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(it)
} ?: date
