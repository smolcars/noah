package com.noahwallet.services.storage

import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** One committed generation. Pending files are never read or used as a fallback. */
internal class NativeSettingsFile(val file: File,
    private val sync: (File) -> Unit = ::syncDirectory,
    private val step: (String) -> Unit = {}) {
    data class State(val values: Map<String, String> = emptyMap(), val settingsNoticePending: Boolean = false,
        val recurringNoticePending: Boolean = false, val cleanupPending: Boolean = false)
    val exists get() = file.exists()

    fun load(): State {
        val root = Json.parseToJsonElement(RNStoreCodec.storedString(true, file.readBytes())!!).jsonObject
        check(root.keys == setOf("version", "payload", "sha256"))
        check(root.getValue("version").toString() == "1")
        fun string(value: JsonElement): String = value.jsonPrimitive.let { check(it.isString); it.content }
        val payload = string(root.getValue("payload"))
        check(string(root.getValue("sha256")) == digest(payload))
        val state = Json.parseToJsonElement(payload).jsonObject
        check(state.keys == setOf("values", "settingsNoticePending", "recurringNoticePending", "cleanupPending"))
        val values = state.getValue("values").jsonObject.mapValues { string(it.value) }
        fun boolean(key: String): Boolean = state.getValue(key).jsonPrimitive.let { check(!it.isString); it.boolean }
        return State(values, boolean("settingsNoticePending"), boolean("recurringNoticePending"), boolean("cleanupPending"))
    }

    fun save(state: State) {
        val payload = buildJsonObject {
            put("values", JsonObject(state.values.toSortedMap().mapValues { JsonPrimitive(it.value) }))
            put("settingsNoticePending", state.settingsNoticePending)
            put("recurringNoticePending", state.recurringNoticePending)
            put("cleanupPending", state.cleanupPending)
        }.toString()
        val data = buildJsonObject { put("version", 1); put("payload", payload); put("sha256", digest(payload)) }.toString()
        val pending = File(file.path + ".pending")
        FileOutputStream(pending).use { it.write(data.toByteArray(Charsets.UTF_8)) }
        step("write")
        sync(pending)
        step("fileSync")
        check(pending.renameTo(file))
        step("rename")
        sync(file.parentFile!!)
        step("directorySync")
    }

    private fun digest(payload: String) = MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
