package com.noahwallet.services.storage

import java.io.File
import java.io.FileOutputStream

/** Written before MMKV can rewrite recovered bytes; never contains settings or secrets. */
internal class MMKVQuarantine(directory: File, private val store: String,
    private val sync: (File) -> Unit = ::syncDirectory) {
    val file = File(directory, "mmkv.native-quarantine")
    @Volatile var writeFailed = false; private set
    val exists get() = file.exists()
    private val record get() = "1\n$store\n"

    fun recordRecovery(): Boolean {
        try {
            val pending = File(file.path + ".pending")
            FileOutputStream(pending).use { it.write(record.toByteArray()); it.fd.sync() }
            check(pending.renameTo(file))
            sync(file); sync(file.parentFile!!)
        } catch (_: Exception) { writeFailed = true }
        return !writeFailed
    }

    fun requireDurable() {
        check(!writeFailed)
        if (exists) {
            check(file.readText() == record)
            sync(file); sync(file.parentFile!!)
        }
    }

    /** Only a validated first-open restoration or an explicit foreground acknowledgement calls this. */
    fun clear() {
        requireDurable()
        if (!exists) return
        try { check(file.delete()); sync(file.parentFile!!) }
        catch (failure: Exception) { recordRecovery(); throw failure }
    }
}
