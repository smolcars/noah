package com.noahwallet.history

import com.noahwallet.fixtures.SharedFixtures
import io.github.niteshbalusu11.bark.*
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class HistoryFixtureTest(private val name: String, private val input: JsonObject, private val expected: JsonElement) {
    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun cases() =
            (SharedFixtures.load("transaction-history") + SharedFixtures.load("history-regressions")).map { arrayOf(it.name, it.input.jsonObject, it.expected) } +
                SharedFixtures.load("bark-movement").map { arrayOf(it.name, JsonObject(it.input.jsonObject + ("operation" to JsonPrimitive("status"))), it.expected) }
    }
    private inline fun <reified T> value(v: T): JsonElement = json.encodeToJsonElement(v)
    private inline fun <reified T> read(key: String): T = json.decodeFromJsonElement(input.getValue(key))
    private fun movement(obj: JsonObject): Movement {
        fun destinations(key: String) = obj.getValue(key).jsonArray.map { item ->
            val d = item.jsonObject
            MovementDestination(PaymentMethod(d.string("payment_method") ?: "", d.string("destination") ?: ""), d.getValue("amount_sat").jsonPrimitive.content.toULong())
        }
        fun strings(key: String) = obj.getValue(key).jsonArray.map { it.jsonPrimitive.content }
        val subsystem = obj.getValue("subsystem").jsonObject
        return Movement(obj.getValue("id").jsonPrimitive.content.toUInt(), obj.string("status")!!,
            subsystem.string("name")!!, subsystem.string("kind")!!, obj.string("metadata_json")!!,
            obj.getValue("intended_balance_sat").jsonPrimitive.long, obj.getValue("effective_balance_sat").jsonPrimitive.long,
            obj.getValue("offchain_fee_sat").jsonPrimitive.content.toULong(), destinations("sent_to"), destinations("received_on"),
            strings("input_vtxos"), strings("output_vtxos"), strings("exited_vtxos"), obj.string("created_at")!!,
            obj.string("created_at")!!, null, null, null, null)
    }
    @Test fun matchesEveryReactNativeField() {
        val result = when (read<String>("operation")) {
            "csv" -> value(HistoryCSV.content(read("transactions"), read("currency")))
            "date" -> value(HistoryRules.parseDate(read("date"))?.time)
            "status" -> value(HistoryMapping.isFailedOrCanceled(read("status")))
            "metadata" -> value(HistoryMetadata.parse(read("metadataJson")))
            "transactionID" -> value(HistoryMapping.transactionID(movement(read("movement")), read("metadata"), read("outgoing")))
            "boardAmount" -> value(HistoryMapping.boardAmount(movement(read("movement"))))
            "resolveRepeat" -> value(RepeatPayment.resolve(input["metadata"]?.let { json.decodeFromJsonElement<RepeatPayment>(it) }, input.string("destination"), input.string("paymentMethod"), read("amountSat")))
            "prefill" -> value(read<RepeatPayment>("details").prefill(read("currency")))
            "canRepeat" -> value(read<HistoryTransaction>("transaction").canRepeat)
            "presentation" -> read<HistoryTransaction>("transaction").let {
                buildJsonObject { put("label", it.displayLabel); put("canceled", it.isCanceled); put("internalTransfer", it.isInternalTransfer); put("accounting", value(it.accounting)) }
            }
            "merge" -> value(HistoryRules.merge(read("movements"), read("onchain")))
            "preimage" -> value(HistoryMapping.transaction(movement(read("movement"))).preimage)
            "movement" -> movement(read("movement")).let {
                buildJsonObject { put("included", HistoryMapping.include(it)); put("transaction", value(HistoryMapping.transaction(it))) }
            }
            "onchain" -> read<JsonObject>("onchain").let { c ->
                val chain = WalletTransaction(c.string("txid")!!, c.string("tx_hex")!!,
                    c.getValue("onchain_fee_sat").jsonPrimitive.content.toULong().takeIf { c.getValue("has_onchain_fee").jsonPrimitive.boolean },
                    c.getValue("balance_change_sat").jsonPrimitive.long,
                    if (c.getValue("has_confirmation").jsonPrimitive.boolean) BlockRef(c.getValue("confirmation_height").jsonPrimitive.content.toUInt(), c.string("confirmation_hash")!!) else null, false)
                buildJsonObject { put("included", chain.balanceChangeSats != 0L); put("transaction", value(HistoryMapping.transaction(chain))) }
            }
            "repeatRoute" -> value(RepeatPayment.usesDirectArkRoute(read("routeMethod"), input.string("comment")))
            "newMovement" -> value(RepeatPayment.newArkoorMovementID(read<List<UInt>>("existingMovementIds").toSet(), read<List<JsonObject>>("rawMovements").map(::movement), read("destination"), read("amountSat")))
            "sort" -> value(HistoryRules.sorted(read("transactions")).map { it.id })
            else -> error("Unknown fixture operation")
        }
        // JSON numeric equality ignores integer vs floating notation, like Swift's fixture decoder.
        fun normalize(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.mapValues { normalize(it.value) })
            is JsonArray -> JsonArray(element.map(::normalize))
            is JsonPrimitive -> if (!element.isString && element.doubleOrNull != null) JsonPrimitive(element.double) else element
        }
        assertEquals(name, normalize(expected), normalize(result))
    }
}
