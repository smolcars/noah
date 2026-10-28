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
import androidx.core.net.toUri
import com.noahwallet.localization.tr
import kotlinx.coroutines.CancellationException

@Composable
fun BtcMapDetailView(model: BtcMapModel, place: BtcMapPlace, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var detail by remember(place.id) { mutableStateOf<BtcMapDetail?>(null) }
    var loading by remember(place.id) { mutableStateOf(true) }
    var failed by remember(place.id) { mutableStateOf(false) }
    var retry by remember(place.id) { mutableIntStateOf(0) }
    LaunchedEffect(place.id, retry) {
        loading = true; failed = false
        try { detail = model.repository.detail(place) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    val fields = detail?.fields.orEmpty()
    val open = { url: String? -> openPlaceLink(context, model, url) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PlaceHeader(model, detail?.place ?: place, place)
        PlaceActions(model, place, fields, open)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (failed) {
            Text(tr("Live details are unavailable. The bundled places still work offline."))
            TextButton({ retry++ }) { Text(tr("Retry details")) }
        }
        PlaceFields(place, fields, open)
        detail?.comments?.forEach { comment ->
            HorizontalDivider(); SelectionContainer { Text(comment.text) }
            Text(tr(comment.createdAt.take(10)), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Opens a place's link. Only public merchant URIs leave Noah, with no wallet data/extras; the user's choice of
 * handler is kept. Anything else (or no app for it) says why instead.
 */
private fun openPlaceLink(context: Context, model: BtcMapModel, url: String?) {
    if (url == null) { model.alert = "This place provided an invalid link."; return }
    val uri = url.toUri()
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

/** The place's name, category, verification, feature and distance, with Close. */
@Composable
private fun PlaceHeader(model: BtcMapModel, merchant: BtcMapPlace, place: BtcMapPlace) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(merchant.name, style = MaterialTheme.typography.headlineMedium)
            Text(tr(merchant.category.label), style = MaterialTheme.typography.bodySmall)
            merchant.verifiedAt?.let { Text(tr("Verified {date}", it.take(10)), style = MaterialTheme.typography.bodySmall) }
            if ((merchant.boostedUntil ?: "") > btcMapTimestamp(System.currentTimeMillis())) Text(tr("Featured"),
                color = MaterialTheme.colorScheme.primary)
            model.userLocation?.let { Text(tr(BtcMapDistance.format(BtcMapDistance.kilometers(it, place.center),
                androidx.compose.ui.platform.LocalConfiguration.current.locales[0])), style = MaterialTheme.typography.bodySmall) }
        }
        TextButton(model::closeDetail) { Text(tr("Close")) }
    }
}

/** Directions, the place's own links and Share. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceActions(model: BtcMapModel, place: BtcMapPlace, fields: Map<String, String>, open: (String?) -> Unit) {
    val context = LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ open(BtcMapLinks.directions(place)) }) { Text(tr("Directions")) }
        fields["website"]?.let { OutlinedButton({ open(BtcMapLinks.web(it)) }) { Text(tr("Website")) } }
        fields["phone"]?.let { OutlinedButton({ open(BtcMapLinks.phone(it)) }) { Text(tr("Call")) } }
        fields["email"]?.let { OutlinedButton({ open(BtcMapLinks.email(it)) }) { Text(tr("Email")) } }
        OutlinedButton({
            try { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${place.name}\nhttps://btcmap.org/merchant/${place.id}")
            }, "Share place")) }
            catch (_: android.content.ActivityNotFoundException) { model.alert = "No app is available to share this place." }
        }) { Text(tr("Share")) }
    }
}

/** What BTC Map knows: the address (tap to copy), hours, description, how to pay and the place's social links. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceFields(place: BtcMapPlace, fields: Map<String, String>, open: (String?) -> Unit) {
    val context = LocalContext.current
    var copied by remember(place.id) { mutableStateOf(false) }
    fields["address"]?.let { address ->
        TextButton({
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Address", address))
            copied = true
        }) { Text(tr(if (copied) "Address copied" else tr("Copy address: {value1}", address))) }
    }
    fields["opening_hours"]?.let { Text(tr("Hours: $it")) }
    fields["description"]?.let { SelectionContainer { Text(tr(it)) } }
    val payments = listOf("osm:payment:lightning" to "Lightning", "osm:payment:lightning_contactless" to "Contactless",
        "osm:payment:onchain" to "On-chain").filter { fields[it.first] == "yes" }.map { it.second } + listOfNotNull(fields["payment_provider"])
    if (payments.isNotEmpty()) { Text(tr("Pay with"), style = MaterialTheme.typography.titleLarge); Text(tr(payments.joinToString(" · "))) }
    fields["required_app_url"]?.let { TextButton({ open(BtcMapLinks.web(it)) }) { Text(tr("This place may require another payment app")) } }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("twitter", "facebook", "instagram", "telegram").forEach { key ->
            fields[key]?.let { TextButton({ open(BtcMapLinks.web(it)) }) { Text(tr(if (key == "twitter") "X" else key.replaceFirstChar(Char::titlecase))) } }
        }
    }
}
