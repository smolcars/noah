package com.noahwallet.ui

import com.noahwallet.services.logging.SafeErrorDescription
import com.noahwallet.services.wallet.DebugJSON
import io.github.niteshbalusu11.bark.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DebugJSONTest {
    @Test fun decodedResultsKeepNestedFieldsAndUnsignedAmounts() {
        val vtxo = Vtxo("public", 12u, OutPoint("anchor", 1u), OutPoint("point", 2u), "id", ULong.MAX_VALUE,
            100u, "pubkey", VtxoState.Unknown, 1u, 42uL, false)
        val text = DebugJSON.decoded(vtxo)
        val result = Json.parseToJsonElement(text).jsonObject
        assertTrue(text.contains("\n  \"amountSats\": 18446744073709551615"))
        assertEquals("point", result["point"]!!.jsonObject["txid"]!!.jsonPrimitive.content)
        assertEquals("unknown", result["state"]!!.jsonPrimitive.content)
    }
    @Test fun debugInfoShowsExactlyTheThreeSupportIdentifiers() {
        val text = DebugJSON.debugInfo(DebugInfo(Network.REGTEST, "mailbox", "tpub-test"))
        assertEquals(mapOf("network" to "regtest", "mailboxId" to "mailbox", "vtxoXpub" to "tpub-test"),
            Json.parseToJsonElement(text).jsonObject.mapValues { it.value.jsonPrimitive.content })
    }
    @Test fun errorsUseOnlyRedactedSDKTextOrProviderType() {
        assertEquals("[redacted]", SafeErrorDescription.of(BarkError("token=fake-test-token")))
        assertEquals("IllegalStateException", SafeErrorDescription.of(IllegalStateException("private request input")))
    }
}
