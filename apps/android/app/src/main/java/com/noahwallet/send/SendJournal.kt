package com.noahwallet.send

import android.util.AtomicFile
import com.noahwallet.services.storage.syncDirectory
import kotlinx.serialization.json.Json
import java.io.File

/** Wallet-private, atomically replaced and fsynced before the payment call. */
class SendJournal(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    @Synchronized fun read(): SendAttempt? {
        val atomic = AtomicFile(file)
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return atomic.openRead().use { stream ->
            require(stream.channel.size() <= 1_048_576)
            json.decodeFromString<SendAttempt>(stream.readBytes().toString(Charsets.UTF_8))
        }
    }
    @Synchronized fun write(attempt: SendAttempt?) {
        val atomic = AtomicFile(file)
        if (attempt == null) {
            atomic.delete(); check(!file.exists() && !File(file.path + ".bak").exists()); syncDirectory(file.parentFile!!)
            return
        }
        check(file.parentFile!!.isDirectory)
        val output = atomic.startWrite()
        try { output.write(json.encodeToString(attempt).toByteArray()); output.fd.sync(); atomic.finishWrite(output); syncDirectory(file.parentFile!!) }
        catch (failure: Exception) { atomic.failWrite(output); throw failure }
    }
}
