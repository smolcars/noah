package com.noahwallet.send

import com.noahwallet.services.storage.syncDirectory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@Serializable data class SendOverrideAudit(
    val overriddenAt: Long = System.currentTimeMillis(), val txid: String? = null,
    val walletIdentity: SendIdentity? = null, val ownershipUnverified: Boolean = false,
    val candidates: List<UInt> = emptyList(), val possibleTxids: List<String> = emptyList(),
    val originalEvidence: SendAttempt? = null, val title: String = titleText,
    val warning: String = warningText, val confirmation: String = confirmationText,
    val status: String, val statusMessage: String? = null) {
    companion object {
        const val titleText = "You could pay twice"
        const val confirmationText = "Start a new payment anyway"
        const val warningText = "Forgetting this attempt does not cancel Bark’s stored transaction or its future broadcast retries. This payment may still complete. Check your full wallet history and confirm with the recipient first. Sending again may spend twice and cannot undo the first payment."
    }
}

/** Wallet-private evidence survives active journal removal; never discard unreadable audit bytes. */
class SendOverrideAuditStore(private val file: File, private val sync: (File) -> Unit = ::syncDirectory) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Synchronized fun append(audit: SendOverrideAudit) {
        val previous = if (file.exists()) {
            try {
                require(file.length() <= 1_048_576)
                json.decodeFromString<List<SendOverrideAudit>>(file.readText())
            } catch (_: Exception) { quarantineSendEvidence(file, sync); emptyList() }
        } else emptyList()
        val temporary = File(file.parentFile, ".send-overrides-${UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(json.encodeToString((previous + audit).takeLast(100)).toByteArray())
                stream.fd.sync()
            }
            check(temporary.renameTo(file)) { """Unable to save override audit""" }
            sync(file.parentFile!!)
        } finally { temporary.delete() }
    }
}

internal fun quarantineSendEvidence(file: File, sync: (File) -> Unit): File {
    val quarantine = File(file.parentFile, "${file.name}.unreadable-${UUID.randomUUID()}")
    check(file.renameTo(quarantine)) { """Unable to preserve unreadable Send evidence""" }
    sync(file.parentFile!!)
    return quarantine
}
