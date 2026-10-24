package com.noahwallet.ui.settings

import com.noahwallet.onchain.VtxoModel
import com.noahwallet.recurring.RecurringPayment

/** The live values Settings' rows show, and the small rules behind its gates (docs/native-rewrite/design/settings.md). */
internal object SettingsRules {
    enum class Backup { on, off, running, failed }

    /** Backup's row and mark: a failure or a running backup outranks the automatic switch. */
    fun backup(enabled: Boolean, status: String): Backup = when (status) {
        "failed" -> Backup.failed
        "in_progress" -> Backup.running
        else -> if (enabled) Backup.on else Backup.off
    }

    sealed interface Vtxos {
        data object Unknown : Vtxos
        data object Renew : Vtxos
        data class Count(val count: Int) : Vtxos
    }

    /** "Renew" with a dot while any VTXO is due, else how many there are. */
    fun vtxos(vtxos: List<VtxoModel>?): Vtxos = when {
        vtxos == null -> Vtxos.Unknown
        vtxos.any { it.renewable } -> Vtxos.Renew
        else -> Vtxos.Count(vtxos.size)
    }

    data class Recurring(val approve: Int, val active: Int)

    /** "Approve" with a dot while a payment waits for you, else how many are active. */
    fun recurring(schedules: List<RecurringPayment>) =
        Recurring(schedules.count { it.status == "needs_approval" }, schedules.count { it.status == "active" })

    /** The version line unlocks debug after five taps, each within two seconds of the last. */
    class DebugTaps(private val window: Long = 2_000, private val needed: Int = 5) {
        private var count = 0
        private var last = Long.MIN_VALUE
        /** Taps still needed after this one; 0 unlocks debug and starts over. */
        fun tap(now: Long): Int {
            count = if (last != Long.MIN_VALUE && now - last < window) count + 1 else 1
            last = now
            val left = needed - count
            if (left <= 0) { count = 0; last = Long.MIN_VALUE; return 0 }
            return left
        }
    }
}
