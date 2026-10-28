package com.noahwallet.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.noahwallet.localization.tr
import com.noahwallet.ui.design.NoahTokens
import com.noahwallet.ui.onboarding.*
import kotlinx.coroutines.delay

/**
 * FLAG_SECURE, clipboard clearing on background or removal, and [clear] on removal. The words stay in memory in the
 * background (a phrase read from another app a few words at a time), where FLAG_SECURE keeps them out of Recents, and
 * through configuration changes. Onboarding holds one across the restore steps through Ready; screens with words add
 * their own for [clear].
 */
@Composable
internal fun RestoreShield(activity: Activity, lock: com.noahwallet.ui.settings.WalletLock, clipboard: RestoreClipboard, clear: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(activity, lifecycle) {
        val release = lock.showSecret(activity.window)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) clipboard.clearIfUnchanged() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            clipboard.clearIfUnchanged()
            if (!activity.isChangingConfigurations) clear()
            lock.releaseSecret(activity.isChangingConfigurations, release)
        }
    }
}

@Composable
internal fun rememberRestoreClipboard(activity: Activity) = remember {
    val manager = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    RestoreClipboard(
        // Every item's text, one per line: a phrase split across items is never cut short.
        read = { manager.primaryClip?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).text?.toString() }.joinToString("\n") } },
        clear = {
            if (android.os.Build.VERSION.SDK_INT >= 28) manager.clearPrimaryClip()
            else manager.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
        },
    )
}

/** Onboarding passes its [clipboard], shared with its restore shield so clearing outlasts this screen. */
@Composable
internal fun RestoreWalletScreen(model: AppModel, clipboard: RestoreClipboard = rememberRestoreClipboard(requireNotNull(LocalActivity.current)),
    useStoredSeed: Boolean = false) {
    if (useStoredSeed) StoredSeedRestoreScreen(model) else PhraseRestoreScreen(model, clipboard)
}

/**
 * Reinstall recovery: the phrase is still stored, so there is nothing to type (iOS `StoredSeedRestoreScreen`). Restore
 * Wallet moves to Restoring, which brings a failed restore back here with its error. Onboarding's restore shield covers it.
 */
@Composable
private fun StoredSeedRestoreScreen(model: AppModel) {
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val flow = model.restore
    val blocked = model.busy || !flow.canLeave
    BackHandler { }
    FormLayout(headline = { large ->
        Headline(tr("Recovery phrase found"),
            tr("Your recovery phrase is still securely stored on this device. Use it to restore your wallet from its backup."), large = large)
    }, form = {}, actions = {
        // As on iOS, the error sits just above the button.
        Column(verticalArrangement = Arrangement.spacedBy(NoahTokens.Space.l)) {
            flow.issue?.let {
                Text(tr(it.message), Modifier.testTag("restore-error").semantics { liveRegion = LiveRegionMode.Assertive },
                    style = NoahTokens.Typography.sub, color = palette.failure)
            }
            if (model.isWalletSuspended) {
                Text(tr("Your wallet is suspended. Resume recovery to restore its backup, or delete this wallet from your device."),
                    style = NoahTokens.Typography.sub, color = palette.muted)
                PillButton(tr("Resume Recovery"), Modifier.fillMaxWidth(), enabled = !blocked, outlined = true) {
                    model.action("Couldn't resume recovery. Restart Noah and try again.") { model.resumeRestoreRecovery() }
                }
            }
            if (flow.issue?.requiresRestart != true) PillButton(tr("Restore Wallet"), Modifier.fillMaxWidth(), enabled = model.canRestoreWallet) {
                // Restoring has no way back until a restore fails, so never enter it for a request restoreWallet would drop.
                if (!model.canRestoreWallet) return@PillButton
                haptics.medium()
                flow.clearIssue() // Restoring would otherwise read the last attempt's error and send us straight back.
                model.holdRestoreCompletion = true
                model.step = "restoring"
                model.restoreWallet(null)
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DeleteWalletFallback(model, palette.failure, NoahTokens.Typography.body) }
        }
    })
}

