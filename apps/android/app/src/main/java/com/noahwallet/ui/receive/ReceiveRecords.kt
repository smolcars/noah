package com.noahwallet.ui.receive

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import com.noahwallet.history.HistoryTransaction
import com.noahwallet.send.SendTransaction
import com.noahwallet.services.wallet.ReceiveRequest
import kotlinx.serialization.Serializable

/**
 * What Receive has taken from the wallet's records: the rows there when it appeared plus each deposit it has shown
 * since ([known]), the receipts already complete plus each address payment it has shown ([settled]), and the coins
 * every deposit shown since spends, "txid:vout" ([spent]), so a replacement of any of them is never a new arrival.
 */
@Serializable
data class ReceiveRecords(val known: Set<String>, val settled: Set<String>, val spent: Set<String> = emptySet()) {
    companion object {
        fun baseline(rows: List<HistoryTransaction>) = ReceiveRecords(rows.map { it.id }.toSet(), ReceiveArrivals.settled(rows).toSet())
    }
}

/**
 * Shows arrivals from the wallet's records one at a time: a new unconfirmed deposit first, then payments to your
 * Lightning address. It looks again whenever the records or the request change, the card comes free or an arrival is
 * taken, so several arrivals in one history update each play in turn. It also keeps [chain], the deposit the block
 * row follows, on the transaction that's really in the records.
 */
@Composable
internal fun ReceiveRecordsWatcher(rows: List<HistoryTransaction>, loading: Boolean, request: ReceiveRequest?, ownArk: Set<String>, ownHashes: Set<String>,
                                   records: MutableState<ReceiveRecords?>, arrival: MutableState<ReceiveArrival?>, chain: MutableState<ReceiveArrival?>,
                                   onAddressPayment: () -> Unit = {}) {
    LaunchedEffect(rows, loading, request, ownArk, ownHashes, records.value, arrival.value == null, chain.value) {
        if (loading) return@LaunchedEffect
        val seen = records.value ?: run { records.value = ReceiveRecords.baseline(rows); return@LaunchedEffect }
        // The deposit the block row follows, reconciled on its own (the arrival filter skips confirmed transactions):
        // a replacement spending the same coins takes over and shows its confirmation without a second arrival; one
        // that's simply gone ends the wait.
        chain.value?.let { tracked ->
            val (followed, replacement) = ReceiveArrivals.followed(tracked, rows, seen.known, ::spends)
            if (followed != tracked) {
                replacement?.let { records.value = seen.copy(known = seen.known + it.id, spent = seen.spent + followed?.inputs.orEmpty()) }
                chain.value = followed
                return@LaunchedEffect
            }
        }
        if (arrival.value != null) return@LaunchedEffect
        val (taken, next) = ReceiveArrivals.next(rows, seen, request?.onchainAddress, ownArk, ownHashes, ::paid, ::spends, SystemClock.elapsedRealtime())
            ?: return@LaunchedEffect
        // Taken in one step: a new key restarts this effect, so nothing here may suspend before the arrival is set.
        records.value = taken
        // Only replacements of deposits already shown: they're marked seen, with no arrival.
        if (next == null) return@LaunchedEffect
        if (next.rail == ReceiveArrival.Rail.onchain) chain.value = next
        arrival.value = next
        if (next.rail != ReceiveArrival.Rail.onchain) onAddressPayment()
    }
}

/** The sats [hex] pays to [address], when it can be read. */
private fun paid(address: String, hex: String): Long? = runCatching { SendTransaction.amount(address, hex) }.getOrNull()

/** The coins [hex] spends, as "txid:vout", when it can be read. */
private fun spends(hex: String): List<String>? = runCatching { SendTransaction.parse(hex).inputs.map { "${it.txid}:${it.vout}" } }.getOrNull()
