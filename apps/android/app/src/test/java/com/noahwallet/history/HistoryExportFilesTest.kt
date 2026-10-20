package com.noahwallet.history

import org.junit.Assert.*
import org.junit.Test

class HistoryExportFilesTest {
    @Test fun exportsExpireWithoutDeletingRecentShares() {
        val root = kotlin.io.path.createTempDirectory().toFile()
        try {
            val files = HistoryExportFiles(root)
            val old = files.create("old"); old.setLastModified(1)
            val recent = files.create("recent")
            files.sweep()
            assertFalse(old.exists()); assertTrue(recent.exists())
            files.sweep(recent.lastModified() + HistoryExportFiles.retentionMillis)
            assertFalse(recent.exists())
        } finally { root.deleteRecursively() }
    }

}
