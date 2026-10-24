package com.noahwallet.ui.settings

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object ExportFilename {
    fun database(at: Date = Date()): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss-SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("""UTC""") }
        return "noah_backup_${formatter.format(at)}.noahbackup"
    }
}
