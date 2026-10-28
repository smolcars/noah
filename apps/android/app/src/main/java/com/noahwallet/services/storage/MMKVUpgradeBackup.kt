package com.noahwallet.services.storage

import java.io.File
import java.io.FileOutputStream

/** Immutable pre-native bytes for the one-time best-effort import. */
internal class MMKVUpgradeBackup(private val root: File, private val id: String,
    private val sync: (File) -> Unit = ::syncDirectory,
    private val backupRoot: File = root.parentFile!!) {
    val directory = File(backupRoot, root.name + ".native-backup")
    private val names = listOf(id, "$id.crc")
    private val manifest get() = File(directory, "manifest")

    fun prepare() {
        if (!directory.exists()) stage()
        recordedSize()
        sync(directory)
        sync(directory.parentFile!!)
    }

    private fun stage() {
        val staging = File(directory.path + ".pending")
        if (staging.exists()) check(staging.deleteRecursively())
        check(staging.mkdirs())
        val present = names.map { File(root, it).exists() }
        if (present.any { it }) {
            names.filter { File(root, it).exists() }.forEach { name -> copySynced(File(root, name), File(staging, name)) }
        }
        FileOutputStream(File(staging, "manifest")).use {
            it.write("${if (present.any { it }) 1 else 0}\n0".toByteArray()); it.fd.sync()
        }
        sync(staging)
        check(staging.renameTo(directory))
        sync(directory.parentFile!!)
    }

    private fun copySynced(source: File, target: File) = source.inputStream().use { input ->
        FileOutputStream(target).use { output -> input.copyTo(output); output.fd.sync() }
    }

    val hasContent get() = manifest.readLines().first() == "1"

    /** Inspect a disposable copy: MMKV recovery must never rewrite the immutable snapshot. */
    fun <T> inspectSnapshot(read: (File) -> T): T {
        val inspection = File(directory, "inspection")
        if (inspection.exists()) check(inspection.deleteRecursively())
        check(inspection.mkdir())
        try {
            names.filter { File(directory, it).exists() }.forEach { File(directory, it).copyTo(File(inspection, it)) }
            return read(inspection)
        } finally { check(inspection.deleteRecursively()) }
    }

    /** Native store existence prevents a second import after deletion. */
    fun deleteSnapshot() {
        listOf(directory, File(directory.path + ".pending")).forEach { check(!it.exists() || it.deleteRecursively()) }
        sync(backupRoot)
    }

    private fun recordedSize(): Long {
        val lines = manifest.readLines()
        check(lines.size == 2 && lines[0] in listOf("0", "1"))
        return lines[1].toLong().also { check(it in 0..0xffffffffL) }
    }
}
