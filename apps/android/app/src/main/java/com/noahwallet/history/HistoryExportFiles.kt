package com.noahwallet.history

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.noahwallet.services.storage.syncDirectory
import java.io.File

/** Share receivers may read asynchronously; expire successful exports after one day. */
class HistoryExportFiles(private val root: File, private val revoke: (File) -> Unit = {},
    private val syncDirectory: (File) -> Unit = ::syncDirectory) {
    fun sweep(now: Long = System.currentTimeMillis()) {
        root.listFiles()?.filter { now - it.lastModified() >= retentionMillis }?.forEach { revoke(it); it.delete() }
    }
    fun create(content: String): File {
        check(root.isDirectory || root.mkdirs())
        sweep()
        val file = File.createTempFile("noah_transactions_", ".csv", root)
        runCatching { file.writeText(content, Charsets.UTF_8) }.onFailure { file.delete() }.getOrThrow()
        return file
    }
    fun deleteAll() {
        if (!root.exists()) return
        val files = root.listFiles() ?: error("""Cannot inspect history exports""")
        files.forEach { file -> revoke(file); check(file.delete()) { """Cannot delete history export""" } }
        syncDirectory(root)
    }
    companion object {
        const val retentionMillis = 86_400_000L
        fun live(context: Context) = HistoryExportFiles(File(context.cacheDir, "history-exports"), revoke = { file ->
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            context.revokeUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }
}

class HistoryExportCleanup(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        HistoryExportFiles.live(applicationContext).sweep()
        return Result.success()
    }
}
