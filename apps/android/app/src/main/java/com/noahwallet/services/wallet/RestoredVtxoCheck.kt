package com.noahwallet.services.wallet

import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * A restored backup can be older than the wallet it came from: VTXOs it still lists as spendable may have been spent
 * since, and bark's sync never asks the server about VTXOs it holds as spendable. A restore leaves a marker in the
 * datadir, and the first foreground pass syncs, then asks the server about each spendable VTXO and adopts "spent"
 * (bark's `trust_and_adopt_server_vtxo_status`), before delegated maintenance can try to refresh one. A pass that
 * leaves a VTXO unanswered keeps the marker, with its pass count, for the next pass, up to [maxPasses].
 */
internal object RestoredVtxoCheck {
    const val maxPasses = 5

    class Steps(
        val sync: suspend () -> Unit,
        val spendableIds: suspend () -> List<String>,
        /** Asks the server about one VTXO and adopts its answer; throws when there is no usable answer. */
        val adopt: suspend (String) -> Unit,
    )

    /** Returns how many VTXOs went unanswered: 0 when nothing was due or every one was answered. */
    suspend fun run(marker: File, steps: Steps, warn: (String) -> Unit = {}): Int {
        if (!marker.exists()) return 0
        steps.sync()
        var unanswered = 0
        for (id in steps.spendableIds()) {
            try { steps.adopt(id) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { unanswered++ }
        }
        val passes = (marker.readText().toIntOrNull() ?: 0) + 1
        if (unanswered == 0 || passes >= maxPasses) {
            if (unanswered > 0) warn("Stopped checking $unanswered restored VTXOs with the server after $passes passes")
            check(marker.delete()) { "Cannot remove the restored VTXO check" }
        } else {
            marker.writeText(passes.toString())
        }
        return unanswered
    }
}
