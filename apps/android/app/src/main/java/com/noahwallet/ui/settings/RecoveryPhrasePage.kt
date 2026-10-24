package com.noahwallet.ui.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.noahwallet.localization.tr
import com.noahwallet.ui.AppModel
import com.noahwallet.ui.design.NoahTokens
import com.noahwallet.ui.onboarding.LocalPalette
import com.noahwallet.ui.onboarding.LocalReduceMotion
import com.noahwallet.ui.onboarding.rememberHaptics
import com.noahwallet.ui.send.SendPill
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The words after authentication, in memory only: a fold or rotation recreates the screen without asking again, but
 * they're never in saved state, and they clear when Noah leaves the screen (a new lock generation) or the page closes.
 */
internal class RevealedPhrase : ViewModel() {
    var words by mutableStateOf<List<String>>(emptyList()); private set
    /** The lock generation the words were shown in; another generation means Noah left the screen since. */
    var generation = -1; private set
    var shownAt = 0L; private set
    /** When our copied clip clears (elapsedRealtime); kept here so a fold or rotation reschedules it rather than dropping it. */
    var clipDeadline by mutableStateOf<Long?>(null)
    /** Whether the timer for [deadline] may clear the clip: not once a later copy set a new one (the label is the same). */
    fun expireClip(deadline: Long): Boolean = (clipDeadline == deadline).also { if (it) clipDeadline = null }
    fun show(words: List<String>, generation: Int) { this.words = words; this.generation = generation; shownAt = SystemClock.elapsedRealtime() }
    fun clear() { words = emptyList(); generation = -1 }
    override fun onCleared() { clear() }
}

/** The 2-minute clipboard rule (settings.md, Decision 9): a sensitive clip, cleared while Noah is alive if it's still ours. */
internal object PhraseClip {
    const val clearAfterMillis = 120_000L
    fun clip(label: String, phrase: String): ClipData = ClipData.newPlainText(label, phrase).apply {
        // ClipDescription.EXTRA_IS_SENSITIVE (API 33), set by name so older releases carry it too.
        description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    }
    fun ours(clipboard: ClipboardManager, label: String) = clipboard.primaryClipDescription?.label == label
    /** How long until [deadline]; a recreated page waits only what's left. */
    fun remaining(deadline: Long, now: Long) = (deadline - now).coerceAtLeast(0L)
}

/**
 * Recovery phrase (prototype `SCR.recovery`): until authentication the grid shows bars and Show — the words aren't in the
 * screen. Authentication is always required ("No screen lock" asks first instead). Copy marks the clip sensitive and
 * clears it after 2 minutes; "I wrote it down" clears Home's nudge. Screenshots are blocked here. [fixture] shows sample
 * words for the screenshot gallery only.
 */
