package com.noahwallet.btcmap

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BtcMapDetailView(model: BtcMapModel, place: BtcMapPlace, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var detail by remember(place.id) { mutableStateOf<BtcMapDetail?>(null) }
    var loading by remember(place.id) { mutableStateOf(true) }
    var failed by remember(place.id) { mutableStateOf(false) }
    var copied by remember(place.id) { mutableStateOf(false) }
    var retry by remember(place.id) { mutableIntStateOf(0) }
    LaunchedEffect(place.id, retry) {
        loading = true; failed = false
        try { detail = model.repository.detail(place) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    val fields = detail?.fields.orEmpty()
    val merchant = detail?.place ?: place
    fun open(url: String?) {
        if (url == null) { model.alert = "This place provided an invalid link."; return }
        // Only public merchant URIs leave Noah, with no wallet data/extras; keep the user's choice of handler.
        val uri = Uri.parse(url)
        val intent = when (uri.scheme?.lowercase()) {
            "http", "https" -> Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
            "tel" -> Intent(Intent.ACTION_DIAL, uri)
            "mailto" -> Intent(Intent.ACTION_SENDTO, uri)
            else -> { model.alert = "This place provided an invalid link."; return }
        }
        try { context.startActivity(intent) }
        catch (_: android.content.ActivityNotFoundException) { model.alert = "No app is available to open this link." }
        catch (_: SecurityException) { model.alert = "This link could not be opened." }
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(merchant.name, style = MaterialTheme.typography.headlineMedium)
                Text(merchant.category.label, style = MaterialTheme.typography.bodySmall)
                merchant.verified_at?.let { Text("Verified ${it.take(10)}", style = MaterialTheme.typography.bodySmall) }
                if ((merchant.boosted_until ?: "") > btcMapTimestamp(System.currentTimeMillis())) Text("Featured", color = MaterialTheme.colorScheme.primary)
                model.userLocation?.let { Text(BtcMapDistance.format(BtcMapDistance.kilometers(it, place.center)), style = MaterialTheme.typography.bodySmall) }
            }
            TextButton(model::closeDetail) { Text("Close") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ open(BtcMapLinks.directions(place)) }) { Text("Directions") }
            fields["website"]?.let { OutlinedButton({ open(BtcMapLinks.web(it)) }) { Text("Website") } }
            fields["phone"]?.let { OutlinedButton({ open(BtcMapLinks.phone(it)) }) { Text("Call") } }
            fields["email"]?.let { OutlinedButton({ open(BtcMapLinks.email(it)) }) { Text("Email") } }
            OutlinedButton({
                try { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${place.name}\nhttps://btcmap.org/merchant/${place.id}")
                }, "Share place")) }
                catch (_: android.content.ActivityNotFoundException) { model.alert = "No app is available to share this place." }
            }) { Text("Share") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (failed) {
            Text("Live details are unavailable. The bundled places still work offline.")
            TextButton({ retry++ }) { Text("Retry details") }
        }
        fields["address"]?.let { address ->
            TextButton({
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Address", address))
                copied = true
            }) { Text(if (copied) "Address copied" else "Copy address: $address") }
        }
        fields["opening_hours"]?.let { Text("Hours: $it") }
        fields["description"]?.let { SelectionContainer { Text(it) } }
        val payments = listOf("osm:payment:lightning" to "Lightning", "osm:payment:lightning_contactless" to "Contactless",
            "osm:payment:onchain" to "On-chain").filter { fields[it.first] == "yes" }.map { it.second } + listOfNotNull(fields["payment_provider"])
        if (payments.isNotEmpty()) { Text("Pay with", style = MaterialTheme.typography.titleLarge); Text(payments.joinToString(" · ")) }
        fields["required_app_url"]?.let { TextButton({ open(BtcMapLinks.web(it)) }) { Text("This place may require another payment app") } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("twitter", "facebook", "instagram", "telegram").forEach { key ->
                fields[key]?.let { TextButton({ open(BtcMapLinks.web(it)) }) { Text(if (key == "twitter") "X" else key.replaceFirstChar(Char::titlecase)) } }
            }
        }
        detail?.comments?.forEach { comment ->
            HorizontalDivider(); SelectionContainer { Text(comment.text) }
            Text(comment.createdAt.take(10), style = MaterialTheme.typography.bodySmall)
        }
    }
}