/**
 * Onboarding restore: "Welcome back", a 12/24 control, the word grid with BIP-39 suggestions, Paste and
 * Restore. Words stay masked password fields, out of saved state, logs and screenshots (FLAG_SECURE).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhraseRestoreScreen(model: AppModel, clipboard: RestoreClipboard) {
    val activity = requireNotNull(LocalActivity.current)
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val focus = LocalFocusManager.current
    val input = androidx.lifecycle.viewmodel.compose.viewModel<RecoveryPhrasePresentation>().input
    val shake = remember { Animatable(0f) }
    val reduceMotion = LocalReduceMotion.current
    val flow = model.restore
    // Only plain remember: Android saved-instance state must never contain recovery words.
    val entry = remember(input, clipboard, flow) {
        // The word cells are platform EditTexts: clearing Compose focus alone leaves their keyboard up.
        PhraseEntry(input, clipboard, flow, haptics, focus) {
            activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
    }
    val blocked = model.busy || !flow.canLeave
    val presentation = androidx.lifecycle.viewmodel.compose.viewModel<OnboardingPresentation>()
    val play = remember { presentation.firstShow("restore") }
    fun leave() { entry.clear(); flow.clearIssue(); model.step = "welcome" }
    BackHandler { if (!blocked) leave() }
    RestoreShield(activity, model.walletLock, clipboard, entry::clear)
    // Read the revision so changes to the non-saveable, redacted input redraw the fields.
    val words = remember(entry.revision) { input.words }
    LaunchedEffect(entry.checksumFailed) { if (entry.checksumFailed && !reduceMotion) shake.shake(listOf(-11f, 9f, -6f, 3f), 420) }
    val firstBad = entry.firstBad(words)
    LaunchedEffect(firstBad) { if (firstBad != null) haptics.warning() }
    val complete = words.all { it in RecoveryPhraseInput.wordList } && !input.wrongWordCount
    val keyboard = WindowInsets.isImeVisible && entry.focused != null
    FormLayout(leadingTop = {
        val label = tr("Back")
        IconButton({ if (!blocked) leave() }, Modifier.semantics { contentDescription = label }) { BackIcon(palette.foreground) }
    }, headline = { large ->
        Headline(tr("Welcome back"), tr("Enter your recovery phrase."), large = large, modifier = Modifier.entrance(0, play))
    }, form = {
        WordCountControl(words.size, enabled = !blocked, modifier = Modifier.padding(top = formGap(22.dp)).entrance(2, play), select = entry::selectCount)
        key(entry.clearGeneration, words.size) {
            WordGrid(entry, words, !blocked, Modifier.padding(top = 18.dp).graphicsLayer { translationX = shake.value * density }.entrance(3, play))
        }
        Text(restoreMessage(entry, firstBad, flow).orEmpty(), Modifier.padding(top = 14.dp).heightIn(min = 20.dp).testTag("restore-error")
            .semantics { liveRegion = LiveRegionMode.Assertive }, style = NoahTokens.Typography.sub, color = palette.failure)
    }, accessory = true, actions = {
        if (keyboard) Suggestions(entry, entry.focused ?: 0)
        else Row(Modifier.fillMaxWidth().padding(top = NoahTokens.Space.s), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(tr("Paste"), Modifier.weight(1f), enabled = !blocked, outlined = true, icon = { PasteIcon(palette.foreground) }, onClick = entry::paste)
            PillButton(tr("Restore"), Modifier.weight(1f).testTag("restore-submit"), enabled = complete && model.canRestoreWallet) {
                entry.validPhrase()?.let { phrase ->
                    model.holdRestoreCompletion = true
                    model.step = "restoring"
                    model.restoreWallet(phrase)
                }
            }
        }
    })
}

/** The phrase form's state and edits. Held with plain remember, so saved-instance state never holds recovery words. */
@Stable
private class PhraseEntry(
    private val input: RecoveryPhraseInput, private val clipboard: RestoreClipboard, private val flow: RestoreFlow, private val haptics: Haptics,
    private val focus: androidx.compose.ui.focus.FocusManager, private val hideKeyboard: () -> Unit,
) {
    val requesters = List(24) { FocusRequester() }
    var revision by mutableIntStateOf(0); private set
    var clearGeneration by mutableIntStateOf(0); private set
    var fillGeneration by mutableIntStateOf(0); private set
    var focused by mutableStateOf<Int?>(null); private set
    var checksumFailed by mutableStateOf(false); private set
    /** Paste found no text, or not 12 or 24 words. */
    var pasteRejected by mutableStateOf(false); private set
    var leftBad by mutableStateOf(setOf<Int>()); private set
    private val wordList get() = RecoveryPhraseInput.wordList
    private fun release() { focus.clearFocus(); hideKeyboard() }

    val wrongWordCount get() = input.wrongWordCount
    fun suggestions(index: Int) = input.suggestions(index)
    fun bad(index: Int, words: List<String>) = index in leftBad && words[index].isNotEmpty() && words[index] !in wordList
    fun firstBad(words: List<String>) = words.indices.firstOrNull { bad(it, words) }

    fun clear() {
        clipboard.clearIfUnchanged(); release(); input.clear(); focused = null
        leftBad = emptySet(); checksumFailed = false; pasteRejected = false; revision++; clearGeneration++
    }
    private fun changed() { revision++; checksumFailed = false; pasteRejected = false; flow.clearIssue() }
    private fun moveOn(index: Int) { if (index < input.words.size - 1) requesters[index + 1].requestFocus() else release() }
    fun accept(word: String, index: Int) {
        input.update(word, index); changed(); haptics.selection(); moveOn(index)
    }
    /** Space, return or Next accepts the first suggestion, or moves on without one. */
    fun next(index: Int) { input.suggestions(index).firstOrNull()?.let { accept(it, index) } ?: moveOn(index) }
    private fun fill(text: String) {
        val count = RecoveryPhraseInput.normalizedWords(text).size
        if (count !in listOf(12, 24)) { input.update(text, 0); changed(); return }
        release(); focused = null
        input.update(text, 0); clipboard.accepted(text)
        changed(); fillGeneration++; clearGeneration++; haptics.light()
        leftBad = input.words.indices.filter { input.words[it] !in wordList }.toSet()
    }
    fun selectCount(count: Int) {
        if (count == input.words.size) return
        focus.clearFocus(); focused = null; input.selectWordCount(count)
        leftBad = emptySet(); changed(); clearGeneration++; haptics.selection()
    }
    fun typed(index: Int, raw: String) {
        // Programmatic syncs echo the stored word back; only typing changes anything.
        if (raw == input.words.getOrNull(index)) return
        val incoming = RecoveryPhraseInput.normalizedWords(raw)
        if (incoming.size > 1) { fill(raw); return }
        input.update(raw, index); leftBad = leftBad - index; changed()
        // Space or return accepts the first suggestion.
        if (raw.isNotEmpty() && raw.last().isWhitespace() && incoming.size == 1) next(index)
    }
    fun focusChanged(index: Int, now: Boolean) {
        if (now) { focused = index; return }
        if (focused == index) focused = null
        if (input.words.getOrNull(index)?.let { it.isNotEmpty() && it !in wordList } == true) leftBad = leftBad + index
    }
    fun paste() {
        val text = clipboard.text()
        if (text == null || RecoveryPhraseInput.normalizedWords(text).size !in listOf(12, 24)) {
            pasteRejected = true; haptics.warning()
        } else fill(text)
    }
    /** The phrase to restore, cleared from the form; null (with the shake) when its checksum fails. */
    fun validPhrase(): String? {
        release()
        if (!RecoveryPhraseInput.isValid(input.phrase) ||
            !runCatching { io.github.niteshbalusu11.bark.validateMnemonic(input.phrase) }.getOrDefault(false)) {
            haptics.error(); checksumFailed = true; return null
        }
        haptics.medium()
        val phrase = input.phrase
        clear()
        return phrase
    }
}

