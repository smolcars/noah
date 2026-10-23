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
 * since ([known]), and the receipts already complete plus each address payment it has shown ([settled]).
 */
@Serializable
data class ReceiveRecords(val known: Set<String>, val settled: Set<String>) {
    companion object {
        fun baseline(rows: List<HistoryTransaction>) = ReceiveRecords(rows.map { it.id }.toSet(), ReceiveArrivals.settled(rows).toSet())
    }
}

/**
 * Shows arrivals from the wallet's records one at a time: a new unconfirmed deposit first, then payments to your
 * Lightning address. It looks again whenever the records or the request change, the card comes free or an arrival is
 * taken, so several arrivals in one history update each play in turn.
 */
@Composable
internal fun ReceiveRecordsWatcher(rows: List<HistoryTransaction>, loading: Boolean, request: ReceiveRequest?, ownArk: Set<String>, ownHashes: Set<String>,
                                   records: MutableState<ReceiveRecords?>, arrival: MutableState<ReceiveArrival?>, chain: MutableState<ReceiveArrival?>,
                                   onAddressPayment: () -> Unit = {}) {
    LaunchedEffect(rows, loading, request, ownArk, ownHashes, records.value, arrival.value == null) {
        if (loading) return@LaunchedEffect
        val seen = records.value ?: run { records.value = ReceiveRecords.baseline(rows); return@LaunchedEffect }
        if (arrival.value != null) return@LaunchedEffect
        val (taken, next) = ReceiveArrivals.next(rows, seen, request?.onchainAddress, ownArk, ownHashes, ::paid, SystemClock.elapsedRealtime())
            ?: return@LaunchedEffect
        // Taken in one step: a new key restarts this effect, so nothing here may suspend before the arrival is set.
        records.value = taken
        if (next.rail == ReceiveArrival.Rail.onchain) chain.value = next
        arrival.value = next
        if (next.rail != ReceiveArrival.Rail.onchain) onAddressPayment()
    }
}

/** The sats [hex] pays to [address], when it can be read. */
private fun paid(address: String, hex: String): Long? = runCatching { SendTransaction.amount(address, hex) }.getOrNull()
