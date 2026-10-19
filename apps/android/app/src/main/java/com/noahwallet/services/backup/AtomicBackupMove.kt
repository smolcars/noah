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
 *
 * Callers publish only to fresh, uniquely named paths inside app-private `ScratchSpace` folders:
 * no other writer exists there, and a process death between the two steps leaves at most an
 * empty file that the startup sweep removes (a retry uses a new name). That is why this needs
 * no native `renameat2(RENAME_NOREPLACE)`.
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
