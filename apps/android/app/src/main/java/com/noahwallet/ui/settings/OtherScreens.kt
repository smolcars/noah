package com.noahwallet.ui.settings

import com.noahwallet.ui.WalletAlertDialog

import com.noahwallet.localization.tr
import android.media.MediaPlayer
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.noahwallet.R
import com.noahwallet.api.generated.client.V0SupportTicketClient
import com.noahwallet.services.server.DeviceInfo
import com.noahwallet.services.server.value
import com.noahwallet.services.wallet.WalletSession.DebugAction
import com.noahwallet.ui.*
import kotlinx.coroutines.*

@Composable
fun FeedbackScreen(model: AppModel) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var screenshot by remember { mutableStateOf<ByteArray?>(null) }
    var cropImage by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var success by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            loading = true; error = null
            try {
                cropImage = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use(FeedbackDraft::readScreenshot) ?: error("Unreadable image")
                    decodeScreenshot(bytes)
                }
            } catch (validation: IllegalArgumentException) { error = validation.message }
            catch (_: Exception) { error = "Couldn't read the screenshot. Please try again." }
            finally { loading = false }
        }
    }
    SettingsColumn {
        if (success) { SectionTitle("Thank You"); Text(tr("Your feedback was sent to the Noah team.")) }
        else {
            Text(tr("Report a bug or share what would make Noah better."))
            OutlinedTextField(name, { name = it }, label = { Text(tr("Name")) }, enabled = !model.busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(email, { email = it }, label = { Text(tr("Email (Optional)")) }, enabled = !model.busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(subject, { subject = it }, label = { Text(tr("Subject")) }, enabled = !model.busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(body, { body = it }, label = { Text(tr("Message")) }, minLines = 5, enabled = !model.busy, modifier = Modifier.fillMaxWidth())
            SectionTitle("Screenshot (optional)")
            NoahButton("Add Screenshot", !model.busy && !loading, secondary = true) { picker.launch("image/*") }
            if (screenshot != null) {
                val preview = remember(screenshot) { android.graphics.BitmapFactory.decodeByteArray(screenshot, 0, screenshot!!.size)?.asImageBitmap() }
                preview?.let { androidx.compose.foundation.Image(it, tr("Selected screenshot"), Modifier.fillMaxWidth().heightIn(max = 180.dp)) }
                TextButton({ screenshot = null }, enabled = !model.busy) { Text(tr("Remove Screenshot")) }
            }
            Text(tr("JPEG or PNG, 5 MB or smaller"), style = MaterialTheme.typography.bodySmall)
            error?.let { Text(tr(it), color = MaterialTheme.colorScheme.error) }
            if (loading || model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            NoahButton("Send Feedback", !model.busy && !loading && cropImage == null && name.isNotBlank() && subject.isNotBlank() && body.isNotBlank()) {
                model.action("Failed to submit feedback. Please try again.") {
                    val payload = try { FeedbackDraft(name, email, subject, body, screenshot).payload(DeviceInfo.current()) { Base64.encodeToString(it, Base64.NO_WRAP) } }
                    catch (validation: IllegalArgumentException) { error = validation.message; return@action }
                    V0SupportTicketClient(model.services.client).submitSupportTicket(payload).value()
                    success = true; name = ""; email = ""; subject = ""; body = ""; screenshot = null
                }
            }
        }
        TextButton({ uriHandler.openUri("https://t.me/blixtwallet/605026") }) { Text(tr("Telegram Support")) }
    }
    cropImage?.let { image ->
        ScreenshotCropDialog(image, onCancel = { cropImage = null }) { crop ->
            cropImage = null
            scope.launch {
                loading = true
                try {
                    screenshot = withContext(Dispatchers.Default) { cropScreenshot(image, crop) }
                } catch (validation: IllegalArgumentException) { error = validation.message }
                catch (_: Exception) { error = "Could not read the selected screenshot." }
                finally { loading = false }
            }
        }
    }
}

@Composable
fun StoryScreen() {
    val context = LocalContext.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var position by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableFloatStateOf(1f) }
    var playing by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        val media = MediaPlayer.create(context, R.raw.noahs_ark_story)
        player = media
        if (media == null) error = "Couldn't play the story. Please try again."
        else duration = media.duration.toFloat().coerceAtLeast(1f)
        onDispose { media?.release(); player = null }
    }
    LaunchedEffect(player) {
        while (isActive) { player?.let { if (!seeking) position = it.currentPosition.toFloat(); playing = it.isPlaying }; delay(500) }
    }
    fun time(ms: Float): String { val seconds = ms.toInt() / 1000; return "%d:%02d".format(seconds / 60, seconds % 60) }
    SettingsColumn {
        SectionTitle("The Story of Noah's Ark")
        Text(tr("Listen to the biblical story that inspired our wallet's name"))
        Slider(position, { seeking = true; position = it }, valueRange = 0f..duration,
            onValueChangeFinished = { player?.seekTo(position.toInt()); seeking = false }, enabled = player != null)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(tr(time(position))); Text(tr(time(duration))) }
        NoahButton(if (playing) "Pause" else "Play", player != null) { player?.let { if (it.isPlaying) it.pause() else it.start(); playing = it.isPlaying } }
        NoahButton("Stop", player != null, secondary = true) { player?.pause(); player?.seekTo(0); position = 0f; playing = false }
        error?.let { Text(tr(it)) }
    }
}

@Composable
fun DebugScreen(model: AppModel) {
    var action by remember { mutableStateOf(DebugAction.ark) }
    var push by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf("") }
    val context = LocalContext.current
    fun execute() { model.action("Action failed. Check your input and connection before trying again.") {
        result = if (push) {
            var token: String? = null
            val outcome = com.noahwallet.services.push.PushRuntime(context, model.services).register { token = it }
            token ?: outcome.name
        }
            else model.session.debug(action, input)
    } }
    SettingsColumn {
        SectionTitle("Select Action")
        Box {
            OutlinedButton({ menu = true }, enabled = !model.busy) { Text(tr(if (push) "Get Push Token" else action.title)) }
            DropdownMenu(menu, { menu = false }) {
                DebugAction.entries.forEach { candidate -> DropdownMenuItem(text = { Text(tr(candidate.title)) }, onClick = { action = candidate; push = false; input = ""; result = null; menu = false }) }
                DropdownMenuItem(text = { Text(tr("Get Push Token")) }, onClick = { push = true; result = null; menu = false })
            }
        }
        if (!push && action.needsInput) OutlinedTextField(input, { input = it }, label = { Text(tr(if (action == DebugAction.offboard) "Bitcoin address" else "VTXO hex or ID")) }, enabled = !model.busy, modifier = Modifier.fillMaxWidth())
        NoahButton("Execute", !model.busy && !model.isWalletSuspended && (push || !action.needsInput || input.isNotBlank())) {
            if (!push && action.destructive) { confirmation = ""; confirming = true } else execute()
        }
        if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        result?.let { CopyRow("Result", it) }
    }
    if (confirming) WalletAlertDialog(onDismissRequest = { confirming = false }, title = { Text(tr(action.title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("This action changes your wallet and may cause loss of funds. Type delete to confirm."))
            OutlinedTextField(confirmation, { confirmation = it }, label = { Text(tr("delete")) })
        } }, dismissButton = { TextButton({ confirming = false }) { Text(tr("Cancel")) } },
        confirmButton = { TextButton({ confirming = false; execute() }, enabled = confirmation.equals("delete", true)) { Text(tr("Confirm")) } })
}
