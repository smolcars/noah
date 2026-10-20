package com.noahwallet.onchain

import com.noahwallet.ui.WalletAlertDialog

import com.noahwallet.localization.tr
import androidx.compose.material3.*
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
fun AutoBoardPrompt(app: AppModel, onBoarded: (Long) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf<OnchainQuote?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    val disposalScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    fun releasePlan() {
        generation++
        plan?.let { held -> disposalScope.launch { app.session.discardBoardReview(held) } }
        plan = null
    }
    fun prefs() = SettingsPreferences.transaction(app.settings.string("transaction-storage"))
    fun enabled() = prefs()["isAutoBoardingEnabled"]?.jsonPrimitive?.booleanOrNull != false
    fun attempted() = prefs()["hasAttemptedAutoBoarding"]?.jsonPrimitive?.booleanOrNull == true
    fun markAttempted(): Boolean = try {
        app.settings.set(SettingsPreferences.update(app.settings.string("transaction-storage"), mapOf("hasAttemptedAutoBoarding" to JsonPrimitive(true)), 1), "transaction-storage")
        app.settings.sync(); true
    } catch (_: Exception) { message = "Couldn't save auto-board state."; false }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { releasePlan() }
            if (event == Lifecycle.Event.ON_RESUME) generation++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); releasePlan() }
    }
    LaunchedEffect(app.session) { app.session.walletChanges.collect { releasePlan() } }
    LaunchedEffect(plan) {
        val held = plan ?: return@LaunchedEffect
        delay((held.createdAt + BoardPrepared.lifetimeMillis - System.currentTimeMillis()).coerceAtLeast(0))
        if (plan == held) { markAttempted(); releasePlan() }
    }
    LaunchedEffect(app.summary?.balance?.onchain?.confirmed, app.isWalletSuspended, generation) {
        if (app.isWalletSuspended) { if (plan != null) releasePlan(); return@LaunchedEffect }
        if (busy || plan != null || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        val token = generation
        try {
            if (!AutoBoardRules.shouldPrompt(enabled(), attempted(), onchainReady(app), app.summary?.balance?.onchain?.confirmed?.toLong() ?: 0, false)) return@LaunchedEffect
            val blocking = withContext(Dispatchers.IO) { app.session.onchainJournal.loadAll().any { it.blocks(OnchainAction.board, emptyList()) } }
            if (blocking) return@LaunchedEffect
            val caller = currentCoroutineContext()
            val quote = withContext(NonCancellable) { app.session.autoBoardQuote() }
            if (quote != null) {
                if (caller.isActive && token == generation && enabled() && !attempted() && onchainReady(app) && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) plan = quote
                else app.session.discardBoardReview(quote)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (token == generation) message = (failure as? OnchainException)?.message ?: "Unable to prepare boarding. Use Settings → Board to Ark to try again." }
    }
    message?.let { text -> WalletAlertDialog(onDismissRequest = { message = null },
        title = { Text(tr("Board to Ark")) }, text = { Text(tr(text + " Check Settings → Board to Ark for operation status.")) },
        confirmButton = { TextButton({ message = null }) { Text(tr("OK")) } }) }
    plan?.let { quote -> OnchainReview(app, quote,
        onDismiss = { markAttempted(); releasePlan() }, cancelTitle = "No, turn off",
        cancel = {
            try {
                app.settings.set(SettingsPreferences.autoBoard(app.settings.string("transaction-storage"), false), "transaction-storage")
                markAttempted(); releasePlan()
            } catch (_: Exception) { message = "Couldn't save auto-board preference." }
        }, confirm = {
            if (markAttempted() && !busy) {
                plan = null; busy = true
                scope.launch {
                    try {
                        message = app.services.lifecycle.withWalletMutation {
                            if (!enabled() || !onchainReady(app) || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) fail(OnchainFailure.unavailable)
                            app.session.executeOnchain(quote)
                        }
                        onBoarded(quote.net)
                        app.refresh()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { message = (failure as? OnchainException)?.message ?: OnchainFailure.uncertain.text }
                    finally { app.session.discardBoardReview(quote); busy = false }
                }
            }
        }) }
}