/** The word cells, two or three a row. */
@Composable
private fun WordGrid(entry: PhraseEntry, words: List<String>, enabled: Boolean, modifier: Modifier) {
    val columns = if (words.size == 12) 2 else 3
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (row in 0 until words.size / columns) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            for (column in 0 until columns) {
                val index = row * columns + column
                WordCell(words[index], index, words.size, bad = entry.bad(index, words), focused = entry.focused == index, enabled = enabled,
                    stagger = entry.fillGeneration, requester = entry.requesters[index], onChange = { entry.typed(index, it) },
                    onFocus = { entry.focusChanged(index, it) }, onNext = { entry.next(index) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun restoreMessage(entry: PhraseEntry, firstBad: Int?, flow: RestoreFlow): String? = when {
    entry.wrongWordCount || entry.pasteRejected -> tr("Paste exactly 12 or 24 words. Your entries haven't been replaced.")
    entry.checksumFailed -> tr("These words don’t form a valid phrase.")
    firstBad != null -> tr("Check word {number}", firstBad + 1)
    else -> flow.issue?.message?.let { tr(it) }
}

/** The keyboard accessory: suggestions reveal the private prefix, so they expose no semantics. */
@Composable
private fun Suggestions(entry: PhraseEntry, index: Int) {
    val suggestions = entry.suggestions(index).take(3)
    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).horizontalScroll(rememberScrollState()).clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        suggestions.forEachIndexed { position, word -> Chip(word, bold = position == 0) { entry.accept(word, index) } }
    }
}

/** 12 / 24 sliding-pill control. */
@Composable
private fun WordCountControl(count: Int, enabled: Boolean, modifier: Modifier, select: (Int) -> Unit) {
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val bounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val target = bounds[count]
    val x by animateDpAsState(with(density) { (target?.first ?: 0f).toDp() }, NoahTokens.Motion.snappy(), label = "pill-x")
    val width by animateDpAsState(with(density) { (target?.second ?: 0f).toDp() }, NoahTokens.Motion.snappy(), label = "pill-width")
    Box(modifier.background(palette.soft, CircleShape).padding(3.dp)) {
        if (target != null) Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(x.roundToPx(), 0) }.width(width).height(34.dp)
            .background(if (dark) androidx.compose.ui.graphics.Color(0xFF3A3A3C) else palette.background, CircleShape))
        Row(Modifier.selectableGroup()) {
            for (option in listOf(12, 24)) {
                val selected = option == count
                Box(Modifier.heightIn(min = NoahTokens.Size.hitTarget).onGloballyPositioned {
                    bounds[option] = it.positionInParent().x to it.size.width.toFloat()
                }.selectable(selected, enabled = enabled, role = Role.Tab) { select(option) }.padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center) {
                    Text(if (option == 12) tr("12 words") else tr("24 words"), Modifier.testTag("restore-count-$option"),
                        style = NoahTokens.Typography.sub.copy(fontWeight = FontWeight.SemiBold),
                        color = if (selected) palette.foreground else palette.muted)
                }
            }
        }
    }
}

