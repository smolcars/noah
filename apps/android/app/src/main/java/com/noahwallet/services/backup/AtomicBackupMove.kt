package com.noahwallet.services.backup

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Publishes a complete file under `destination` without ever replacing an existing file.
 * `createNewFile` claims the name atomically (O_CREAT|O_EXCL) and fails if anything is there;
 * `Os.rename` then swaps the complete file in over that empty placeholder in one step. Hard
 * links (the usual no-replace trick) are denied in Android's app sandbox.
 */
internal object AtomicBackupMove {
    fun publish(source: File, destination: File) {
        if (!destination.createNewFile()) throw ErrnoException("publish", OsConstants.EEXIST)
        try {
            Os.rename(source.absolutePath, destination.absolutePath)
        } catch (failure: ErrnoException) {
            // Only ever our own empty placeholder.
            if (destination.length() == 0L) destination.delete()
            throw failure
        }
    }
}
