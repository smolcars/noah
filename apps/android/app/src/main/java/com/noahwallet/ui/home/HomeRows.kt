package com.noahwallet.ui.home

import com.noahwallet.history.HistoryMetadata
import com.noahwallet.history.HistoryTransaction
import com.noahwallet.history.RepeatPayment
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * How a history record reads on Home and in Activity. Only what the record already says: a counterparty only for
 * Lightning-address payments, an invoice's or offer's own description, a recurring schedule's name when the
 * recurring journal names the payment, never an invented name.
 */
data class HomeRow(
    val id: String, val avatar: Avatar, val title: String, val kind: String?, val date: Date?,
    val amount: Long?, val sign: String, val pending: Boolean, val failed: Boolean, val canceled: Boolean,
    val unchanged: Boolean, val incoming: Boolean, val counterparty: String?, val refunded: Boolean = false,
    /** The pending word when it isn't Home's plain "Sending" / "Confirming": "Exiting", "In a round". */
    val pendingState: String? = null,
) {
    sealed interface Avatar {
        data class Initial(val letter: String) : Avatar
        data object Lightning : Avatar
        data object Chain : Avatar
        data object Received : Avatar
        data object Sent : Avatar
        data object Recurring : Avatar
        data object Renewal : Avatar
        data object Exit : Avatar
    }
    /** "Sending" / "Confirming" while pending; "Failed" / "Refunded" / "Canceled" when it ended without moving funds. */
    val state get() = when {
        refunded -> """Refunded""" // Home shows the whole "Refunded · back in your wallet" line instead.
        failed -> "Failed"
        canceled -> "Canceled"
        pending -> pendingState ?: if (incoming || sign.isEmpty()) "Confirming" else "Sending"
        else -> null
    }

    companion object {
        private val chainKinds = setOf("onboard", "offboard", "send-onchain", "exit")
        fun of(row: HistoryTransaction, ownDomain: String?): HomeRow {
            val outgoing = row.direction == "outgoing"
            val address = row.destination?.takeIf { row.type == "Lnurl" && outgoing }?.let(RepeatPayment::normalizeLightningAddress)
            val local = address?.substringBefore('@')
            val noahUser = address != null && ownDomain != null && address.substringAfter('@') == ownDomain.lowercase(Locale.ROOT)
            val chain = row.type == "Onchain" || row.source == "onchain-wallet" || row.movementKind in chainKinds
            val recurring = row.recurring?.takeIf { outgoing }
            // An invoice or offer names no one: its own description, else what happened.
            val invoiceRow = row.type == "Bolt11" || row.offer != null
            val title = when {
                recurring != null -> recurring.label
                noahUser -> local!!
                address != null -> address
                row.isRenewal -> "Renewed"
                invoiceRow -> row.description ?: if (outgoing) "Sent" else "Received"
                else -> row.displayLabel
            }
            val kind = when {
                recurring != null -> "Recurring"
                noahUser -> "Noah user"
                address != null -> "Lightning"
                chain -> "On-chain"
                row.type == "Bolt11" || row.type == "Lnurl" -> "Lightning"
                row.type == "Arkoor" -> "Ark"
                else -> null
            }
            val avatar = when {
                recurring != null -> Avatar.Recurring
                local != null -> Avatar.Initial(local.take(1).lowercase(Locale.ROOT))
                row.isRenewal -> Avatar.Renewal
                row.exitFeeFor != null -> Avatar.Exit
                chain -> Avatar.Chain
                row.type == "Bolt11" || row.type == "Lnurl" -> Avatar.Lightning
                outgoing -> Avatar.Sent
                else -> Avatar.Received
            }
            val pending = when {
                row.source == "onchain-wallet" -> row.hasConfirmation != true
                else -> row.movementStatus == "pending"
            }
            val failed = row.movementStatus == "failed"
            val sign = if (row.isInternalTransfer) "" else if (outgoing) "−" else "+"
            val pendingState = when (row.movementKind) {
                "exit" -> "Exiting"
                // bark records the offboard transaction once the round signs it; until then it waits for a round.
                "offboard", "send-onchain" -> if (HistoryMetadata.parse(row.metadataJson ?: "").offboardTxid == null) "In a round" else "Confirming"
                else -> null
            }
            return HomeRow(row.id, avatar, title, kind?.takeIf { it != title },
                row.timestamp?.takeIf { it.time > 0 && row.dateLabel == null }, row.provenAmount, sign, pending, failed, row.isCanceled,
                unchanged = (failed || row.isCanceled) && provesNothingLeft(row),
                incoming = !outgoing, counterparty = address, refunded = row.isRefunded, pendingState = pendingState)
        }

        /**
         * Explicit evidence that nothing left the wallet: an Ark movement whose recorded balance change and fee are
         * both zero, and for kinds that also move on-chain funds, a merged chain record showing no change and a zero
         * on-chain fee. Other kinds may carry no chain values, but any they carry must be zero. Anything unknown is
         * not evidence; such rows just say "Failed" or "Canceled". bark 0.8 fails a send that never left with a zero
         * balance change but keeps its planned fee; when it produced and exited no coins, that fee was never paid.
         */
        internal fun provesNothingLeft(row: HistoryTransaction): Boolean {
            val fee = row.offchainFeeSat ?: return false
            val unpaid = row.movementStatus == "failed" && row.outputVtxos.isNullOrEmpty() && row.exitedVtxos.isNullOrEmpty()
            if (row.source != "ark" || row.effectiveBalanceSat != 0L || (fee != 0L && !unpaid)) return false
            return if (row.movementKind in chainKinds) row.balanceChangeSat == 0L && row.onchainFeeSat == 0L
                else (row.balanceChangeSat ?: 0L) == 0L && (row.onchainFeeSat ?: 0L) == 0L
        }

        /** Money that actually came in: incoming, not a transfer between your own balances, not failed or canceled. */
        internal fun received(row: HistoryTransaction) = row.direction == "incoming" && !row.isInternalTransfer &&
            row.movementStatus != "failed" && !row.isCanceled && row.amount > 0
    }
}

/** Day headers for the wide activity list: Today, Yesterday, then the date. Undated rows stay with the group above. */
object HomeDays {
    sealed interface Day {
        data object Today : Day
        data object Yesterday : Day
        data class On(val date: Date) : Day
    }
    fun day(date: Date?, now: Date): Day? {
        if (date == null) return null
        val today = Calendar.getInstance().apply { time = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        return when {
            !date.before(today.time) -> Day.Today
            !date.before(yesterday.time) -> Day.Yesterday
            else -> Day.On(Calendar.getInstance().apply { time = date; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.time)
        }
    }
    fun <T> grouped(rows: List<T>, date: (T) -> Date?, now: Date): List<Pair<Day, List<T>>> {
        val groups = mutableListOf<Pair<Day, MutableList<T>>>()
        for (row in rows) {
            val day = day(date(row), now) ?: groups.lastOrNull()?.first ?: Day.Today
            if (groups.lastOrNull()?.first == day) groups.last().second.add(row) else groups.add(day to mutableListOf(row))
        }
        return groups
    }
}
