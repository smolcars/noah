package com.noahwallet.services.wallet

import io.github.niteshbalusu11.bark.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import java.util.Locale

/** Explicit public debug fields, never a wallet handle or exception object. */
@OptIn(ExperimentalSerializationApi::class)
internal object DebugJSON {
    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }
    fun ark(info: ArkInfo?): String = format(info?.let(::value) ?: JsonNull)
    fun decoded(vtxo: Vtxo): String = format(value(vtxo))
    fun imported(ids: List<String>): String = format(JsonArray(ids.map(::JsonPrimitive)))
    /** The VTXO xpub reveals every VTXO public key of the wallet: display only, never log. */
    fun debugInfo(info: DebugInfo): String = format(buildJsonObject {
        put("mailboxId", JsonPrimitive(info.mailboxId))
        put("network", JsonPrimitive(info.network.name.lowercase(Locale.ROOT)))
        put("vtxoXpub", JsonPrimitive(info.vtxoXpub))
    })
    fun format(value: JsonElement): String = json.encodeToString(JsonElement.serializer(), value)
    private fun value(result: ArkInfo): JsonObject = buildJsonObject {
        put("mailboxPubkey", JsonPrimitive(result.mailboxPubkey))
        put("network", JsonPrimitive(result.network.name.lowercase(Locale.ROOT)))
        put("serverPubkey", JsonPrimitive(result.serverPubkey))
        put("roundIntervalSecs", JsonUnquotedLiteral(result.roundIntervalSecs.toString()))
        put("nbRoundNonces", JsonUnquotedLiteral(result.nbRoundNonces.toString()))
        put("vtxoExitDelta", JsonUnquotedLiteral(result.vtxoExitDelta.toString()))
        put("vtxoLifetime", JsonUnquotedLiteral(result.vtxoLifetime.toString()))
        put("vtxoExpiryDelta", JsonUnquotedLiteral(result.vtxoExpiryDelta.toString()))
        put("htlcSendExpiryDelta", JsonUnquotedLiteral(result.htlcSendExpiryDelta.toString()))
        put("htlcExpiryDelta", JsonUnquotedLiteral(result.htlcExpiryDelta.toString()))
        put("maxVtxoAmountSats", result.maxVtxoAmountSats?.let { JsonUnquotedLiteral(it.toString()) } ?: JsonNull)
        put("requiredBoardConfirmations", JsonUnquotedLiteral(result.requiredBoardConfirmations.toString()))
        put("maxUserInvoiceCltvDelta", JsonUnquotedLiteral(result.maxUserInvoiceCltvDelta.toString()))
        put("minBoardAmountSats", JsonUnquotedLiteral(result.minBoardAmountSats.toString()))
        put("lnReceiveAntiDosRequired", JsonPrimitive(result.lnReceiveAntiDosRequired))
        put("feeSchedule", value(result.feeSchedule))
        put("maxVtxoExitDepth", JsonUnquotedLiteral(result.maxVtxoExitDepth.toString()))
    }
    private fun value(result: Vtxo): JsonObject = buildJsonObject {
        put("serverPubkey", JsonPrimitive(result.serverPubkey))
        put("exitDelta", JsonUnquotedLiteral(result.exitDelta.toString()))
        put("anchorPoint", value(result.anchorPoint))
        put("point", value(result.point))
        put("id", JsonPrimitive(result.id))
        put("amountSats", JsonUnquotedLiteral(result.amountSats.toString()))
        put("expiryHeight", JsonUnquotedLiteral(result.expiryHeight.toString()))
        put("kind", JsonPrimitive(result.kind))
        put("state", state(result.state))
        put("exitDepth", JsonUnquotedLiteral(result.exitDepth.toString()))
        put("exitTxWeightWu", JsonUnquotedLiteral(result.exitTxWeightWu.toString()))
        put("registered", JsonPrimitive(result.registered))
    }
    private fun value(result: OutPoint): JsonObject = buildJsonObject {
        put("txid", JsonPrimitive(result.txid))
        put("vout", JsonUnquotedLiteral(result.vout.toString()))
    }
    private fun value(result: FeeSchedule): JsonObject = buildJsonObject {
        put("board", value(result.board))
        put("offboard", value(result.offboard))
        put("refresh", value(result.refresh))
        put("lightningReceive", value(result.lightningReceive))
        put("lightningSend", value(result.lightningSend))
    }
    private fun value(result: BoardFees): JsonObject = buildJsonObject {
        put("minFeeSats", JsonUnquotedLiteral(result.minFeeSats.toString()))
        put("baseFeeSats", JsonUnquotedLiteral(result.baseFeeSats.toString()))
        put("ppm", JsonUnquotedLiteral(result.ppm.toString()))
    }
    private fun value(result: OffboardFees): JsonObject = buildJsonObject {
        put("baseFeeSats", JsonUnquotedLiteral(result.baseFeeSats.toString()))
        put("fixedAdditionalVb", JsonUnquotedLiteral(result.fixedAdditionalVb.toString()))
        put("ppmExpiryTable", JsonArray(result.ppmExpiryTable.map(::value)))
    }
    private fun value(result: RefreshFees): JsonObject = buildJsonObject {
        put("baseFeeSats", JsonUnquotedLiteral(result.baseFeeSats.toString()))
        put("ppmExpiryTable", JsonArray(result.ppmExpiryTable.map(::value)))
    }
    private fun value(result: LightningReceiveFees): JsonObject = buildJsonObject {
        put("baseFeeSats", JsonUnquotedLiteral(result.baseFeeSats.toString()))
        put("ppm", JsonUnquotedLiteral(result.ppm.toString()))
    }
    private fun value(result: LightningSendFees): JsonObject = buildJsonObject {
        put("minFeeSats", JsonUnquotedLiteral(result.minFeeSats.toString()))
        put("baseFeeSats", JsonUnquotedLiteral(result.baseFeeSats.toString()))
        put("ppmExpiryTable", JsonArray(result.ppmExpiryTable.map(::value)))
    }
    private fun value(result: PpmExpiryFeeEntry): JsonObject = buildJsonObject {
        put("expiryBlocksThreshold", JsonUnquotedLiteral(result.expiryBlocksThreshold.toString()))
        put("ppm", JsonUnquotedLiteral(result.ppm.toString()))
    }
    private fun state(state: VtxoState): JsonPrimitive = JsonPrimitive(when (state) {
        is VtxoState.Unknown -> "unknown"
        is VtxoState.Spendable -> "spendable"
        is VtxoState.Spent -> "spent"
        is VtxoState.Exited -> "exited"
        is VtxoState.Locked -> "locked"
    })
}
