package com.noahwallet.recurring

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.UUID

/** Wallet-local stop authority; only an explicit wallet resume clears a durable stop. */
internal class RecurringAutomationAuthority(directory: File,
    private val write: (File, ByteArray) -> Unit = { file, bytes -> durableRecurringWrite(file, bytes) }) {
    @Serializable private data class Marker(val version: Int = 1, val instance: String)
    @Serializable data class State(val version: Int = 1, val instance: String, val generation: String,
        val suspended: Boolean = false, val stopped: Boolean = false)
    val file = File(directory, ".noah-recurring-automation.json")
    val marker = File("${file.path}.initialized")
    private fun state(): State {
        check(file.path !in failed) { message }
        try {
            if (!file.exists() && !marker.exists()) {
                val instance = UUID.randomUUID().toString()
                write(file, RecurringStore.json.encodeToString(State(instance = instance, generation = UUID.randomUUID().toString())).toByteArray())
                write(marker, RecurringStore.json.encodeToString(Marker(instance = instance)).toByteArray())
            }
            val marker = RecurringStore.json.decodeFromString<Marker>(marker.readText())
            val raw = file.readText()
            require(RecurringStore.json.parseToJsonElement(raw).jsonObject.keys.containsAll(listOf("version", "instance", "generation", "suspended", "stopped")))
            return RecurringStore.json.decodeFromString<State>(raw).also {
                require(marker.version == 1 && it.version == 1 && marker.instance == it.instance)
                UUID.fromString(it.instance); UUID.fromString(it.generation)
            }
        } catch (failure: Exception) { failed.add(file.path); throw IllegalStateException(message, failure) }
    }
    fun generation(): String = synchronized(lock) {
        state().also { check(!it.suspended && !it.stopped) { message } }.generation
    }
    fun allowed(): Boolean = runCatching { generation() }.isSuccess
    fun stop() = change { it.copy(generation = UUID.randomUUID().toString(), stopped = true) }
    fun suspension(suspended: Boolean) = change {
        it.copy(generation = UUID.randomUUID().toString(), suspended = suspended, stopped = if (suspended) true else it.stopped)
    }
    fun resume() = change { it.copy(generation = UUID.randomUUID().toString(), suspended = false, stopped = false) }
    private fun change(mutate: (State) -> State) = synchronized(lock) {
        try { write(file, RecurringStore.json.encodeToString(mutate(state())).toByteArray()) }
        catch (failure: Exception) { failed.add(file.path); throw failure }
    }
    companion object {
        fun deleted(directory: File) = synchronized(lock) { failed.remove(File(directory, ".noah-recurring-automation.json").path) }
        private val lock = Any()
        private val failed = mutableSetOf<String>()
        const val message = "Wallet automation is durably stopped. Resume the wallet explicitly before sending recurring payments."
    }
}
