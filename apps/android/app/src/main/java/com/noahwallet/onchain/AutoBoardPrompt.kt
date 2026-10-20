package com.noahwallet.onchain

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.noahwallet.services.storage.SettingsPreferences
import com.noahwallet.ui.AppModel
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

object AutoBoardRules {
    fun shouldPrompt(enabled: Boolean, attempted: Boolean, ready: Boolean, balance: Long, blocking: Boolean) =
        enabled && !attempted && ready && balance >= 20_000 && !blocking
}

/** One-shot prompt only: background checks never submit boarding. */
@Composable
fun AutoBoardPrompt(app: AppModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf<OnchainQuote?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    fun prefs() = SettingsPreferences.state(app.settings.string("transaction-storage"))
    fun enabled() = prefs()["isAutoBoardingEnabled"]?.jsonPrimitive?.booleanOrNull != false
    fun attempted() = prefs()["hasAttemptedAutoBoarding"]?.jsonPrimitive?.booleanOrNull == true
    fun markAttempted(): Boolean = try {
        app.settings.set(SettingsPreferences.update(app.settings.string("transaction-storage"), mapOf("hasAttemptedAutoBoarding" to JsonPrimitive(true)), 1), "transaction-storage")
        app.settings.sync(); true
    } catch (_: Exception) { message = "Couldn't save auto-board state."; false }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { plan = null; generation++ }
            if (event == Lifecycle.Event.ON_START) generation++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); plan = null }
    }
    LaunchedEffect(app.summary?.balance?.onchain?.confirmed, app.isWalletSuspended, generation) {
        if (app.isWalletSuspended) { plan = null; return@LaunchedEffect }
        if (busy || plan != null || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@LaunchedEffect
        try {
            if (!AutoBoardRules.shouldPrompt(enabled(), attempted(), onchainReady(app), app.summary?.balance?.onchain?.confirmed?.toLong() ?: 0, false)) return@LaunchedEffect
            val blocking = withContext(Dispatchers.IO) { app.session.onchainJournal.loadAll().any { it.blocks(OnchainAction.board, emptyList()) } }
            if (blocking) return@LaunchedEffect
            val quote = app.session.autoBoardQuote()
            if (enabled() && !attempted() && onchainReady(app) && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) plan = quote
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "Unable to estimate boarding fees. Use Settings → Board to Ark to try again." }
    }
    message?.let { Text(it) }
    plan?.let { quote -> OnchainReview(app, quote,
        onDismiss = { markAttempted(); plan = null }, cancelTitle = "No, turn off",
        cancel = {
            try {
                app.settings.set(SettingsPreferences.autoBoard(app.settings.string("transaction-storage"), false), "transaction-storage")
                markAttempted(); plan = null
            } catch (_: Exception) { message = "Couldn't save auto-board preference." }
        }, confirm = {
            if (markAttempted() && !busy) {
                plan = null; busy = true
                scope.launch {
                    try {
                        message = app.services.lifecycle.withWalletMutation {
                            if (!enabled() || !onchainReady(app) || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) fail(OnchainFailure.unavailable)
                            app.session.executeOnchain(quote)
                        }
                        app.refresh()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { message = (failure as? OnchainException)?.message ?: OnchainFailure.uncertain.text }
                    finally { busy = false }
                }
            }
        }) }
}
