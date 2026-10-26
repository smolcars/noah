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
    /** "Asks when you have 20,000 sats or more": of what it may move, so never counting coins claimed to this phone. */
    const val minimumBalance = 20_000L
    fun shouldPrompt(enabled: Boolean, hasUnhandled: Boolean, ready: Boolean, balance: Long, blocking: Boolean) =
        enabled && hasUnhandled && ready && balance >= minimumBalance && !blocking
}

/** Foreground confirmation only: background checks never submit boarding. */
@Composable
fun AutoBoardPrompt(app: AppModel, onBoarded: (Long) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf<OnchainQuote?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
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
    val fundingStore = remember(app.settings) { AutoBoardFunding.stored(app.settings) }
    val review = remember(fundingStore) { AutoBoardReview(fundingStore) }
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
        delay((BoardPrepared.lifetimeMillis - held.elapsed()).coerceAtLeast(0))
        if (plan == held) { releasePlan() }
    }
    LaunchedEffect(app.summaryRevision, app.isWalletSuspended, generation) {
        if (app.isWalletSuspended) { if (plan != null) releasePlan(); return@LaunchedEffect }
        if (checking || busy || plan != null || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        val token = generation
        checking = true
        val caller = currentCoroutineContext()
        try {
            withContext(NonCancellable) {
                review.prepare(allowed = {
                    caller.isActive && token == generation &&
                        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && plan == null &&
                        AutoBoardRules.shouldPrompt(enabled(), true, onchainReady(app),
                            app.summary?.balance?.onchain?.confirmed?.toLong() ?: 0, false)
                }, funding = { withContext(Dispatchers.IO) { app.session.autoBoardFunding(fundingStore::kept) } },
                    quote = { app.session.autoBoardQuote(fundingStore::kept) }, discard = { app.session.discardBoardReview(it) },
                    present = { plan = it })
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (token == generation) message = (failure as? OnchainException)?.message ?: "Unable to prepare boarding. Use Settings → Board to Ark to try again."
        } finally { checking = false }
    }
    message?.let { text -> WalletAlertDialog(onDismissRequest = { message = null },
        title = { Text(tr("Board to Ark")) }, text = { Text(tr(text + " Check Settings → Board to Ark for operation status.")) },
        confirmButton = { TextButton({ message = null }) { Text(tr("OK")) } }) }
    plan?.let { quote -> OnchainReview(app, quote,
        onDismiss = { releasePlan() }, cancelTitle = "No, turn off",
        cancel = {
            try {
                app.settings.set(SettingsPreferences.autoBoard(app.settings.string("transaction-storage"), false), "transaction-storage")
                releasePlan()
            } catch (_: Exception) { message = "Couldn't save auto-board preference." }
        }, confirm = {
            if (!busy) {
                plan = null; busy = true
                scope.launch {
                    try {
                        message = app.services.lifecycle.withWalletMutation {
                            if (!enabled() || !onchainReady(app) || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) fail(OnchainFailure.unavailable)
                            app.session.executeOnchain(quote, review.fundingFor(quote), kept = fundingStore::kept)
                        }
                        onBoarded(quote.net)
                        app.refresh()
                    } catch (failure: Exception) {
                        withContext(NonCancellable) {
                            runCatching {
                                app.services.lifecycle.withWalletMutation {
                                    review.failedConfirm(quote, (failure as? OnchainException)?.reason == OnchainFailure.changed) {
                                        app.session.abandonUnsubmittedAutoBoard(quote)
                                    }
                                }
                            }
                        }
                        if (failure is CancellationException) throw failure
                        message = (failure as? OnchainException)?.message ?: OnchainFailure.uncertain.text
                    }
                    finally { withContext(NonCancellable) { app.session.discardBoardReview(quote) }; busy = false; generation++ }
                }
            }
        }) }
}