private fun androidx.compose.ui.layout.LayoutCoordinates.positionInParent() =
    parentLayoutCoordinates?.localPositionOf(this, androidx.compose.ui.geometry.Offset.Zero) ?: androidx.compose.ui.geometry.Offset.Zero

@Composable
private fun WordCell(word: String, index: Int, count: Int, bad: Boolean, focused: Boolean, enabled: Boolean, stagger: Int,
    requester: FocusRequester, onChange: (String) -> Unit, onFocus: (Boolean) -> Unit, onNext: () -> Unit, modifier: Modifier) {
    val palette = LocalPalette.current
    val reduceMotion = LocalReduceMotion.current
    val appear = remember(stagger) { Animatable(if (stagger == 0) 1f else 0f) }
    LaunchedEffect(stagger) {
        if (stagger == 0) return@LaunchedEffect
        delay(index * 28L)
        if (reduceMotion) appear.animateTo(1f, tween(NoahTokens.Motion.REDUCE_MOTION_FADE_MS)) else appear.animateTo(1f, NoahTokens.Motion.snappy())
    }
    Row(modifier.heightIn(min = 44.dp).graphicsLayer {
        alpha = appear.value.coerceIn(0f, 1f)
        if (!reduceMotion) translationY = (1 - appear.value) * 8 * density
    }, verticalAlignment = Alignment.CenterVertically) {
        Text("${index + 1}", Modifier.widthIn(min = 18.dp).clearAndSetSemantics { }, textAlign = TextAlign.End,
            style = TextStyleNumber, color = palette.muted, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).heightIn(min = 44.dp), contentAlignment = Alignment.CenterStart) {
            RecoveryWordField(word, index, onChange, onFocus = onFocus, onNext = onNext, wordCount = count,
                textColor = if (bad) palette.failure else palette.foreground, cursorColor = palette.orange,
                textSize = if (count == 24) 16f else 17f, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 34.dp).focusRequester(requester).testTag("restore-word-${index + 1}"))
            Underline(focused, failed = bad, modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 5.dp), thickness = 1.dp)
        }
    }
}

private val TextStyleNumber = NoahTokens.Typography.caption.copy(fontFamily = NoahTokens.Display, fontWeight = FontWeight.Medium,
    fontSize = 13.sp, fontFeatureSettings = "tnum")

/** Recovery words survive configuration recreation only in memory, never saved-instance state. */
internal class RecoveryPhrasePresentation : androidx.lifecycle.ViewModel() {
    val input = RecoveryPhraseInput()
    override fun onCleared() { input.clear() }
}
