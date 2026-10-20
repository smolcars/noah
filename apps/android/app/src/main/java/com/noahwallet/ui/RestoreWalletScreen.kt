package com.noahwallet.ui

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
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val focus = LocalFocusManager.current
    val input = remember { RecoveryPhraseInput() }
    var revision by remember { mutableIntStateOf(0) }
    var clearGeneration by remember { mutableIntStateOf(0) }
    // Only plain remember: Android saved-instance state must never contain recovery words.
    var focused by remember { mutableStateOf<Int?>(null) }
    val requesters = remember { List(12) { FocusRequester() } }
    val flow = model.restore
    val blocked = model.busy || !flow.canLeave
    fun clear() { focus.clearFocus(); input.clear(); focused = null; revision++; clearGeneration++ }
    fun leave() { clear(); flow.clearIssue(); model.step = "welcome" }
    BackHandler { if (!blocked && !useStoredSeed) leave() }
    DisposableEffect(activity, lifecycle) {
        val alreadySecure = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) clear() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer); clear()
            if (!alreadySecure) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    // Read the revision so changes to the non-saveable, redacted input redraw the fields.
    val words = remember(revision) { input.words }
    NoahForm(if (useStoredSeed) "Recovery phrase found" else "Restore Wallet") {
        Text(if (useStoredSeed)
            "Your recovery phrase is still securely stored on this device. Use it to restore your wallet from its backup."
        else "Enter your 12 words in order, or paste the whole phrase into any word field.")
        if (!useStoredSeed && !blocked) {
            key(clearGeneration) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(6) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        repeat(2) { column ->
                            val index = row * 2 + column
                            RecoveryWordField(words[index], index, {
                                input.update(it, index); revision++; flow.clearIssue()
                            }, onFocus = { focused = index },
                                onNext = { if (index < 11) requesters[index + 1].requestFocus() },
                                modifier = Modifier.weight(1f).focusRequester(requesters[index])
                                    .testTag("restore-word-${index + 1}"))
                        }
                    }
                }
            }
            }
            Text("${input.wordCount} of 12 words", style = MaterialTheme.typography.bodySmall)
            focused?.let { index ->
                val suggestions = input.suggestions(index)
                if (suggestions.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    suggestions.forEach { word -> TextButton({
                        input.update(word, index); revision++; flow.clearIssue()
                        if (index < 11) requesters[index + 1].requestFocus() else focus.clearFocus()
                    }) { Text(word) } }
                }
            }
        }
        if (input.wrongWordCount) Text("Paste exactly 12 words. Your entries haven't been replaced.", color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (model.busy || flow.running) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(flow.step.restoreMessage(), Modifier.testTag("restore-progress").semantics { liveRegion = LiveRegionMode.Polite })
        }
        flow.issue?.let { Text(it.message, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("restore-error").semantics { liveRegion = LiveRegionMode.Assertive }) }
        if (flow.issue?.requiresRestart != true) NoahButton("Restore Wallet", !blocked && (useStoredSeed || input.isComplete)) {
            focus.clearFocus()
            if (!useStoredSeed && !RecoveryPhraseInput.isValid(input.phrase)) {
                clear()
                flow.reject(RestoreIssue.invalidPhrase)
            } else {
                val phrase = if (useStoredSeed) null else input.phrase
                clear()
                model.restoreWallet(phrase)
            }
        }
        if (!useStoredSeed) NoahButton("Back", !blocked, secondary = true, onClick = ::leave)
    }
}
