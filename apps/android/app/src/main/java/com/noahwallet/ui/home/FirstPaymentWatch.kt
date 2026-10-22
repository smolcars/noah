package com.noahwallet.ui.home

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.noahwallet.history.HistoryTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * When the first payment plays. Each visit takes a baseline only after its own balance refresh has landed; the
 * sequence then plays once, for incoming funds that appear after that baseline on a wallet that was Fresh at it.
 * Payments the refresh itself finds (they arrived while Home was away) just open Everyday, and so does anything
 * that shows up before the baseline exists. A visit is Home on screen in the foreground: showing Home, or coming
 * back to it from the background. In memory only; a configuration change keeps it, a real hide resets it.
 */
class FirstPaymentWatch {
    data class Baseline(val fresh: Boolean, val incoming: Set<String>)
    data class Arrival(val row: HistoryTransaction?, val sats: Long)

    var baseline: Baseline? = null
        private set

    /** Home was hidden for real (another screen or the background): the next visit takes a new baseline. */
    fun hidden() { baseline = null }

    /** This visit's initial refresh has landed; only the first call per visit sets the baseline. */
    fun caughtUp(state: HomeRules.State, rows: List<HistoryTransaction>) {
        if (baseline == null) baseline = Baseline(state == HomeRules.State.fresh, incoming(rows))
    }

    /**
     * One visit's catch-up: waits for a balance refresh that lands after the visit began ([revisions] passing its
     * value now; Home appearing and the app coming back each ask for one), and asks for another ([refresh]) whenever
     * none lands within a pause, so a failed refresh (offline, the wallet busy) is retried, backing off to
     * [longestPause]. Then it takes the baseline from the wallet as it is ([settled]). A visit that already has one
     * (a configuration change continues it) does nothing.
     */
    suspend fun catchUp(revisions: Flow<Long>, refresh: suspend () -> Unit, settled: suspend () -> Pair<HomeRules.State, List<HistoryTransaction>>,
                        firstPause: Long = 5_000, longestPause: Long = 60_000) {
        if (baseline != null) return
        val start = revisions.first()
        var pause = firstPause
        while (withTimeoutOrNull(pause) { revisions.first { it > start } } == null) {
            refresh()
            pause = (pause * 2).coerceAtMost(longestPause)
        }
        val (state, rows) = settled()
        caughtUp(state, rows)
    }

    /** The payment to celebrate: a new incoming row, or the balance rising above zero, after a Fresh baseline. */
    fun arrival(rows: List<HistoryTransaction>, totalSats: ULong?): Arrival? {
        val base = baseline?.takeIf { it.fresh } ?: return null
        val row = rows.firstOrNull { it.isIncoming && it.id !in base.incoming }
        val sats = row?.amount ?: totalSats?.toLong()?.takeIf { it > 0 } ?: return null
        return Arrival(row, sats)
    }

    /** It plays once per visit: afterwards the baseline is no longer Fresh. */
    fun played() { baseline = baseline?.copy(fresh = false) }

    private val HistoryTransaction.isIncoming get() = HomeRow.received(this)

    private fun incoming(rows: List<HistoryTransaction>) = rows.filter { it.isIncoming }.map { it.id }.toSet()
}

/**
 * Keeps [watch] in step with Home's visits: every time Home is started (shown, or back from the background) it
 * catches up once [revision] moves past where it was, and a real hide (the background, another screen) resets it.
 * A configuration change is neither: the visit, and its baseline, carry on.
 */
@Composable
internal fun FirstPaymentVisits(watch: FirstPaymentWatch, active: Boolean, revision: () -> Long, refresh: suspend () -> Unit,
                                settled: suspend () -> Pair<HomeRules.State, List<HistoryTransaction>>) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = LocalContext.current as? Activity
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true) watch.hidden()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); if (activity?.isChangingConfigurations != true) watch.hidden() }
    }
    LaunchedEffect(lifecycle, active) {
        if (active) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { watch.catchUp(snapshotFlow(revision), refresh, settled) }
    }
}
