package com.noahwallet.ui.home

import com.noahwallet.history.HistoryTransaction
import com.noahwallet.history.RepeatPayment
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * How a history record reads on Home. Only what the record already says: a counterparty only for
 * Lightning-address payments, never an invented name, and no recurring marker (history rows don't link to schedules).
 */
data class HomeRow(
    val id: String, val avatar: Avatar, val title: String, val kind: String?, val date: Date?,
    val amount: Long, val sign: String, val pending: Boolean, val failed: Boolean, val canceled: Boolean,
    val unchanged: Boolean, val incoming: Boolean, val counterparty: String?,
) {
    sealed interface Avatar {
        data class Initial(val letter: String) : Avatar
        data object Lightning : Avatar
        data object Chain : Avatar
        data object Received : Avatar
        data object Sent : Avatar
    }
    /** "Sending" / "Confirming" while pending; "Failed" / "Canceled" when it ended without moving funds. */
    val state get() = when {
        failed -> "Failed"
        canceled -> "Canceled"
        pending -> if (incoming || sign.isEmpty()) "Confirming" else "Sending"
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
            val title = when { noahUser -> local!!; address != null -> address; else -> row.displayLabel }
            val kind = when {
                noahUser -> "Noah user"
                address != null -> "Lightning"
                chain -> "On-chain"
                row.type == "Bolt11" || row.type == "Lnurl" -> "Lightning"
                row.type == "Arkoor" -> "Ark"
                else -> null
            }
            val avatar = when {
                local != null -> Avatar.Initial(local.take(1).lowercase(Locale.ROOT))
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
            return HomeRow(row.id, avatar, title, kind?.takeIf { it != title },
                row.timestamp?.takeIf { it.time > 0 && row.dateLabel == null }, row.amount, sign, pending, failed, row.isCanceled,
                unchanged = (failed || row.isCanceled) && provesNothingLeft(row),
                incoming = !outgoing, counterparty = address)
        }

        /**
         * Explicit evidence that nothing left the wallet: an Ark movement whose recorded balance change and fee are
         * both zero, and for kinds that also move on-chain funds, a merged chain record showing no change. Anything
         * unknown is not evidence; such rows just say "Failed" or "Canceled".
         */
        internal fun provesNothingLeft(row: HistoryTransaction): Boolean {
            if (row.source != "ark" || row.effectiveBalanceSat != 0L || row.offchainFeeSat != 0L) return false
            return row.movementKind !in chainKinds || row.balanceChangeSat == 0L
        }
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
