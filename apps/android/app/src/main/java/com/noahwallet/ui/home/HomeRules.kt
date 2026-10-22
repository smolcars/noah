package com.noahwallet.ui.home

import com.noahwallet.services.storage.PersistedBackupState
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import com.noahwallet.formatting.FiatCurrency

object HomeRules {
    private val symbols = mapOf("USD" to "$", "EUR" to "€", "GBP" to "£", "CAD" to "CA$", "CHF" to "CHF", "AUD" to "A$",
        "JPY" to "¥", "BRL" to "R$", "KRW" to "₩", "INR" to "₹", "MXN" to "MX$", "SGD" to "S$")
    fun fiat(sats: Long?, price: Double?, currency: String, locale: Locale, hidden: Boolean): String? =
        if (hidden) "${symbols.getValue(currency)} ••••" else sats?.let { balance -> price?.takeIf { it.isFinite() && it > 0 }?.let { FiatCurrency.display(balance, it, currency, locale) } }
    const val successMillis = 5_000L
    data class Banner(val title: String, val message: String, val failed: Boolean = false, val retry: Boolean = false)
    fun backup(state: PersistedBackupState, now: Long, locale: Locale): Banner? = when {
        state.lastBackupStatus == "in_progress" -> Banner("Backing up wallet", "Running in background")
        state.lastBackupStatus == "failed" -> Banner("Backup failed", "An unknown error occurred while backing up.", failed = true, retry = true)
        !state.backupPending && state.lastBackupStatus == "success" && state.lastBackupAt != null && now - state.lastBackupAt!! < successMillis ->
            Banner("Backup completed", "Last backup ${DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(state.lastBackupAt!!.toLong()))}")
        else -> null
    }
    fun boarding(amount: Long, createdAt: Long, now: Long, locale: Locale): Banner? =
        if (now - createdAt < successMillis) Banner("Boarding completed", "${java.text.NumberFormat.getIntegerInstance(locale).format(amount)} sats available in Ark") else null
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
        val due = vtxos.filter { it.expiring && it.refreshable && !it.expired }
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
    fun nudge(notices: List<String>, renew: Renew?, backup: Banner?, backedUp: Boolean, emailPrompt: Boolean, updateRequired: Boolean): Nudge? = when {
        notices.isNotEmpty() -> Nudge.Notice(notices.first())
        renew != null -> Nudge.Renewal(renew)
        backup?.failed == true -> Nudge.BackupFailed(backup)
        !backedUp -> Nudge.BackUp
        emailPrompt -> Nudge.Email
        updateRequired -> Nudge.Update
        else -> null
    }

    /** At most one line under the fiat, in the spec's order. */
    sealed interface Status {
        data class Error(val message: String) : Status
        data class Arriving(val sats: ULong) : Status
        data class InRound(val sats: ULong) : Status
        data class Rounds(val count: Int) : Status
        data object BackingUp : Status
        data class Background(val message: String) : Status
    }
    fun status(error: String?, balance: com.noahwallet.services.wallet.WalletBalance?, activeRounds: Int, backup: Banner?, background: String?): Status? = when {
        error != null -> Status.Error(error)
        balance != null && balance.pendingSats > 0uL -> Status.Arriving(balance.pendingSats)
        balance != null && balance.ark.pendingInRound > 0uL -> Status.InRound(balance.ark.pendingInRound)
        activeRounds > 0 -> Status.Rounds(activeRounds)
        backup != null && !backup.failed && backup.title == "Backing up wallet" -> Status.BackingUp
        background != null -> Status.Background(background)
        else -> null
    }
    /** "Backup completed" and automatic-boarding completion are toasts now, within today's expiry. */
    fun toast(backup: Banner?, boarding: Banner?): String? =
        backup?.title?.takeIf { it == "Backup completed" } ?: boarding?.title
}
