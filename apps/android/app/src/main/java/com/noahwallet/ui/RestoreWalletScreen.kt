package com.noahwallet.ui

import com.noahwallet.localization.tr
import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun RestoreWalletScreen(model: AppModel, useStoredSeed: Boolean = false) {
    val activity = LocalContext.current as Activity
    val clipboard = remember {
        val manager = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        RestoreClipboard(
            read = { manager.primaryClip?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.text?.toString() },
            clear = {
                if (android.os.Build.VERSION.SDK_INT >= 28) manager.clearPrimaryClip()
                else manager.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
            },
        )
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val focus = LocalFocusManager.current
    val input = androidx.lifecycle.viewmodel.compose.viewModel<RecoveryPhrasePresentation>().input
    var revision by remember { mutableIntStateOf(0) }
    var clearGeneration by remember { mutableIntStateOf(0) }
    // Only plain remember: Android saved-instance state must never contain recovery words.
    var focused by remember { mutableStateOf<Int?>(null) }
    val requesters = remember { List(24) { FocusRequester() } }
    val flow = model.restore
    val blocked = model.busy || !flow.canLeave
    fun clear() { clipboard.clearIfUnchanged(); focus.clearFocus(); input.clear(); focused = null; revision++; clearGeneration++ }
    fun leave() { clear(); flow.clearIssue(); model.step = "welcome" }
    BackHandler { if (!blocked && !useStoredSeed) leave() }
    DisposableEffect(activity, lifecycle) {
        val alreadySecure = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) {
            clipboard.clearIfUnchanged()
            if (!activity.isChangingConfigurations) clear()
        } }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            clipboard.clearIfUnchanged()
            if (!activity.isChangingConfigurations) clear()
            if (!alreadySecure) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    // Read the revision so changes to the non-saveable, redacted input redraw the fields.
    val words = remember(revision) { input.words }
    NoahForm(if (useStoredSeed) "Recovery phrase found" else "Restore Wallet") {
        Text(tr(if (useStoredSeed)
            "Your recovery phrase is still securely stored on this device. Use it to restore your wallet from its backup."
        else "Enter your 12 or 24 words in order, or paste the whole phrase into any word field."))
        if (!useStoredSeed && !blocked) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(12, 24).forEach { count ->
                    FilterChip(selected = words.size == count, onClick = {
                        focus.clearFocus(); focused = null; input.selectWordCount(count)
                        revision++; clearGeneration++; flow.clearIssue()
                    }, label = { Text(if (count == 12) tr("12 words") else tr("24 words")) },
                        modifier = Modifier.testTag("restore-count-$count"))
                }
            }
            key(clearGeneration, words.size) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(words.size / 2) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            repeat(2) { column ->
                                val index = row * 2 + column
                                RecoveryWordField(words[index], index, {
                                    input.update(it, index)
                                    if (!input.wrongWordCount && RecoveryPhraseInput.normalizedWords(it).size in listOf(12, 24)) clipboard.accepted(it)
                                    revision++; flow.clearIssue()
                                }, onFocus = { focused = index },
                                    onNext = { if (index < words.size - 1) requesters[index + 1].requestFocus() },
                                    wordCount = words.size, modifier = Modifier.weight(1f).focusRequester(requesters[index])
                                        .testTag("restore-word-${index + 1}"))
                            }
                        }
                    }
                }
            }
            Text(tr("${input.wordCount} of ${words.size} words"), style = MaterialTheme.typography.bodySmall)
            focused?.let { index ->
                val suggestions = input.suggestions(index)
                if (suggestions.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    // Suggestions reveal the private prefix; password accessibility exposes editing only.
                    .clearAndSetSemantics { }) {
                    suggestions.forEach { word -> TextButton({
                        input.update(word, index); revision++; flow.clearIssue()
                        if (index < words.size - 1) requesters[index + 1].requestFocus() else focus.clearFocus()
                    }) { Text(tr(word)) } }
                }
            }
        }
        if (input.wrongWordCount) Text(tr("Paste exactly 12 or 24 words. Your entries haven't been replaced."), color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (model.busy || flow.running) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(tr(flow.step.restoreMessage()), Modifier.testTag("restore-progress").semantics { liveRegion = LiveRegionMode.Polite })
        }
        flow.issue?.let { Text(tr(it.message), color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("restore-error").semantics { liveRegion = LiveRegionMode.Assertive }) }
        if (useStoredSeed && model.isWalletSuspended) {
            Text(tr("Your wallet is suspended. Resume recovery to restore its backup, or delete this wallet from your device."))
            NoahButton("Resume Recovery", !blocked) {
                model.action("Couldn't resume recovery. Restart Noah and try again.") { model.resumeRestoreRecovery() }
            }
        }
        if (flow.issue?.requiresRestart != true) NoahButton("Restore Wallet", model.canRestoreWallet && (useStoredSeed || input.isComplete)) {
            focus.clearFocus()
            if (!useStoredSeed && (!RecoveryPhraseInput.isValid(input.phrase) || !runCatching { io.github.niteshbalusu11.bark.validateMnemonic(input.phrase) }.getOrDefault(false))) {
                clear()
                flow.reject(RestoreIssue.invalidPhrase)
            } else {
                val phrase = if (useStoredSeed) null else input.phrase
                clear()
                model.restoreWallet(phrase)
            }
        }
        if (useStoredSeed) DeleteWalletFallback(model)
        if (!useStoredSeed) NoahButton("Back", !blocked, secondary = true, onClick = ::leave)
    }
}

/** Recovery words survive configuration recreation only in memory, never saved-instance state. */
internal class RecoveryPhrasePresentation : androidx.lifecycle.ViewModel() {
    val input = RecoveryPhraseInput()
    override fun onCleared() { input.clear() }
}
