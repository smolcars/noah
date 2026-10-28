package com.noahwallet.services.sharing

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.noahwallet.services.storage.syncDirectory
import java.io.File

/** Only encrypted backups and redacted logs belong here; plaintext stays in ScratchSpace. */
class SettingsShareFiles(private val root: File, private val revoke: (File) -> Unit = {},
    private val syncDirectory: (File) -> Unit = ::syncDirectory) {
    fun retain(source: File): File {
        require(source.extension in setOf("noahbackup", "log"))
        check(root.isDirectory || root.mkdirs())
        sweep()
        val file = File.createTempFile("noah_", ".${source.extension}", root)
        var copied = false
        try { source.copyTo(file, overwrite = true); copied = true; return file }
        finally { if (!copied) discard(file) }
    }

    fun discard(file: File) { revoke(file); file.delete() }

    fun sweep(now: Long = System.currentTimeMillis()) {
        root.listFiles()?.filter { now - it.lastModified() >= retentionMillis }?.forEach(::discard)
    }

    fun deleteAll() {
        if (!root.exists()) return
        val files = root.listFiles() ?: error("""Cannot inspect settings exports""")
        files.forEach { file -> revoke(file); check(file.delete()) { "Cannot delete settings export" } }
        syncDirectory(root)
    }

    companion object {
        const val retentionMillis = 86_400_000L
        fun live(context: Context) = SettingsShareFiles(File(context.cacheDir, "settings-exports"), revoke = { file ->
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }
}

class SettingsShareCleanup(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        SettingsShareFiles.live(applicationContext).sweep()
        return Result.success()
    }
}
