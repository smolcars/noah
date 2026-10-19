package com.noahwallet.services.backup

import android.system.ErrnoException
import java.io.File

internal object AtomicBackupMove {
    init { System.loadLibrary("noah_files") }
    private external fun moveExclusive(source: ByteArray, destination: ByteArray): Int
    fun publish(source: File, destination: File) {
        val error = moveExclusive(path(source), path(destination))
        if (error != 0) throw ErrnoException("renameat2", error)
    }
    private fun path(file: File): ByteArray {
        require('\u0000' !in file.absolutePath)
        return file.absolutePath.toByteArray(Charsets.UTF_8) + 0.toByte()
    }
}
