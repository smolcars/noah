package com.noahwallet.history

import com.noahwallet.api.generated.models.LnurlPayReceiveMetadata
import com.noahwallet.api.generated.models.LnurlPayReceiveMetadataPayerData
import com.noahwallet.fixtures.SharedFixtures
import com.noahwallet.services.wallet.LnurlPayReceiveSync
import com.noahwallet.ui.home.HomeRow
import io.github.niteshbalusu11.bark.Movement
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** LNURL-pay payer data on received payments: the shared fixtures, the pass that stores it, and History's rows. */
class LnurlPayReceiveTest {
    @Test fun fixture() {
        for (case in SharedFixtures.load("lnurl-pay-receive-metadata")) {
            val input = case.input.jsonObject
            fun JsonObject.text(key: String) = get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
            when (input.getValue("kind").jsonPrimitive.content) {
                "patch" -> {
                    val payer = input["payer_data"]?.takeUnless { it is JsonNull }?.jsonObject
                    val metadata = LnurlPayReceive.of(payer?.text("name"), payer?.text("identifier"), input.text("comment"))
                    assertEquals(case.name, case.expected, Json.parseToJsonElement(metadata.patch()))
                }
                else -> {
                    val parsed = LnurlPayReceive.parse(input.getValue("metadata").jsonPrimitive.content)
                    if (case.expected is JsonNull) assertNull(case.name, parsed)
                    else {
                        val expected = case.expected.jsonObject
                        assertEquals(case.name, LnurlPayReceive(expected.text("name"), expected.text("identifier"), expected.text("comment")),
                            parsed)
                        assertEquals(case.name, expected.text("label"), parsed?.payerLabel)
                    }
                }
            }
        }
    }

    private val outputs = listOf("aa:0", "bb:1")

    private fun movement(id: UInt, outputs: List<String>, metadata: String = "{}", status: String = "successful",
        subsystem: String = "bark.arkoor") = Movement(id, status, subsystem, "receive", metadata, 9_880, 9_880, 0uL, emptyList(),
        emptyList(), emptyList(), outputs, emptyList(), "2026-11-27T09:12:00Z", "2026-11-27T09:12:00Z", null, null, null, null)

    private fun record(id: String, outputs: List<String>, name: String? = "Alice", comment: String? = "Thanks") = LnurlPayReceiveMetadata(
        amountSat = 9_880, comment = comment, id = id, payerData = LnurlPayReceiveMetadataPayerData(identifier = null, name = name),
        target = buildJsonObject {
            put("type", "ark_output_vtxos")
            putJsonArray("vtxo_ids") { outputs.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        })

    /** A wallet whose movements take the patches written, and the acknowledgements and backups the pass made. */
    private class Wallet(var movements: List<Movement>) {
        val writes = mutableListOf<Pair<UInt, String>>()
        val acknowledged = mutableListOf<List<String>>()
        var backups = 0
        fun sync(records: List<LnurlPayReceiveMetadata>) = LnurlPayReceiveSync(
            list = { records },
            movements = { movements },
            write = { id, patch ->
                writes += id to patch
                movements = movements.map { if (it.id == id) it.copy(metadataJson = patch) else it }
            },
            backup = { backups++ },
            acknowledge = { acknowledged += it },
        )
    }

    @Test fun storesEachRecordOnTheReceiveWithExactlyItsOutputsThenAcknowledges() = runBlocking {
        val wallet = Wallet(listOf(movement(1u, listOf("aa:0")), movement(2u, outputs.reversed()), movement(3u, outputs, status = "pending")))
        val stored = wallet.sync(listOf(record("r1", outputs))).run()

        assertEquals(listOf("r1"), stored)
        assertEquals(listOf(2u), wallet.writes.map { it.first })
        assertEquals(LnurlPayReceive("Alice", null, "Thanks"), LnurlPayReceive.parse(wallet.movements[1].metadataJson))
        assertEquals(1, wallet.backups)
        assertEquals(listOf(listOf("r1")), wallet.acknowledged)
    }

    @Test fun aRecordWhoseReceiveHasNotSyncedWaits() = runBlocking {
        val wallet = Wallet(listOf(movement(1u, listOf("aa:0"))))
        // A partial match is not the payment either.
        assertTrue(wallet.sync(listOf(record("r1", outputs))).run().isEmpty())
        assertTrue(wallet.writes.isEmpty() && wallet.acknowledged.isEmpty())

        val unknown = record("r2", outputs).copy(target = buildJsonObject { put("type", "lightning_payment_hash"); put("payment_hash", "00") })
        assertNull(LnurlPayReceiveSync.arkOutputs(unknown))
    }

    @Test fun anAlreadyStoredRecordIsAcknowledgedWithoutAnotherWrite() = runBlocking {
        val metadata = LnurlPayReceive.of("Alice", null, "Thanks").patch()
        val wallet = Wallet(listOf(movement(2u, outputs, metadata)))
        assertEquals(listOf("r1"), wallet.sync(listOf(record("r1", outputs))).run())
        assertTrue(wallet.writes.isEmpty())
        assertEquals(0, wallet.backups)
    }

    @Test fun aWriteThatDidNotStickIsNotAcknowledged() = runBlocking {
        val wallet = Wallet(listOf(movement(2u, outputs)))
        val sync = LnurlPayReceiveSync({ listOf(record("r1", outputs)) }, { wallet.movements }, { _, _ -> }, { wallet.backups++ },
            { wallet.acknowledged += it })
        assertTrue(sync.run().isEmpty())
        assertTrue(wallet.acknowledged.isEmpty())
    }

    @Test fun historyNamesThePayerOnIncomingRows() {
        val metadata = LnurlPayReceive.of("Alice Payer", "alice@example.com", "Thanks").patch()
        val row = HistoryMapping.transaction(movement(2u, outputs, metadata))
        assertEquals(LnurlPayReceive("Alice Payer", "alice@example.com", "Thanks"), row.lnurlPay)
        val home = HomeRow.of(row, "noahwallet.io")
        assertEquals("Alice Payer", home.title)
        // It arrived over Ark: the row and Details' "Via" agree.
        assertEquals("Ark", home.kind)
        assertEquals(com.noahwallet.ui.activity.ActivityRules.Rail.ark, com.noahwallet.ui.activity.ActivityRules.rail(row))
        assertEquals(row.lnurlPay, com.noahwallet.ui.activity.ActivityRules.payer(row))
        assertNull(com.noahwallet.ui.activity.ActivityRules.payer(row.copy(direction = "outgoing")))
        // Nothing stored: an ordinary Ark receive.
        assertNull(HistoryMapping.transaction(movement(2u, outputs)).lnurlPay)
    }
}
