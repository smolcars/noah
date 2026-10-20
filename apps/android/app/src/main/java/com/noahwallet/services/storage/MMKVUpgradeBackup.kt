package com.noahwallet.services.storage

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Immutable pre-native bytes; a separate retirement record governs automatic restoration. */
internal class MMKVUpgradeBackup(private val root: File, private val id: String,
    private val sync: (File) -> Unit = ::syncDirectory,
    private val backupRoot: File = root.parentFile!!) {
    val directory = File(backupRoot, root.name + ".native-backup")
    val retirementFile = File(backupRoot, root.name + ".native-retired")
    val retiredKeys: List<String>? get() {
        if (!retirementFile.exists()) return null
        val lines = retirementFile.readText().split('\n')
        check(lines.first() == "1")
        return lines.drop(1).filter { it.isNotEmpty() }.also { keys -> check(keys.all { it in RNStoreCodec.versions }) }
    }

    /** Persist admission before native writes; this survives deletion of the support snapshot. */
    fun retire(keys: List<String>) {
        val sorted = keys.sorted()
        if (retiredKeys != sorted) {
            val temporary = File(retirementFile.path + ".pending")
            FileOutputStream(temporary).use { it.write(("1\n" + sorted.joinToString("\n")).toByteArray()); it.fd.sync() }
            check(temporary.renameTo(retirementFile))
        }
        sync(retirementFile)
        sync(retirementFile.parentFile!!)
    }

    private val names = listOf(id, "$id.crc")
    private val manifest get() = File(directory, "manifest")

    fun prepare() {
        if (retiredKeys != null) {
            sync(retirementFile)
            sync(retirementFile.parentFile!!)
            return
        }
        if (!directory.exists()) {
            val staging = File(directory.path + ".pending")
            if (staging.exists()) check(staging.deleteRecursively())
            check(staging.mkdirs())
            val present = names.map { File(root, it).exists() }
            check(present[0] == present[1])
            var size = 0L
            if (present[0]) {
                names.forEach { name ->
                    File(root, name).inputStream().use { input ->
                        FileOutputStream(File(staging, name)).use { output -> input.copyTo(output); output.fd.sync() }
                    }
                }
                val data = File(staging, id).readBytes()
                val meta = File(staging, "$id.crc").readBytes()
                check(data.size >= 4 && meta.size >= 36)
                size = maxOf(uint32(data, 0), uint32(meta, 28), uint32(meta, 32))
            }
            FileOutputStream(File(staging, "manifest")).use {
                it.write("${if (present[0]) 1 else 0}\n$size".toByteArray()); it.fd.sync()
            }
            sync(staging)
            check(staging.renameTo(directory))
            sync(directory.parentFile!!)
        }
        recordedSize()
        sync(directory)
        sync(directory.parentFile!!)
        // An interrupted two-file restore finishes before MMKV maps either file.
        if (File(directory, "restore-pending").exists()) restore()
    }

    val hasContent get() = retiredKeys == null && recordedSize() > 4 && !File(directory, "reset").exists()
    val restored get() = File(directory, "restored").exists()

    fun restore() {
        check(retiredKeys == null && manifest.readLines().first() == "1")
        mark("restore-pending")
        names.forEach { name ->
            val temporary = File(root, "$name.restore")
            File(directory, name).inputStream().use { input ->
                FileOutputStream(temporary).use { output -> input.copyTo(output); output.fd.sync() }
            }
            check(temporary.renameTo(File(root, name)))
        }
        sync(root)
        mark("restored")
        check(File(directory, "restore-pending").delete())
        sync(directory)
    }

    /** Inspect a disposable copy: MMKV recovery must never rewrite the immutable snapshot. */
    fun <T> inspectSnapshot(read: (File) -> T): T {
        val inspection = File(directory, "inspection")
        if (inspection.exists()) check(inspection.deleteRecursively())
        check(inspection.mkdir())
        try {
            names.forEach { File(directory, it).copyTo(File(inspection, it)) }
            return read(inspection)
        } finally { check(inspection.deleteRecursively()) }
    }

    /** Never resurrect a deliberately deleted wallet from the retained backup. */
    fun markReset() = retire(emptyList())

    /** Called after local wallet deletion clears and syncs live settings. Keep retirement. */
    fun deleteSnapshot() {
        listOf(directory, File(directory.path + ".pending")).forEach { check(!it.exists() || it.deleteRecursively()) }
        sync(backupRoot)
    }

    private fun recordedSize(): Long {
        val lines = manifest.readLines()
        check(lines.size == 2 && lines[0] in listOf("0", "1"))
        if (lines[0] == "1") names.forEach { check(File(directory, it).isFile) }
        return lines[1].toLong().also { check(it in 0..0xffffffffL) }
    }

    private fun mark(name: String) {
        FileOutputStream(File(directory, name)).use { it.write(1); it.fd.sync() }
        sync(directory)
    }

    private fun uint32(bytes: ByteArray, offset: Int) =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
}
