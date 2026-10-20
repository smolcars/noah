package com.noahwallet.recurring

import com.noahwallet.send.SendIdentity
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import com.noahwallet.localization.L10n
import com.noahwallet.services.storage.LegacyLocale
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Fixtures retain the pre-reason-code native encoding and its original digest. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class RecurringReasonUpgradeTest {
    private class World : AutoCloseable {
        val directory = Files.createTempDirectory("review10-reason-upgrade-").toFile()
        val file = File(directory, "journal.json.policies")
        val binding = RecurringStore.Binding(SendIdentity("wallet", "regtest"), UUID.randomUUID().toString())
        val policy = RecurringPayment("rent", "Rent", "recipient", "ark", 1000,
            interval = RecurringInterval("day", 1), startAt = 1000, nativeRevision = UUID.randomUUID().toString(), createdAt = 0, updatedAt = 0)
        var raw: String? = null
        var settingsWrites = 0
        var ledgerWrites = 0
        var crashSettings = 0
        var crashLedger = 0

        private val json = RecurringStore.json
        fun oldRow(row: RecurringPayment) = JsonObject(json.encodeToJsonElement(row).jsonObject - "issue")
        private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        fun install(before: RecurringPayment, paid: RecurringPayment? = null) {
            raw = buildJsonObject {
                put("version", 0); putJsonObject("state") {
                    put("nativeWalletBinding", json.encodeToJsonElement(binding)); put("serverSyncPending", true)
                    putJsonObject("schedules") { put(before.id, oldRow(before)) }
                }
            }.toString()
            val target = paid ?: before
            val entry = buildJsonObject {
                put("binding", json.encodeToJsonElement(binding)); put("scheduleID", target.id); put("generation", 1)
                put("revision", target.nativeRevision); put("status", target.status); put("digest", hash(oldRow(target).toString()))
                if (paid != null) {
                    put("settlementKey", UUID.randomUUID().toString()); put("pending", oldRow(paid))
                    put("predecessor", hash(oldRow(before).toString()))
                }
            }
            File("${file.path}.initialized").writeText(buildJsonObject { put("version", 1); put("binding", json.encodeToJsonElement(binding)) }.toString())
            file.writeText(buildJsonObject {
                put("version", 1); put("binding", json.encodeToJsonElement(binding)); put("generation", 1)
                putJsonObject("entries") { put(hash(json.encodeToString(binding)) + ":" + target.id, entry) }
            }.toString())
        }
        fun store() = RecurringStore({ raw }, {
            raw = it; settingsWrites++
            check(settingsWrites != crashSettings) { "Process death after settings commit" }
        }).also { it.protectPolicies(file, sync = {
            ledgerWrites++
            check(ledgerWrites != crashLedger) { "Process death after ledger commit" }
        }, syncFile = {}) { binding } }
        override fun close() { directory.deleteRecursively() }
    }
    @Test fun oldHealthyNativePolicyMigratesOnlyWithMatchingAuthority() = World().use { w ->
        w.install(w.policy)
        val loaded = w.store().load().schedules.getValue(w.policy.id)
        assertEquals("active", loaded.status)
        assertFalse(loaded.requiresPolicyApproval)
    }
    @Test fun oldPendingPaidSettlementCanRecoverAndThenBeCancelled() = World().use { w ->
        val plan = w.policy.plan(1000)
        val inFlight = w.policy.copy(inFlight = RecurringPayment.InFlight(plan.occurrenceIndex!!, 1000))
        val paid = inFlight.success(plan, 1000, java.util.TimeZone.getTimeZone("UTC"))
        w.install(inFlight, paid)
        val restarted = w.store()
        assertEquals(1, restarted.load().schedules.getValue(w.policy.id).occurrencesPaid)
        restarted.remove(w.policy.id)
        assertTrue(restarted.load().schedules.isEmpty())
    }
    @Test fun processDeathAtEveryReplayAndFormatCommitIsIdempotent() {
        // Original settings, original finish, migration intent, migrated settings, migration finish.
        for ((settings, ledger) in listOf(1 to 0, 0 to 1, 0 to 2, 2 to 0, 0 to 3)) World().use { w ->
            val retryable = w.policy.copy(lastError = "No payment was submitted.", retryNotBefore = 901_000)
            val plan = retryable.plan(1000, ignoreBackoff = true)
            val inFlight = retryable.copy(inFlight = RecurringPayment.InFlight(plan.occurrenceIndex!!, 1000))
            val paid = inFlight.success(plan, 1000, java.util.TimeZone.getTimeZone("UTC"))
            w.install(inFlight, paid)
            val oldSettings = w.raw
            w.crashSettings = settings; w.crashLedger = ledger
            assertTrue("$settings/$ledger", runCatching { w.store().load() }.isFailure)
            w.crashSettings = 0; w.crashLedger = 0
            repeat(3) {
                val loaded = w.store().load().schedules.getValue(paid.id)
                assertEquals(1, loaded.occurrencesPaid)
                assertEquals(1, loaded.runs.count { it.status == "success" })
                assertNull(loaded.inFlight)
                assertFalse(loaded.requiresPolicyApproval)
            }
            val complete = w.raw
            w.raw = oldSettings // A completed record may never repair a later settings rollback.
            val stopped = w.store().load().schedules.getValue(paid.id)
            assertTrue(stopped.requiresPolicyApproval)
            assertEquals(0, stopped.occurrencesPaid)
            w.raw = complete
            w.store().remove(paid.id)
            assertTrue(w.store().load().schedules.isEmpty())
        }
    }
    @Test fun predecessorMismatchAndUnknownDigestVersionFailClosed() {
        for (damage in listOf("predecessor", "pending", "version", "wallet")) World().use { w ->
            val plan = w.policy.plan(1000)
            val inFlight = w.policy.copy(inFlight = RecurringPayment.InFlight(plan.occurrenceIndex!!, 1000))
            w.install(inFlight, inFlight.success(plan, 1000, java.util.TimeZone.getTimeZone("UTC")))
            val root = RecurringStore.json.parseToJsonElement(w.file.readText()).jsonObject
            val entries = root.getValue("entries").jsonObject
            val entry = entries.values.single().jsonObject
            val changed = when (damage) {
                "predecessor" -> entry + ("predecessor" to JsonPrimitive("0".repeat(64)))
                "pending" -> entry + ("pending" to JsonObject(entry.getValue("pending").jsonObject + ("amountSat" to JsonPrimitive(999))))
                "wallet" -> entry + ("binding" to RecurringStore.json.encodeToJsonElement(w.binding.copy(instance = UUID.randomUUID().toString())))
                else -> entry + ("digestVersion" to JsonPrimitive(99))
            }
            w.file.writeText(JsonObject(root + ("entries" to JsonObject(mapOf(entries.keys.single() to JsonObject(changed))))).toString())
            val original = w.raw
            assertTrue(damage, runCatching { w.store().load() }.isFailure)
            assertEquals(original, w.raw)
        }
    }
    @Test fun everyReasonRendersInEveryLanguage() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("gt_store", 0)
        val old = preferences.getString(LegacyLocale.key, null)
        try {
            for (locale in listOf("en", "es", "ja", "ko", "sv")) {
                preferences.edit().putString(LegacyLocale.key, locale).commit()
                for (code in RecurringIssue.Code.values()) {
                    val args = if (code in listOf(RecurringIssue.Code.quoteExceedsCap, RecurringIssue.Code.scheduleExceedsCap)) listOf("500", "200") else emptyList()
                    val issue = RecurringIssue(code, args)
                    val rendered = issue.message(context)
                    assertTrue("$locale $code", issue.valid && rendered.isNotEmpty())
                    assertFalse("$locale $code: $rendered", rendered.contains('{') || rendered.contains("%1$") || rendered.contains("%2$"))
                }
            }
        } finally { preferences.edit().putString(LegacyLocale.key, old).commit() }
    }
}
