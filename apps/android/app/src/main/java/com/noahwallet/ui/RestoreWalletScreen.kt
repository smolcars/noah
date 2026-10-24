package com.noahwallet.ui

import com.noahwallet.localization.tr
import android.app.Activity
import androidx.activity.compose.BackHandler
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
import com.noahwallet.ui.design.NoahTokens
import com.noahwallet.ui.onboarding.*
import kotlinx.coroutines.delay

/**
 * FLAG_SECURE, clipboard clearing and clearing on background or removal; configuration changes keep the words in memory.
 * Onboarding holds one across the restore steps through Ready; screens with words add their own for [clear].
 */
@Composable
internal fun RestoreShield(activity: Activity, lock: com.noahwallet.ui.settings.WalletLock, clipboard: RestoreClipboard, clear: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(activity, lifecycle) {
        val release = lock.showSecret(activity.window)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) {
            clipboard.clearIfUnchanged()
            if (!activity.isChangingConfigurations) clear()
        } }
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
        read = { manager.primaryClip?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.text?.toString() },
        clear = {
            if (android.os.Build.VERSION.SDK_INT >= 28) manager.clearPrimaryClip()
            else manager.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
        },
    )
}

/** Onboarding passes its [clipboard], shared with its restore shield so clearing outlasts this screen. */
@Composable
internal fun RestoreWalletScreen(model: AppModel, clipboard: RestoreClipboard = rememberRestoreClipboard(LocalContext.current as Activity),
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
            tr("Your recovery phrase is still securely stored on this device. Use it to restore your wallet from its backup."), large)
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
    val activity = LocalContext.current as Activity
    val palette = LocalPalette.current
    val haptics = rememberHaptics()
    val focus = LocalFocusManager.current
    val input = androidx.lifecycle.viewmodel.compose.viewModel<RecoveryPhrasePresentation>().input
    var revision by remember { mutableIntStateOf(0) }
    var clearGeneration by remember { mutableIntStateOf(0) }
    var fillGeneration by remember { mutableIntStateOf(0) }
    // Only plain remember: Android saved-instance state must never contain recovery words.
    var focused by remember { mutableStateOf<Int?>(null) }
    var checksumFailed by remember { mutableStateOf(false) }
    var leftBad by remember { mutableStateOf(setOf<Int>()) }
    val requesters = remember { List(24) { FocusRequester() } }
    val shake = remember { Animatable(0f) }
    val reduceMotion = LocalReduceMotion.current
    val flow = model.restore
    val blocked = model.busy || !flow.canLeave
    val presentation = androidx.lifecycle.viewmodel.compose.viewModel<OnboardingPresentation>()
    val play = remember { presentation.firstShow("restore") }
    // The word cells are platform EditTexts: clearing Compose focus alone leaves their keyboard up.
    fun hideKeyboard() = activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        ?.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
    fun clear() {
        clipboard.clearIfUnchanged(); focus.clearFocus(); hideKeyboard(); input.clear(); focused = null
        leftBad = emptySet(); checksumFailed = false; revision++; clearGeneration++
    }
    fun leave() { clear(); flow.clearIssue(); model.step = "welcome" }
    BackHandler { if (!blocked) leave() }
    RestoreShield(activity, model.walletLock, clipboard, ::clear)
    // Read the revision so changes to the non-saveable, redacted input redraw the fields.
    val words = remember(revision) { input.words }
    val wordList = RecoveryPhraseInput.wordList
    fun changed() { revision++; checksumFailed = false; flow.clearIssue() }
    fun moveOn(index: Int) { if (index < words.size - 1) requesters[index + 1].requestFocus() else { focus.clearFocus(); hideKeyboard() } }
    fun accept(word: String, index: Int) {
        input.update(word, index); changed(); haptics.selection(); moveOn(index)
    }
    fun fill(text: String): Boolean {
        val count = RecoveryPhraseInput.normalizedWords(text).size
        if (count !in listOf(12, 24)) { input.update(text, 0); changed(); return false }
        focus.clearFocus(); hideKeyboard(); focused = null
        input.update(text, 0); clipboard.accepted(text)
        changed(); fillGeneration++; clearGeneration++; haptics.light()
        leftBad = input.words.indices.filter { input.words[it] !in wordList }.toSet()
        return true
    }
    LaunchedEffect(checksumFailed) { if (checksumFailed && !reduceMotion) shake.shake(listOf(-11f, 9f, -6f, 3f), 420) }
    val firstBad = words.indices.firstOrNull { it in leftBad && words[it].isNotEmpty() && words[it] !in wordList }
    LaunchedEffect(firstBad) { if (firstBad != null) haptics.warning() }
    val complete = words.all { it in wordList } && !input.wrongWordCount
    val keyboard = WindowInsets.isImeVisible && focused != null
    FormLayout(leadingTop = {
        val label = tr("Back")
        IconButton({ if (!blocked) leave() }, Modifier.semantics { contentDescription = label }) { BackIcon(palette.foreground) }
    }, headline = { large ->
        Headline(tr("Welcome back"), tr("Enter your recovery phrase."), large, modifier = Modifier.entrance(0, play))
    }, form = {
        WordCountControl(words.size, enabled = !blocked, modifier = Modifier.padding(top = formGap(22.dp)).entrance(2, play)) { count ->
            if (count == words.size) return@WordCountControl
            focus.clearFocus(); focused = null; input.selectWordCount(count)
            leftBad = emptySet(); changed(); clearGeneration++; haptics.selection()
        }
        key(clearGeneration, words.size) {
            val columns = if (words.size == 12) 2 else 3
            Column(Modifier.padding(top = 18.dp).graphicsLayer { translationX = shake.value * density }.entrance(3, play),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (row in 0 until words.size / columns) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (column in 0 until columns) {
                        val index = row * columns + column
                        WordCell(words[index], index, words.size, bad = index in leftBad && words[index].isNotEmpty() && words[index] !in wordList,
                            focused = focused == index, enabled = !blocked, stagger = fillGeneration, requester = requesters[index],
                            onChange = { raw ->
                                // Programmatic syncs echo the stored word back; only typing changes anything.
                                if (raw == input.words.getOrNull(index)) return@WordCell
                                val incoming = RecoveryPhraseInput.normalizedWords(raw)
                                if (incoming.size > 1) { fill(raw); return@WordCell }
                                input.update(raw, index); leftBad = leftBad - index; changed()
                                // Space or return accepts the first suggestion.
                                if (raw.isNotEmpty() && raw.last().isWhitespace() && incoming.size == 1) {
                                    input.suggestions(index).firstOrNull()?.let { accept(it, index) } ?: moveOn(index)
                                }
                            },
                            onFocus = { now -> if (now) focused = index else {
                                if (focused == index) focused = null
                                if (input.words.getOrNull(index)?.let { it.isNotEmpty() && it !in wordList } == true) leftBad = leftBad + index
                            } },
                            onNext = { input.suggestions(index).firstOrNull()?.let { accept(it, index) } ?: moveOn(index) },
                            modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        val message = when {
            input.wrongWordCount -> tr("Paste exactly 12 or 24 words. Your entries haven't been replaced.")
            checksumFailed -> tr("These words don’t form a valid phrase.")
            firstBad != null -> tr("Check word {number}", firstBad + 1)
            else -> flow.issue?.message?.let { tr(it) }
        }
        Text(message.orEmpty(), Modifier.padding(top = 14.dp).heightIn(min = 20.dp).testTag("restore-error")
            .semantics { liveRegion = LiveRegionMode.Assertive }, style = NoahTokens.Typography.sub, color = palette.failure)
    }, accessory = true, actions = {
        if (keyboard) {
            val index = focused ?: 0
            val suggestions = input.suggestions(index).take(3)
            // The keyboard accessory: suggestions reveal the private prefix, so they expose no semantics.
            Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).horizontalScroll(rememberScrollState()).clearAndSetSemantics { },
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                suggestions.forEachIndexed { position, word -> Chip(word, bold = position == 0) { accept(word, index) } }
            }
        } else Row(Modifier.fillMaxWidth().padding(top = NoahTokens.Space.s), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(tr("Paste"), Modifier.weight(1f), enabled = !blocked, outlined = true, icon = { PasteIcon(palette.foreground) }) {
                clipboard.text()?.let { fill(it) }
            }
            PillButton(tr("Restore"), Modifier.weight(1f).testTag("restore-submit"), enabled = complete && model.canRestoreWallet) {
                focus.clearFocus(); hideKeyboard()
                if (!RecoveryPhraseInput.isValid(input.phrase) || !runCatching { io.github.niteshbalusu11.bark.validateMnemonic(input.phrase) }.getOrDefault(false)) {
                    haptics.error(); checksumFailed = true; return@PillButton
                }
                haptics.medium()
                val phrase = input.phrase
                clear()
                model.holdRestoreCompletion = true
                model.step = "restoring"
                model.restoreWallet(phrase)
            }
        }
    })
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
        if (target != null) Box(Modifier.offset(x = x).width(width).height(34.dp)
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
