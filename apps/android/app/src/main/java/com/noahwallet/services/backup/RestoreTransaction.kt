package com.noahwallet.services.backup

import android.system.Os
import android.system.OsConstants
import com.noahwallet.services.storage.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

/** Journal is durable before renames; seed write is the sole commit point; journal is removed last. */
class RestoreTransaction(
    private val location: WalletDataLocation,
    private val sync: (File) -> Unit = ::syncFileOrDirectory,
    private val rename: (File, File) -> Unit = ::renameFile,
    private val stopAutomation: ((File) -> Unit)? = null,
) {
    enum class Phase { installing, committing }
    @Serializable data class Journal(val version: Int = 1, val id: String, val phase: Phase,
        val hadPreviousData: Boolean, val seedSalt: String, val seedTag: String)
    enum class Recovery { nothingPending, rolledBack, completed, waitingForSecret }
    enum class Reason { alreadyPending, journalUnreadable, orphanedPreviousWallet, unexpectedDatadir }
    class Failure(val reason: Reason) : Exception(reason.name)
    val datadir = location.directory
    val directory = location.restoreDirectory
    val journalFile = File(directory, "journal.json")
    val previous = File(directory, "previous")
    val staged = File(directory, "staged")
    val discard = File(directory, "discard")
    val work = File(directory, "work")
    val isPending get() = journalFile.exists()

    fun prepare() {
        if (isPending) throw Failure(Reason.alreadyPending)
        if (previous.exists()) throw Failure(Reason.orphanedPreviousWallet)
        location.removeDiscardedDeletionData(sync)
        ensureWorkspace(); removeScratch(); check(work.mkdir())
    }
    fun begin(mnemonic: String) {
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val journal = Journal(id = UUID.randomUUID().toString(), phase = Phase.installing,
            hadPreviousData = datadir.exists(), seedSalt = Base64.encode(salt),
            seedTag = Base64.encode(tag(mnemonic, salt)))
        File(staged, markerName).writeText(journal.id)
        syncTree(staged); sync(directory); write(journal)
        // Each rename is durable before the next.
        if (journal.hadPreviousData) { rename(datadir, previous); syncBothSides() }
        rename(staged, datadir); syncBothSides()
    }
    fun markCommitting() = write(readJournal().copy(phase = Phase.committing))
    val settingsCompletion = File(directory, "settings-pending")
    val settingsPending get() = settingsCompletion.exists()
    fun completeSettings(settings: SettingsStorage) {
        if (!settingsPending || !settings.completeRestore()) return
        runCatching { check(settingsCompletion.delete()); sync(directory) }
    }
    fun finish() {
        settingsCompletion.writeBytes(byteArrayOf()); sync(settingsCompletion); sync(directory)
        if (stopAutomation != null) stopAutomation.invoke(datadir) else establishStoppedAutomation()
        val marker = File(datadir, markerName)
        if (marker.exists()) { remove(marker); sync(datadir) }
        remove(previous); removeScratch(); sync(directory); removeJournal()
    }
    fun undo() {
        val journal = readJournal()
        val restored = runCatching { File(datadir, markerName).readText() }.getOrNull() == journal.id
        val mustGo = !journal.hadPreviousData || previous.exists()
        if (mustGo && datadir.exists()) {
            if (!restored) throw Failure(Reason.unexpectedDatadir)
            remove(discard); rename(datadir, discard); syncBothSides()
        }
        if (journal.hadPreviousData && previous.exists()) { rename(previous, datadir); syncBothSides() }
        removeScratch(); sync(directory); removeJournal()
    }
    suspend fun recover(seed: SecretItem): Recovery {
        if (!isPending) { removeScratch(); return Recovery.nothingPending }
        val journal = readJournal()
        if (journal.phase == Phase.installing) { undo(); return Recovery.rolledBack }
        return when (val current = seed.read()) {
            is SecretResult.Unavailable -> Recovery.waitingForSecret
            is SecretResult.Found -> {
                val matches = MessageDigest.isEqual(tag(current.value, Base64.decode(journal.seedSalt)),
                    Base64.decode(journal.seedTag))
                if (matches) { finish(); Recovery.completed } else { undo(); Recovery.rolledBack }
            }
            SecretResult.NotFound -> { undo(); Recovery.rolledBack }
        }
    }
    fun removeScratch() { remove(staged); remove(discard); remove(work) }
    fun readJournal(): Journal = try {
        Json.decodeFromString<Journal>(journalFile.readText()).also {
            require(it.version == 1 && UUID.fromString(it.id).toString() == it.id)
            require(Base64.decode(it.seedSalt).size == 32 && Base64.decode(it.seedTag).size == 32)
        }
    } catch (_: Exception) { throw Failure(Reason.journalUnreadable) }
    private fun ensureWorkspace() {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create restore workspace" }
        sync(directory.parentFile!!)
    }
    private fun write(journal: Journal) {
        ensureWorkspace()
        writeDurably(Json.encodeToString(journal), journalFile)
    }
    private fun writeDurably(raw: String, file: File) {
        val temporary = File(file.parentFile, ".restore-${UUID.randomUUID()}")
        try {
            temporary.writeText(raw); sync(temporary)
            rename(temporary, file); sync(file.parentFile!!)
        } finally { temporary.delete() }
    }
    /** Only committed restore settlement may repair authority, always stopped, state before marker. */
    private fun establishStoppedAutomation() {
        val authority = com.noahwallet.recurring.RecurringAutomationAuthority(datadir)
        val instance = UUID.randomUUID().toString()
        val state = com.noahwallet.recurring.RecurringAutomationAuthority.State(instance = instance, generation = UUID.randomUUID().toString(), stopped = true)
        writeDurably(com.noahwallet.recurring.RecurringStore.json.encodeToString(state), authority.file)
        writeDurably(buildJsonObject { put("version", 1); put("instance", instance) }.toString(), authority.marker)
        com.noahwallet.recurring.RecurringAutomationAuthority.deleted(datadir)
    }
    private fun removeJournal() { remove(journalFile); sync(directory) }
    private fun syncBothSides() { sync(datadir.parentFile!!); sync(directory) }
    private fun syncTree(root: File) {
        if (root.isDirectory) (root.listFiles() ?: error("""Cannot read staged files""")).forEach(::syncTree)
        sync(root)
    }
    private fun remove(file: File) { check(!file.exists() || file.deleteRecursively()) { "Cannot remove restore workspace files" } }
    companion object {
        const val markerName = ".noah-restore-id"
        fun tag(mnemonic: String, salt: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(salt, "HmacSHA256")); doFinal(mnemonic.toByteArray(Charsets.UTF_8))
        }
        internal fun renameFile(from: File, to: File) { Os.rename(from.path, to.path) }
        internal fun syncFileOrDirectory(file: File) {
            if (file.isDirectory) {
                val descriptor = Os.open(file.path, OsConstants.O_RDONLY, 0)
                try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
            } else FileOutputStream(file, true).use { it.fd.sync() }
        }
    }
}
