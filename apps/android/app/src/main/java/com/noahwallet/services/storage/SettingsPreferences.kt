package com.noahwallet.services.storage

import kotlinx.serialization.json.*

/** Preserve RN envelope versions and fields that belong to other screens. */
object SettingsPreferences {
    fun state(envelope: String?): JsonObject = runCatching {
        Json.parseToJsonElement(envelope ?: "").jsonObject["state"]!!.jsonObject
    }.getOrDefault(JsonObject(emptyMap()))

    fun update(envelope: String?, fields: Map<String, JsonElement>, version: Int? = null): String {
        val root = runCatching { Json.parseToJsonElement(envelope ?: "").jsonObject }.getOrDefault(JsonObject(emptyMap()))
        return JsonObject(root + ("state" to JsonObject(state(envelope) + fields)) +
            ("version" to (version?.let(::JsonPrimitive) ?: root["version"] ?: JsonPrimitive(0)))).toString()
    }

    fun autoBoard(envelope: String?, enabled: Boolean): String {
        val version = runCatching { Json.parseToJsonElement(envelope ?: "").jsonObject["version"]?.jsonPrimitive?.int }.getOrNull() ?: 0
        val fields = mutableMapOf<String, JsonElement>("isAutoBoardingEnabled" to JsonPrimitive(enabled))
        if (enabled || version < 1) fields["hasAttemptedAutoBoarding"] = JsonPrimitive(false)
        return update(envelope, fields, 1)
    }
}
