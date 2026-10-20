package com.noahwallet.send

import android.util.AtomicFile
import com.noahwallet.services.storage.syncDirectory
import kotlinx.serialization.json.*
import java.io.File

/** Wallet-private, atomically replaced and fsynced before the payment call. */
class SendJournal(private val file: File, private val sync: (File) -> Unit = ::syncDirectory) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Synchronized fun read(): SendAttempt? {
        val atomic = AtomicFile(file)
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return atomic.openRead().use { stream ->
            require(stream.channel.size() <= 1_048_576)
            val value = json.parseToJsonElement(stream.readBytes().toString(Charsets.UTF_8)).jsonObject
            json.decodeFromJsonElement<SendAttempt>(value).let { if ("safetyVersion" in value) it else it.copy(safetyVersion = 0) }
        }
    }
    /** Copy first: the active lock must survive restart until audit and removal both succeed. */
    @Synchronized fun quarantineUnreadable() {
        val sources = listOf(file, File(file.path + ".bak"), File(file.path + ".new")).filter { it.exists() }
        check(sources.isNotEmpty())
        for (source in sources) {
            val copy = File(file.parentFile, ".send-evidence-${java.util.UUID.randomUUID()}")
            try {
                source.inputStream().use { input -> java.io.FileOutputStream(copy).use { output ->
                    input.copyTo(output); output.fd.sync()
                } }
                check(copy.renameTo(File(file.parentFile, "${source.name}.unreadable-${java.util.UUID.randomUUID()}")))
                sync(file.parentFile!!)
            } finally { copy.delete() }
        }
    }
    @Synchronized fun write(attempt: SendAttempt?) {
        val atomic = AtomicFile(file)
        if (attempt == null) {
            atomic.delete(); check(!file.exists() && !File(file.path + ".bak").exists()); sync(file.parentFile!!)
            return
        }
        check(file.parentFile!!.isDirectory)
        val output = atomic.startWrite()
        try { output.write(json.encodeToString(attempt).toByteArray()); output.fd.sync(); atomic.finishWrite(output); sync(file.parentFile!!) }
        catch (failure: Exception) { atomic.failWrite(output); throw failure }
    }
}
