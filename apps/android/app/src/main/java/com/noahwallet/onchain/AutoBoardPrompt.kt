package com.noahwallet.onchain

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.noahwallet.localization.tr
import com.noahwallet.services.storage.SettingsPreferences
import com.noahwallet.ui.AppModel
import com.noahwallet.ui.WalletAlertDialog
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Foreground confirmation only: background checks never submit boarding. */
@Composable
fun AutoBoardPrompt(app: AppModel, onBoarded: (Long) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val fundingStore = remember(app.settings) { AutoBoardFunding.stored(app.settings) }
    val review = remember(fundingStore) { AutoBoardReview(fundingStore) }
    val prompt = remember { AutoBoardPromptState(app) }
    val board = AutoBoard(app, review, fundingStore)
    val resumed = { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
    ReleaseWhenPaused(lifecycle, prompt::releasePlan) { prompt.generation++ }
    LaunchedEffect(app.session) { app.session.walletChanges.collect { prompt.releasePlan() } }
    LaunchedEffect(prompt.plan) { prompt.expire() }
    LaunchedEffect(app.summaryRevision, app.isWalletSuspended, prompt.generation) { prompt.check(board, resumed) }
    prompt.message?.let { text -> AutoBoardMessage(text) { prompt.message = null } }
    prompt.plan?.let { quote -> OnchainReview(app, quote,
        onDismiss = { prompt.releasePlan() }, cancelTitle = "No, turn off",
        cancel = { prompt.turnOff() }, confirm = { prompt.confirm(scope, board, quote, resumed, onBoarded) }) }
}

/** The prompt's state: the review shown, its message, and whether a check or a board is running. */
@Stable
private class AutoBoardPromptState(private val app: AppModel) {
    var plan by mutableStateOf<OnchainQuote?>(null)
    var message by mutableStateOf<String?>(null)
    private var checking by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    var generation by mutableIntStateOf(0)
    private val disposalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun releasePlan() {
        generation++
        plan?.let { held -> disposalScope.launch { app.session.discardBoardReview(held) } }
        plan = null
    }
    private fun prefs() = SettingsPreferences.transaction(app.settings.string("transaction-storage"))
    private fun enabled() = prefs()["isAutoBoardingEnabled"]?.jsonPrimitive?.booleanOrNull != false

    /** A review is released when its preparation expires. */
    suspend fun expire() {
        val held = plan ?: return
        delay((BoardPrepared.lifetimeMillis - held.elapsed()).coerceAtLeast(0))
        if (plan == held) { releasePlan() }
    }

    suspend fun check(board: AutoBoard, resumed: () -> Boolean) {
        if (app.isWalletSuspended) {
            if (plan != null) releasePlan()
            return
        }
        val idle = !checking && !busy && plan == null
        if (!idle || !resumed()) return
        val token = generation
        checking = true
        val caller = currentCoroutineContext()
        try {
            board.prepare(allowed = { allowed(caller, token, resumed) }) { plan = it }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (@Suppress("TooGenericExceptionCaught") failure: Exception) { // UI boundary: typed failures show their own message
            if (token == generation) {
                message = (failure as? OnchainException)?.message ?: "Unable to prepare boarding. Use Settings → Board to Ark to try again."
            }
        } finally { checking = false }
    }

    /** Still this check's moment: nothing replaced it, the app is in front, no review is shown and the rules allow it. */
    private fun allowed(caller: kotlin.coroutines.CoroutineContext, token: Int, resumed: () -> Boolean) =
        caller.isActive && token == generation && resumed() && plan == null &&
            AutoBoardRules.shouldPrompt(enabled(), true, onchainReady(app), app.summary?.balance?.onchain?.confirmed?.toLong() ?: 0, false)

    fun turnOff() {
        if (turnOffAutoBoard(app)) releasePlan() else message = "Couldn't save auto-board preference."
    }

    fun confirm(scope: CoroutineScope, board: AutoBoard, quote: OnchainQuote, resumed: () -> Boolean, onBoarded: (Long) -> Unit) {
        if (busy) return
        plan = null; busy = true
        scope.launch {
            // A board changes what every page shows, as the pages' own submissions do (SharedOverview).
            SharedOverview.shared.changed()
            try {
                board.confirm(quote, allowed = { enabled() && onchainReady(app) && resumed() }, onBoarded) { message = it }
            } finally {
                SharedOverview.shared.changed()
                withContext(NonCancellable) { app.session.discardBoardReview(quote) }; busy = false; generation++
            }
        }
    }
}

/** A pause releases the shown review; a resume asks for a new check. */
@Composable
private fun ReleaseWhenPaused(lifecycle: Lifecycle, release: () -> Unit, resumed: () -> Unit) {
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { release() }
            if (event == Lifecycle.Event.ON_RESUME) resumed()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); release() }
    }
}

@Composable
private fun AutoBoardMessage(text: String, dismiss: () -> Unit) {
    WalletAlertDialog(onDismissRequest = dismiss,
        title = { Text(tr("Board to Ark")) }, text = { Text(tr(text + " Check Settings → Board to Ark for operation status.")) },
        confirmButton = { TextButton(dismiss) { Text(tr("OK")) } })
}

/** False when the preference couldn't be saved. */
private fun turnOffAutoBoard(app: AppModel): Boolean = try {
    app.settings.set(SettingsPreferences.autoBoard(app.settings.string("transaction-storage"), false), "transaction-storage")
    true
} catch (_: Exception) { false }

/** The prompt's wallet work: preparing a review, and confirming or recording a failed confirm. */
private class AutoBoard(private val app: AppModel, private val review: AutoBoardReview, private val fundingStore: AutoBoardFunding) {
    suspend fun prepare(allowed: () -> Boolean, present: (OnchainQuote) -> Unit) = withContext(NonCancellable) {
        review.prepare(allowed = allowed, funding = { withContext(Dispatchers.IO) { app.session.autoBoardFunding(fundingStore::kept) } },
            quote = { app.session.autoBoardQuote(fundingStore::kept) }, discard = { app.session.discardBoardReview(it) },
            present = present)
    }

    /** Boards [quote] under the wallet lock if still [allowed]; [show] gets the outcome as soon as it's known. */
    suspend fun confirm(quote: OnchainQuote, allowed: () -> Boolean, onBoarded: (Long) -> Unit, show: (String) -> Unit) {
        try {
            show(app.services.lifecycle.withWalletMutation {
                if (!allowed()) fail(OnchainFailure.unavailable)
                app.session.executeOnchain(quote, review.fundingFor(quote), kept = fundingStore::kept)
            })
            onBoarded(quote.net)
            app.refresh()
        } catch (cancelled: CancellationException) {
            failed(quote, cancelled)
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) { // any failure is recorded, then shown
            failed(quote, failure)
            show((failure as? OnchainException)?.message ?: OnchainFailure.uncertain.text)
        }
    }

    /** A confirm that failed, cancellation included: the review learns whether the board may have been submitted. */
    private suspend fun failed(quote: OnchainQuote, failure: Exception) {
        withContext(NonCancellable) {
            runCatching {
                app.services.lifecycle.withWalletMutation {
                    review.failedConfirm(quote, (failure as? OnchainException)?.reason == OnchainFailure.changed) {
                        app.session.abandonUnsubmittedAutoBoard(quote)
                    }
                }
            }
        }
    }
}
