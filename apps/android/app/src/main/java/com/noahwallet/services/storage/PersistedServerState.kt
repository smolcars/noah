package com.noahwallet.services.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** RN server-storage, including nullable identity and optional preferences from older installs. */
@Serializable
data class PersistedServerState(
    val isRegisteredWithServer: Boolean? = null,
    val lightningAddress: String? = null,
    val nostrPubkey: String? = null,
    val isBackupEnabled: Boolean? = null,
    val emailAddress: String? = null,
    val isEmailVerified: Boolean? = null,
    val isEmailPromptDismissed: Boolean? = null,
    val mailboxAuthorizationExpiry: Double? = null,
    val isMailboxAuthorizationEnabled: Boolean? = null,
    val lastReportedDeviceFingerprint: String? = null,
    val lastDeviceReportAt: Double? = null,
) {
    companion object {
        const val key = "server-storage"
        const val version = 0
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val booleans = setOf("isRegisteredWithServer", "isBackupEnabled", "isEmailVerified",
            "isEmailPromptDismissed", "isMailboxAuthorizationEnabled")
        private val numbers = setOf("mailboxAuthorizationExpiry", "lastDeviceReportAt")

        fun decode(envelope: String?): PersistedServerState {
            if (envelope == null) return PersistedServerState()
            return runCatching {
                val state = json.parseToJsonElement(envelope).jsonObject.getValue("state").jsonObject
                val valid = state.filter { (key, value) ->
                    value == JsonNull || (value is JsonPrimitive && when (key) {
                        in booleans -> !value.isString && value.booleanOrNull != null
                        in numbers -> !value.isString && value.doubleOrNull?.isFinite() == true
                        else -> value.isString
                    })
                }
                json.decodeFromJsonElement<PersistedServerState>(JsonObject(valid))
            }.getOrDefault(PersistedServerState())
        }

        fun encode(state: PersistedServerState): String {
            val fields = json.encodeToJsonElement(state).jsonObject.filterNot { (key, value) ->
                key in booleans && value == JsonNull
            }
            return buildJsonObject {
                put("state", JsonObject(fields))
                put("version", version)
            }.toString()
        }
    }
}

/** Re-read on mutation so identity updates retain the latest mailbox/user preferences. */
class ServerStateStorage(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
) {
    constructor(settings: SettingsStorage) : this(settings::string, { key, value -> settings.set(value, key) })
    val state: PersistedServerState get() = synchronized(lock) { PersistedServerState.decode(read(PersistedServerState.key)) }

    fun update(mutation: (PersistedServerState) -> PersistedServerState): PersistedServerState = synchronized(lock) {
        mutation(state).also { write(PersistedServerState.key, PersistedServerState.encode(it)) }
    }

    fun setDisplayName(name: String) = synchronized(lock) {
        val envelope = read("profile-storage")?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val state = (envelope?.get("state") as? JsonObject).orEmpty()
        write("profile-storage", buildJsonObject {
            envelope?.forEach { (key, value) -> put(key, value) }
            put("state", JsonObject(state + ("displayName" to JsonPrimitive(name))))
            put("version", envelope?.get("version") ?: JsonPrimitive(0))
        }.toString())
    }

    companion object { private val lock = Any() }
}
