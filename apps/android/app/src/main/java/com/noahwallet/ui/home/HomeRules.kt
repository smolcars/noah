package com.noahwallet.ui.home

import com.noahwallet.formatting.FiatCurrency
import com.noahwallet.services.storage.PersistedBackupState
import java.text.DateFormat
import java.util.Date
import java.util.Locale

object HomeRules {
    private val symbols = mapOf("USD" to "$", "EUR" to "€", "GBP" to "£", "CAD" to "CA$", "CHF" to "CHF", "AUD" to "A$",
        "JPY" to "¥", "BRL" to "R$", "KRW" to "₩", "INR" to "₹", "MXN" to "MX$", "SGD" to "S$")
    fun fiat(sats: Long?, price: Double?, currency: String, locale: Locale, hidden: Boolean): String? =
        if (hidden) "${symbols.getValue(currency)} ••••" else sats?.let { balance -> price?.takeIf { it.isFinite() &&
            it > 0 }?.let { FiatCurrency.display(balance, it, currency, locale) } }
    const val successMillis = 5_000L
    data class Banner(val title: String, val message: String, val failed: Boolean = false, val retry: Boolean = false)
    fun backup(state: PersistedBackupState, now: Long, locale: Locale): Banner? = when {
        state.lastBackupStatus == "in_progress" -> Banner("Backing up wallet", "Running in background")
        state.lastBackupStatus == "failed" -> Banner("Backup failed", "An unknown error occurred while backing up.", failed = true, retry = true)
        !state.backupPending && state.lastBackupStatus == "success" && state.lastBackupAt != null && now - state.lastBackupAt!! < successMillis -> {
            // Never shown: Home toasts only the title.
            val time = DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(state.lastBackupAt!!.toLong()))
            Banner("Backup completed", """Last backup $time""")
        }
        else -> null
    }
    /** The title is the toast's key; the toast itself says "{amount} available in Ark". */
    fun boarding(amount: Long, createdAt: Long, now: Long, locale: Locale): Banner? = if (now - createdAt < successMillis) {
        Banner("""Boarding completed""", "${java.text.NumberFormat.getIntegerInstance(locale).format(amount)} sats available in Ark")
    } else null
    fun activeRound(final: Boolean, status: String) = !final || status in setOf("pending", "unconfirmed")
    fun updateURL(variant: String) = "https://play.google.com/store/apps/details?id=com.noahwallet.${if (variant == "mainnet") "mainnet" else "signet"}"

    /** Fresh needs a known zero balance and a loaded, empty history; any row means Everyday. */
    enum class State { loading, fresh, everyday, suspended }
    fun state(suspended: Boolean, totalSats: ULong?, historyLoading: Boolean, historyError: String?, hasRows: Boolean): State = when {
        suspended -> State.suspended
        hasRows -> State.everyday
        historyLoading || totalSats == null -> State.loading
        totalSats == 0uL && historyError == null -> State.fresh
        else -> State.everyday
    }

    /** Refreshable VTXOs bark reports inside the refresh threshold; expired and locked ones are excluded. */
    data class Renew(val sats: Long, val blocks: Long, val ids: List<String>) {
        /** Ten minutes a block, rounded down so the time left is never overstated; 0 means under an hour. */
        val hours get() = (blocks.coerceAtLeast(0) * 10) / 60
    }
    fun renew(vtxos: List<com.noahwallet.onchain.VtxoModel>): Renew? {
        val due = vtxos.filter { it.renewable }
        if (due.isEmpty()) return null
        return Renew(due.sumOf { it.amount }, due.minOf { it.expiry - it.tip }, due.map { it.id }.sorted())
    }

    /** One nudge at a time, in the spec's order. */
    sealed interface Nudge {
        val key: String
        data class Notice(val message: String) : Nudge { override val key get() = "notice:$message" }
        data class Renewal(val renew: Renew) : Nudge { override val key get() = "renew" }
        data class BackupFailed(val banner: Banner) : Nudge { override val key get() = "backup-failed" }
        data object BackUp : Nudge { override val key get() = "backup" }
        data object Email : Nudge { override val key get() = "email" }
        data object Update : Nudge { override val key get() = "update" }
    }
    @Suppress("LongParameterList") // a pure rule over Home's state, which HomeRulesTest drives argument by argument
    fun nudge(notices: List<String>, renew: Renew?, backup: Banner?, backedUp: Boolean, emailPrompt: Boolean, updateRequired: Boolean): Nudge? = when {
        notices.isNotEmpty() -> Nudge.Notice(notices.first())
        renew != null -> Nudge.Renewal(renew)
        backup?.failed == true -> Nudge.BackupFailed(backup)
        !backedUp -> Nudge.BackUp
        emailPrompt -> Nudge.Email
        updateRequired -> Nudge.Update
        else -> null
    }

    /** At most one line under the fiat, in the spec's order: money and rounds only (backups and background tasks are [activity]). */
    sealed interface Status {
        data class Error(val message: String) : Status
        data class Arriving(val sats: ULong) : Status
        data class InRound(val sats: ULong) : Status
        data class Rounds(val count: Int) : Status

        /**
         * What a tap opens: only a place that explains the line. Funds in a round open the round (its funding
         * transaction) while one is active; an error already says what to do, so it's plain text.
         */
        fun tap(roundsActive: Boolean): StatusTap? = when (this) {
            is Arriving -> StatusTap.Balance
            is InRound -> if (roundsActive) StatusTap.Rounds else StatusTap.Balance
            is Rounds -> StatusTap.Rounds
            is Error -> null
        }
    }
    enum class StatusTap { Balance, Rounds }
    fun status(error: String?, balance: com.noahwallet.services.wallet.WalletBalance?, rows: List<com.noahwallet.history.HistoryTransaction>,
        activeRounds: Int): Status? = when {
        error != null -> Status.Error(error)
        balance != null && arriving(balance, rows) > 0uL -> Status.Arriving(arriving(balance, rows))
        balance != null && balance.ark.pendingInRound > 0uL -> Status.InRound(balance.ark.pendingInRound)
        activeRounds > 0 -> Status.Rounds(activeRounds)
        else -> null
    }
    /**
     * Money on its way in, and only that: incoming on-chain transactions not yet confirmed, plus Lightning receives
     * waiting to be claimed. Bark's on-chain pending (BDK's trusted and untrusted pending together) also holds the
     * change of your own unconfirmed spends, so history's unconfirmed incoming transactions pick out the incoming
     * part, never more than that pending total. Sends, boards, offboards, exits and rounds are your own funds moving.
     */
    fun arriving(balance: com.noahwallet.services.wallet.WalletBalance, rows: List<com.noahwallet.history.HistoryTransaction>): ULong {
        val deposits = rows.filter { it.source == "onchain-wallet" && it.hasConfirmation != true }.sumOf { (it.balanceChangeSat ?: 0L).coerceAtLeast(0L) }
        return minOf(deposits.toULong(), balance.onchain.pending) + balance.ark.claimableLightningReceive
    }
    /**
     * What the balance depends on in history, by row: status, confirmation, direction, amounts, balance deltas
     * and fees. Price and block-time enrichment (and the re-sorting it causes) leave it equal, so re-reading the
     * balance whenever it changes can't loop.
     */
    fun accounting(rows: List<com.noahwallet.history.HistoryTransaction>): Map<String, List<Any?>> = rows.associate {
        it.id to listOf(it.movementStatus, it.hasConfirmation, it.confirmationHeight, it.direction, it.amount,
            it.effectiveBalanceSat, it.intendedBalanceSat, it.balanceChangeSat, it.offchainFeeSat, it.onchainFeeSat)
    }
    // "Backup completed" and automatic-boarding completion are toasts now, within today's expiry.
    /** The top bar's activity glyph while a backup or another background task runs: what runs, for TalkBack. */
    fun activity(backup: Banner?, background: String?): String? = backup?.title?.takeIf { it == "Backing up wallet" } ?: background
    /** A backup completed within today's banner expiry: the glyph's check, in place of the old toast. */
    fun backedUpAt(backup: Banner?, state: PersistedBackupState): Long? = state.lastBackupAt?.toLong()?.takeIf { backup?.title == "Backup completed" }
}
