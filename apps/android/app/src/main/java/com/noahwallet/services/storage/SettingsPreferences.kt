package com.noahwallet.services.storage

import kotlinx.serialization.json.*

/** Preserve RN envelope versions and fields that belong to other screens. */
object SettingsPreferences {
    fun state(envelope: String?): JsonObject = runCatching {
        Json.parseToJsonElement(envelope ?: "").jsonObject["state"]!!.jsonObject
    }.getOrDefault(JsonObject(emptyMap()))

    fun preferredCurrency(envelope: String?): String = state(envelope)["preferredCurrency"]?.jsonPrimitive?.contentOrNull
        ?.takeIf { it in com.noahwallet.formatting.FiatCurrency.supported } ?: "USD"

    fun amountUnit(envelope: String?): com.noahwallet.formatting.BitcoinAmountUnit =
        if (state(envelope)["bitcoinAmountUnit"]?.jsonPrimitive?.contentOrNull == "sats")
            com.noahwallet.formatting.BitcoinAmountUnit.sats else com.noahwallet.formatting.BitcoinAmountUnit.bip177

    fun update(envelope: String?, fields: Map<String, JsonElement>, version: Int? = null): String {
        val root = if (envelope == null) JsonObject(emptyMap()) else try {
            Json.parseToJsonElement(envelope).jsonObject.also { require(it["state"] is JsonObject) }
        } catch (_: Exception) { throw RNStoreCodec.Unreadable() }
        return JsonObject(root + ("state" to JsonObject(state(envelope) + fields)) +
            ("version" to (version?.let(::JsonPrimitive) ?: root["version"] ?: JsonPrimitive(0)))).toString()
    }

    fun transaction(envelope: String?): JsonObject {
        if (envelope == null) return JsonObject(emptyMap())
        return try {
            val root = RNStoreCodec.decode("transaction-storage", envelope)
            val state = root.getValue("state").jsonObject
            if ((root["version"]?.jsonPrimitive?.intOrNull ?: 0) < 1)
                JsonObject(state + ("hasAttemptedAutoBoarding" to JsonPrimitive(false))) else state
        } catch (_: RNStoreCodec.Unreadable) {
            buildJsonObject { put("isAutoBoardingEnabled", false); put("hasAttemptedAutoBoarding", true) }
        }
    }

    fun autoBoard(envelope: String?, enabled: Boolean): String {
        val version = runCatching { Json.parseToJsonElement(envelope ?: "").jsonObject["version"]?.jsonPrimitive?.int }.getOrNull() ?: 0
        val fields = mutableMapOf<String, JsonElement>("isAutoBoardingEnabled" to JsonPrimitive(enabled))
        if (enabled || version < 1) fields["hasAttemptedAutoBoarding"] = JsonPrimitive(false)
        return update(envelope, fields, 1)
    }
}
