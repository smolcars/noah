package com.noahwallet.ui.settings

import android.media.MediaPlayer
import android.util.Base64
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.noahwallet.R
import com.noahwallet.api.generated.client.V0SupportTicketClient
import com.noahwallet.localization.tr
import com.noahwallet.services.server.DeviceInfo
import com.noahwallet.services.server.value
import com.noahwallet.ui.AppModel
import com.noahwallet.ui.design.NoahTokens
import com.noahwallet.ui.home.HomeType
import com.noahwallet.ui.home.tonal
import com.noahwallet.ui.onboarding.LocalPalette
import com.noahwallet.ui.onboarding.rememberHaptics
import kotlinx.coroutines.*

internal const val telegramURL = "https://t.me/blixtwallet/605026"
internal const val sourceURL = "https://github.com/smolcars/noah"
internal const val privacyURL = "https://github.com/smolcars/noah/blob/master/PRIVACY.md"

/**
 * Feedback (prototype `SCR.feedback`): name, email (optional), subject, message and a screenshot (Android's crop stays),
 * with today's limits; "Thank you" once sent, and Telegram.
 */
@Composable
internal fun FeedbackPage(model: AppModel, nav: SettingsNav) {
    val haptics = rememberHaptics()
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var subject by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var success by rememberSaveable { mutableStateOf(false) }
    val attachments = androidx.lifecycle.viewmodel.compose.viewModel<FeedbackAttachments>()
    var screenshot by attachments::screenshot
    val activity = requireNotNull(LocalActivity.current)
    DisposableEffect(activity) { onDispose {
        if (!activity.isChangingConfigurations) { attachments.screenshot = null; attachments.cropImage = null }
    } }
    val work = remember { FeedbackWork() }
    val uri = LocalUriHandler.current
    val picker = rememberScreenshotPicker(attachments, work)
    SettingsFrame(tr("Feedback"), nav.back, hero = success, line = if (success) null else tr("Report a bug or share an idea."), foot = {
        if (success) FootPill(tr("Done")) { nav.pop() }
        else FootPill(tr("Send"), Modifier.testTag("feedback-send"), loading = model.busy,
            enabled = !model.busy && !work.loading && attachments.cropImage == null && name.isNotBlank() && subject.isNotBlank() && body.isNotBlank()) {
            model.action("Failed to submit feedback. Please try again.") {
                val payload = try { FeedbackDraft(name, email, subject, body,
                    screenshot).payload(DeviceInfo.current()) { Base64.encodeToString(it, Base64.NO_WRAP) } }
                catch (validation: IllegalArgumentException) { work.error = validation.message; return@action }
                V0SupportTicketClient(model.services.client).submitSupportTicket(payload).value()
                success = true; haptics.success(); name = ""; email = ""; subject = ""; body = ""; screenshot = null
            }
        }
    }) {
        if (success) {
            FeedbackThanks()
            return@SettingsFrame
        }
        val enabled = !model.busy
        SettingsField(name, { name = it }, tr("Your name"), Modifier.testTag("feedback-name"), label = tr("Name"), enabled = enabled,
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
        SettingsField(email, { email = it }, "you@example.com", label = tr("Email (optional)"), enabled = enabled,
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false))
        SettingsField(subject, { subject = it }, tr("Short summary"), Modifier.testTag("feedback-subject"), label = tr("Subject"), enabled = enabled,
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
        SettingsField(body, { body = it }, tr("What happened, or what would help?"), Modifier.testTag("feedback-message"), label = tr("Message"),
            enabled = enabled,
            lines = 5, keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
        ScreenshotField(screenshot, enabled, canPick = enabled && !work.loading, pick = { picker.launch("image/*") }) { screenshot = null }
        work.error?.let { FinePrint(tr(it), failed = true) }
        FinePrint(tr("Your app version and phone model are attached."))
        SettingsGroup(rows = listOf({ d -> SettingsRow(tr("Chat on Telegram"), SettingsIcons.send, end = RowEnd.External,
            divider = d) { uri.openUri(telegramURL) } }))
    }
    ScreenshotCrop(attachments, work)
}

/** The screenshot's read and crop in progress, and why the last one failed. */
@Stable
private class FeedbackWork {
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

/** Picks an image and opens it for cropping; a file that isn't a JPEG or PNG of 5 MB or less says why. */
@Composable
private fun rememberScreenshotPicker(attachments: FeedbackAttachments, work: FeedbackWork) = run {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { picked ->
        if (picked != null) scope.launch {
            work.loading = true; work.error = null
            try {
                attachments.cropImage = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(picked)?.use(FeedbackDraft::readScreenshot) ?: error("""Unreadable image""")
                    decodeScreenshot(bytes)
                }
            } catch (validation: IllegalArgumentException) { work.error = validation.message }
            catch (_: Exception) { work.error = "Couldn't read the screenshot. Please try again." }
            finally { work.loading = false }
        }
    }
}

/** The picked image's crop; cropping it makes the attached screenshot. */
@Composable
private fun ScreenshotCrop(attachments: FeedbackAttachments, work: FeedbackWork) {
    val scope = rememberCoroutineScope()
    attachments.cropImage?.let { image ->
        ScreenshotCropDialog(image, onCancel = { attachments.cropImage = null }) { crop ->
            attachments.cropImage = null
            scope.launch {
                work.loading = true
                try { attachments.screenshot = withContext(Dispatchers.Default) { cropScreenshot(image, crop) } }
                catch (validation: IllegalArgumentException) { work.error = validation.message }
                catch (_: Exception) { work.error = "Could not read the selected screenshot." }
                finally { work.loading = false }
            }
        }
    }
}

@Composable
private fun FeedbackThanks() {
    val palette = LocalPalette.current
    Column(Modifier.padding(top = 6.dp)) {
        StatusMark(MarkKind.ok)
        Text(tr("Thank you"), Modifier.padding(top = 16.dp), style = SettingsType.hero, color = palette.foreground)
        Text(tr("It’s with the Noah team."), Modifier.padding(top = 3.dp), style = HomeType.line, color = palette.muted)
    }
}

/** The optional screenshot: its preview and Remove, or the dashed Add field. */
@Composable
private fun ScreenshotField(shot: ByteArray?, enabled: Boolean, canPick: Boolean, pick: () -> Unit, remove: () -> Unit) {
    val palette = LocalPalette.current
    FieldLabel(tr("Screenshot (optional)"))
    if (shot != null) {
        val preview = remember(shot) { android.graphics.BitmapFactory.decodeByteArray(shot, 0, shot.size)?.asImageBitmap() }
        preview?.let { Image(it, tr("Selected screenshot"), Modifier.padding(top = 8.dp).heightIn(max = 180.dp).clip(RoundedCornerShape(18.dp))) }
        SettingsLink(tr("Remove screenshot"), enabled = enabled, onClick = remove)
    } else Row(Modifier.padding(top = 8.dp).fillMaxWidth().height(84.dp).clip(RoundedCornerShape(18.dp)).border(1.5.dp, palette.hairline,
        RoundedCornerShape(18.dp))
        .clickable(enabled = canPick, role = Role.Button, onClick = pick).testTag("feedback-screenshot"),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(SettingsIcons.image), null, Modifier.size(20.dp), tint = palette.muted)
        Spacer(Modifier.width(8.dp))
        Text(tr("Add screenshot"), style = HomeType.line.copy(fontWeight = FontWeight.Medium), color = palette.muted)
    }
    Text(tr("JPEG or PNG, 5 MB or smaller"), Modifier.padding(top = 6.dp), style = HomeType.small, color = palette.muted)
}

/**
 * About Noah (prototype `SCR.about`): the wordmark and version (five taps turn debug on here too), the Noah's Ark story
 * player, Telegram, the source code and the privacy policy.
 */
@Composable
internal fun AboutPage(model: AppModel, nav: SettingsNav, live: SettingsLive) {
    val palette = LocalPalette.current
    val uri = LocalUriHandler.current
    val tap = rememberDebugTaps(model, live)
    SettingsFrame(tr("About Noah"), nav.back, hero = true) {
        Column(Modifier.padding(top = 6.dp)) {
            Text("Noah", Modifier.padding(top = 18.dp), style = TextStyle(fontFamily = NoahTokens.Display, fontWeight = FontWeight.SemiBold, fontSize = 64.sp,
                lineHeight = 64.sp, letterSpacing = (-0.045).em), color = palette.foreground)
            Text(tr("Version {version}", versionText()) + if (live.debug) " · " + tr("debug") else "", Modifier.padding(top = 10.dp)
                .clickable(role = Role.Button, onClickLabel = tr("Version")) { tap() }.testTag("about-version"),
                style = TextStyle(fontFamily = NoahTokens.Display, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp), color = palette.muted)
        }
        StoryPlayer()
        FinePrint(tr("The story that gave Noah its name."))
        SettingsGroup(rows = listOf(
            { d -> SettingsRow(tr("Telegram"), SettingsIcons.telegram, end = RowEnd.External, divider = d) { uri.openUri(telegramURL) } },
            { d -> SettingsRow(tr("Source code"), SettingsIcons.github, end = RowEnd.External, divider = d) { uri.openUri(sourceURL) } },
            { d -> SettingsRow(tr("Privacy policy"), SettingsIcons.shield, end = RowEnd.External, divider = d,
                modifier = Modifier.testTag("about-privacy")) { uri.openUri(privacyURL) } },
        ))
    }
}

/** The Noah's Ark story (today's player, restyled as the prototype's card): play or pause, a scrub bar and the times. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoryPlayer() {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val haptics = rememberHaptics()
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
        while (isActive) {
            player?.let {
                if (!seeking) position = it.currentPosition.toFloat()
                playing = it.isPlaying
            }
            delay(500)
        }
    }
    fun time(ms: Float): String { val seconds = ms.toInt() / 1000; return "%d:%02d".format(seconds / 60, seconds % 60) }
    Row(Modifier.padding(top = 22.dp).fillMaxWidth().tonal(RoundedCornerShape(22.dp), palette.soft).padding(start = 14.dp, end = 16.dp, top = 14.dp,
        bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val label = if (playing) tr("Pause") else tr("Play")
        Box(Modifier.size(44.dp).background(palette.foreground, CircleShape).clip(CircleShape)
            .clickable(enabled = player != null, role = Role.Button, onClickLabel = label) {
                haptics.light(); player?.let { if (it.isPlaying) it.pause() else it.start(); playing = it.isPlaying }
            }.semantics { contentDescription = label }.testTag("about-story-play"), contentAlignment = Alignment.Center) {
            Icon(painterResource(if (playing) SettingsIcons.pause else SettingsIcons.play), null, Modifier.size(18.dp), tint = palette.background)
        }
        Column(Modifier.weight(1f)) {
            Text(tr("The story of Noah’s Ark"), style = HomeType.strong.copy(fontWeight = FontWeight.Medium), color = palette.foreground)
            // The prototype's 4 dp scrub bar, still draggable (today's seek).
            val colors = SliderDefaults.colors(thumbColor = palette.foreground, activeTrackColor = palette.foreground, inactiveTrackColor = palette.hairline,
                disabledActiveTrackColor = palette.hairline, disabledInactiveTrackColor = palette.hairline)
            Slider(position, { seeking = true; position = it }, Modifier.padding(top = 4.dp).height(16.dp).testTag("about-story-scrub"),
                valueRange = 0f..duration,
                onValueChangeFinished = { player?.seekTo(position.toInt()); seeking = false }, enabled = player != null, colors = colors,
                thumb = { Box(Modifier.size(10.dp).background(if (player != null) palette.foreground else palette.hairline, CircleShape)) },
                track = { state -> SliderDefaults.Track(state, Modifier.height(4.dp), colors = colors, enabled = player != null,
                    drawStopIndicator = null, thumbTrackGapSize = 0.dp, trackInsideCornerSize = 2.dp) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(time(position), style = SettingsType.tick.copy(fontSize = 11.sp), color = palette.muted)
                Text(time(duration), style = SettingsType.tick.copy(fontSize = 11.sp), color = palette.muted)
            }
            error?.let { Text(tr(it), style = HomeType.small, color = palette.failure) }
        }
    }
}

/** Large image drafts stay in memory rather than Android's saved-instance Bundle. */
internal class FeedbackAttachments : androidx.lifecycle.ViewModel() {
    var screenshot by mutableStateOf<ByteArray?>(null)
    var cropImage by mutableStateOf<android.graphics.Bitmap?>(null)
}