@Composable
internal fun RecoveryPhrasePage(model: AppModel, nav: SettingsNav, fixture: List<String>? = null) {
    val context = LocalContext.current
    val activity = context as? Activity
    val haptics = rememberHaptics()
    val toasts = LocalSettingsToasts.current
    val holder = androidx.lifecycle.viewmodel.compose.viewModel<RevealedPhrase>()
    val clipboard = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val label = rememberSaveable { "noah-recovery-${java.util.UUID.randomUUID()}" }
    val clear = remember(label) { Runnable {
        if (PhraseClip.ours(clipboard, label)) {
            if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    } }
    if (fixture == null && activity != null) DisposableEffect(activity) {
        val release = model.walletLock.showSecret(activity.window)
        onDispose {
            // A fold or rotation hands the count to the recreated page, so its new window is never unshielded.
            model.walletLock.releaseSecret(activity.isChangingConfigurations, release)
            // Leaving the page (not a fold) forgets the words and our clip.
            if (!activity.isChangingConfigurations) { clear.run(); holder.clipDeadline = null; holder.clear() }
        }
    }
    val generation = model.walletLock.generation
    LaunchedEffect(generation) { if (holder.generation != generation) { holder.clear(); clear.run(); holder.clipDeadline = null } }
    LaunchedEffect(holder.clipDeadline) {
        val deadline = holder.clipDeadline ?: return@LaunchedEffect
        delay(PhraseClip.remaining(deadline, SystemClock.elapsedRealtime()))
        if (holder.expireClip(deadline)) clear.run()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true) holder.clear()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val scope = rememberCoroutineScope()
    var warning by remember { mutableStateOf<Int?>(null) }
    var prompted by rememberSaveable { mutableStateOf(false) }
    val words = fixture ?: holder.words
    suspend fun reveal() {
        val fragment = activity as? androidx.fragment.app.FragmentActivity ?: return
        val started = model.walletLock.generation
        if (WalletLock.hasNoScreenLock(fragment)) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) warning = started
        } else if (model.walletLock.authenticate(fragment, "View your seed phrase")) {
            val loaded = model.mnemonic()
            if (started == model.walletLock.generation && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { holder.show(loaded, started); haptics.success() }
        }
    }
    // Opening the page asks once, as today; after that, Show asks.
    LaunchedEffect(Unit) {
        if (fixture != null || prompted || holder.words.isNotEmpty()) return@LaunchedEffect
        prompted = true; delay(420)
        // The page is resumed once its push settles.
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        reveal()
    }
    val copied = tr("Copied · clears in 2 minutes")
    val backedUp = tr("Backed up")
    SettingsFrame(tr("Recovery phrase"), nav.back, line = if (words.isEmpty()) tr("The words that restore your wallet.") else tr("{n} words that restore your wallet.", words.size), foot = if (words.isEmpty()) null else ({
        FootPill(tr("Copy"), Modifier.testTag("recovery-copy"), outlined = true, icon = SettingsIcons.copy) {
            clipboard.setPrimaryClip(PhraseClip.clip(label, words.joinToString(" ")))
            holder.clipDeadline = SystemClock.elapsedRealtime() + PhraseClip.clearAfterMillis
            haptics.success(); toasts.show(copied)
        }
        if (!model.recoveryPhraseBackedUp) FootPill(tr("I wrote it down"), Modifier.testTag("recovery-saved"), enabled = !model.busy) {
            nav.save(model, "Couldn't save your preference.") { model.confirmRecoveryPhraseBackup(); haptics.success(); toasts.show(backedUp); nav.pop() }
        }
    })) {
        PhraseGrid(words, holder.shownAt, authenticating = model.walletLock.authenticating) { scope.launch { reveal() } }
        model.walletLock.error?.let { FinePrint(tr(it), failed = true) }
        Warning(tr("Never share them. Anyone with these words can take your bitcoin."))
    }
    warning?.let { started ->
        SettingsSheet({ warning = null }, tr("No screen lock"), tr("Anyone with this phone could see your phrase. Set a screen lock in Settings to protect it.")) { close ->
            SheetActions(tr("Cancel"), tr("Show anyway"), onCancel = close, onConfirm = {
                warning = null
                scope.launch {
                    val fragment = activity as? androidx.fragment.app.FragmentActivity ?: return@launch
                    if (started != model.walletLock.generation || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                    if (WalletLock.hasNoScreenLock(fragment)) {
                        val loaded = model.mnemonic()
                        if (started == model.walletLock.generation && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) holder.show(loaded, started)
                    } else reveal()
                }
            }, confirmTag = "recovery-show-anyway")
        }
    }
}

/** Two columns of twelve: bars and Show until authentication, then the words, un-blurring in sequence (soft spring). */
@Composable
private fun PhraseGrid(words: List<String>, shownAt: Long, authenticating: Boolean, show: () -> Unit) {
    val palette = LocalPalette.current
    val reduceMotion = LocalReduceMotion.current
    val bars = listOf(52, 64, 40, 58, 46, 70, 60, 44, 56, 50, 66, 48)
    val fresh = SystemClock.elapsedRealtime() - shownAt < 1_000
    Box(Modifier.padding(top = 22.dp).fillMaxWidth().testTag("recovery-grid")) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in 0 until 6) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (column in 0 until 2) {
                    val index = row * 2 + column
                    val word = words.getOrNull(index)
                    Row(Modifier.weight(1f).height(46.dp).background(palette.soft, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp)
                        .then(if (word == null) Modifier.clearAndSetSemantics { } else Modifier.semantics(mergeDescendants = true) { }),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${index + 1}", Modifier.width(17.dp), style = SettingsType.index, color = palette.muted, textAlign = TextAlign.End)
                        if (word == null) Box(Modifier.fillMaxWidth(bars[index] / 100f).height(10.dp).background(palette.hairline, RoundedCornerShape(5.dp)))
                        else {
                            val progress = remember(word, index) { Animatable(if (fresh && !reduceMotion) 0f else 1f) }
                            LaunchedEffect(word, index) { if (progress.value < 1f) { delay(index * 35L); progress.animateTo(1f, NoahTokens.Motion.soft()) } }
                            val blur = 8f * (1 - progress.value)
                            Text(word, Modifier.testTag("recovery-word-${index + 1}").graphicsLayer { alpha = progress.value; translationY = 4 * (1 - progress.value) * density }
                                .then(if (Build.VERSION.SDK_INT >= 31 && blur > .05f) Modifier.blur(blur.dp, BlurredEdgeTreatment.Unbounded) else Modifier),
                                style = SettingsType.word, color = palette.foreground, maxLines = 1)
                        }
                    }
                }
            }
        }
        if (words.isEmpty()) {
            val description = tr("Show recovery phrase")
            SendPill(tr("Show"), Modifier.align(Alignment.Center).background(palette.background, androidx.compose.foundation.shape.CircleShape)
                .testTag("recovery-show").semantics { contentDescription = description },
                outlined = true, icon = SettingsIcons.finger, loading = authenticating) { show() }
        }
    }
}

/** The lock cover (prototype `SCR.locked`): the lock, "Noah is locked" and Unlock, which asks for the biometric prompt. */
@Composable
internal fun LockCover(error: String?, authenticating: Boolean, unlock: () -> Unit) = com.noahwallet.ui.onboarding.OnboardingTheme {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(64.dp).background(palette.soft, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(SettingsIcons.lock), null, Modifier.size(28.dp), tint = palette.foreground)
            }
            Text(tr("Noah is locked"), Modifier.padding(top = 24.dp), style = SettingsType.hero, color = palette.foreground)
            Text(tr("Unlock to see your wallet."), Modifier.padding(top = 6.dp), style = com.noahwallet.ui.home.HomeType.line, color = palette.muted)
            error?.let { Text(tr(it), Modifier.padding(top = 10.dp), style = com.noahwallet.ui.home.HomeType.line, color = palette.failure) }
        }
        SendPill(tr("Unlock"), Modifier.fillMaxWidth().padding(bottom = 28.dp).testTag("lock-unlock"), height = 56.dp, icon = SettingsIcons.finger,
            loading = authenticating) { unlock() }
    }
}
