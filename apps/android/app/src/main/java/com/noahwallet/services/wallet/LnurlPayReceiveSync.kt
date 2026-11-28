package com.noahwallet.services.wallet

import com.noahwallet.api.generated.models.LnurlPayReceiveMetadata
import com.noahwallet.history.LnurlPayReceive
import io.github.niteshbalusu11.bark.Movement
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Moves the payer data the server matched to Ark receives into Bark's movements (docs/lnurl_pay.md): lists ready
 * records, finds the successful Arkoor receive with exactly each record's output VTXOs, writes `noah.lnurl_pay`, reads
 * it back, makes sure a backup follows, and only then acknowledges, which deletes the server's copy. A record whose
 * movement hasn't synced yet, or whose target this version doesn't know, waits for a later pass. Never matched by
 * amount or time. One pass at a time; a pass already running makes another return.
 */
class LnurlPayReceiveSync(
    private val list: suspend () -> List<LnurlPayReceiveMetadata>,
    private val movements: suspend () -> List<Movement>,
    private val write: suspend (movementId: UInt, patchJson: String) -> Unit,
    private val backup: suspend () -> Unit,
    private val acknowledge: suspend (ids: List<String>) -> Unit,
) {
    private val running = Mutex()

    /** The records this pass acknowledged. */
    suspend fun run(): List<String> {
        if (!running.tryLock()) return emptyList()
        try {
            val records = list()
            if (records.isEmpty()) return emptyList()
            val wanted = mutableMapOf<String, Pair<UInt, LnurlPayReceive>>()
            var wrote = false
            var history = movements()
            for (record in records) {
                val movement = receive(record, history) ?: continue
                val metadata = LnurlPayReceive.of(record.payerData?.name, record.payerData?.identifier, record.comment)
                if (LnurlPayReceive.parse(movement.metadataJson) != metadata) {
                    write(movement.id, metadata.patch())
                    wrote = true
                }
                wanted[record.id] = movement.id to metadata
            }
            if (wrote) history = movements()
            val stored = wanted.filter { (_, value) ->
                val (id, metadata) = value
                LnurlPayReceive.parse(history.firstOrNull { it.id == id }?.metadataJson) == metadata
            }.keys.toList()
            if (stored.isEmpty()) return emptyList()
            if (wrote) backup()
            acknowledge(stored)
            return stored
        } finally { running.unlock() }
    }

    /** Another foreground pass may have stored the exact movement while this trigger yielded its slot. */
    suspend fun isStored(movement: Movement): Boolean = movements().any {
        it.id == movement.id && it.outputVtxoIds.toSet() == movement.outputVtxoIds.toSet() && LnurlPayReceive.parse(it.metadataJson) != null
    }

    companion object {
        fun needsMetadata(movement: Movement): Boolean = movement.isArkoorReceive() &&
            movement.outputVtxoIds.isNotEmpty() && LnurlPayReceive.parse(movement.metadataJson) == null

        /** Empty/busy passes retry too; no foreground slot is held during a delay. */
        suspend fun retryReceive(sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }, attempt: suspend () -> Boolean) {
            for (wait in listOf(0L, 2_000L, 5_000L)) {
                sleep(wait)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (attempt()) return
            }
        }

        /** The record's `ark_output_vtxos` target; null for a target this version doesn't handle. */
        fun arkOutputs(record: LnurlPayReceiveMetadata): Set<String>? {
            val target = record.target as? JsonObject ?: return null
            if ((target["type"]?.jsonPrimitive?.contentOrNull) != "ark_output_vtxos") return null
            val ids = (target["vtxo_ids"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
            return ids?.toSet()?.takeIf { it.isNotEmpty() }
        }

        /** The successful Arkoor receive with exactly the record's output VTXOs. */
        private fun receive(record: LnurlPayReceiveMetadata, history: List<Movement>): Movement? {
            val outputs = arkOutputs(record) ?: return null
            return history.singleOrNull { it.isArkoorReceive() && it.outputVtxoIds.toSet() == outputs }
        }

        private fun Movement.isArkoorReceive() = subsystemName.equals("bark.arkoor", ignoreCase = true) &&
            subsystemKind.equals("receive", ignoreCase = true) && status == "successful"
    }
}
