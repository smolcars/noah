package com.noahwallet.ui

import com.noahwallet.ui.settings.ExportFilename
import org.junit.Assert.*
import org.junit.Test
import java.util.Date
import java.util.TimeZone

class ExportFilenameTest {
    @Test fun filenameMatchesRNUtcTimestampWithMilliseconds() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals("noah_backup_1970-01-01T00-00-00-000Z.noahbackup", ExportFilename.database(Date(0)))
            assertEquals("noah_backup_1970-01-01T00-00-01-234Z.noahbackup", ExportFilename.database(Date(1234)))
        } finally { TimeZone.setDefault(previous) }
    }
}
