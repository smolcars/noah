package com.noahwallet.history

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File

/** Share receivers may read asynchronously; expire successful exports after one day. */
class HistoryExportFiles(private val root: File) {
    fun sweep(now: Long = System.currentTimeMillis()) {
        root.listFiles()?.filter { now - it.lastModified() >= retentionMillis }?.forEach { it.delete() }
    }
    fun create(content: String): File {
        check(root.isDirectory || root.mkdirs())
        sweep()
        val file = File.createTempFile("noah_transactions_", ".csv", root)
        try { file.writeText(content, Charsets.UTF_8); return file }
        catch (failure: Exception) { file.delete(); throw failure }
    }
    companion object { const val retentionMillis = 86_400_000L }
}

class HistoryExportCleanup(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        HistoryExportFiles(File(applicationContext.cacheDir, "history-exports")).sweep()
        return Result.success()
    }
}
