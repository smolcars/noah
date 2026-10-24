package com.noahwallet.ui

import com.noahwallet.ui.settings.*
import com.noahwallet.services.storage.SettingsPreferences
import com.noahwallet.api.generated.models.DeviceInfo
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SettingsFeaturesTest {
    @Test fun successfulManualBackupSurvivesFailedListRefresh() = kotlinx.coroutines.runBlocking {
        var uploaded = false
        assertFalse(backUpAndRefresh(backup = { uploaded = true }, refresh = { error("list unavailable") }))
        assertTrue(uploaded)
        var listed = false
        assertTrue(runCatching { backUpAndRefresh(backup = { error("upload failed") }, refresh = { listed = true }) }.isFailure)
        assertFalse(listed)
        assertTrue(backUpAndRefresh(backup = {}, refresh = {}))
    }

    @Test fun aListThatFailsAfterABackupSaysSoInsteadOfLoading() = kotlinx.coroutines.runBlocking {
        val backups = BackupList()
        // Back up now's refresh: the list read fails on its own, and that failure is what the page shows.
        backUpAndRefresh(backup = {}, refresh = { backups.load { error("list unavailable") } })
        assertTrue(backups.failed)
        assertNull("never listed: \"Couldn't list backups.\", not \"Loading…\"", backups.items)
        backups.load { emptyList() }
        assertFalse(backups.failed)
        assertEquals(emptyList<Any>(), backups.items)
        // A later failure keeps what was listed and says the refresh failed.
        backups.load { error("list unavailable") }
        assertTrue(backups.failed)
        assertEquals(emptyList<Any>(), backups.items)
    }

    @Test fun launchBackgroundAndStaleAuthentication() {
        val policy = BiometricLockPolicy(true)
        assertTrue(policy.locked)
        policy.authenticated(policy.generation); assertFalse(policy.locked)
        val old = policy.generation
        policy.background(); policy.authenticated(old); assertTrue(policy.locked)
        policy.authenticated(policy.generation); assertFalse(policy.locked)
        policy.setEnabled(false); policy.background(); assertFalse(policy.locked)
        policy.setEnabled(true); assertFalse(policy.locked)
        policy.background(); assertTrue(policy.locked)
    }
    @Test fun credentialHopKeepsAuthenticationButRealBackgroundRejectsIt() {
        val policy = BiometricLockPolicy(true)
        val started = policy.generation
        policy.credentialStarted(); policy.stopped()
        assertEquals(started, policy.generation)
        policy.credentialFinished(); policy.authenticated(started)
        assertFalse(policy.locked)
        policy.stopped(); assertTrue(policy.locked)
        val request = policy.generation
        policy.credentialStarted(); policy.stopped()
        policy.background(); policy.credentialFinished(); policy.authenticated(request)
        assertTrue(policy.locked); assertFalse(policy.confirmingCredential)
        policy.authenticated(policy.generation); assertFalse(policy.locked)
    }

    @Test fun unlockedSettingsHandoffHidesWalletAndUnexpectedResumeRejectsLateSuccess() {
        val policy = BiometricLockPolicy(true)
        policy.authenticated(policy.generation)
        val request = policy.generation
        policy.credentialStarted(); policy.stopped()
        assertTrue(policy.locked)
        assertEquals(request, policy.generation)
        policy.resumed()
        policy.credentialFinished(); policy.authenticated(request)
        assertTrue(policy.locked)
        assertNotEquals(request, policy.generation)
    }

    @Test fun validSettingsCredentialResultBeforeResumeUnlocksSameGeneration() {
        val policy = BiometricLockPolicy(true)
        policy.authenticated(policy.generation)
        val request = policy.generation
        policy.credentialStarted(); policy.stopped()
        assertTrue(policy.locked)
        policy.credentialFinished(); policy.resumed(); policy.authenticated(request)
        assertEquals(request, policy.generation)
        assertFalse(policy.locked)
    }

    @Test fun screenshotBoundedAndMimeChecked() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10).copyOf(FeedbackDraft.maximumScreenshotBytes)
        assertEquals("image/png", FeedbackDraft.imageType(png))
        assertEquals(png.size, FeedbackDraft.readScreenshot(png.inputStream()).size)
        assertTrue(runCatching { FeedbackDraft.readScreenshot(png.copyOf(png.size + 1).inputStream()) }.isFailure)
        assertTrue(runCatching { FeedbackDraft.imageType("not image".toByteArray()) }.isFailure)
        assertEquals("image/jpeg", FeedbackDraft.imageType(byteArrayOf(-1, -40, -1)))
    }
    @Test fun feedbackLimitsAreUtf8BytesAndBlankEmailIsNull() {
        val draft = FeedbackDraft(" Alice ", " ", "é".repeat(75), " message ")
        val payload = draft.payload(DeviceInfo(appVersion = "1", osName = "Android")) { "unused" }
        assertEquals("Alice", payload.name); assertEquals("message", payload.body); assertNull(payload.email)
        assertEquals("Android", payload.deviceInfo!!.osName)
        draft.subject += "a"; assertTrue(runCatching { draft.payload(DeviceInfo()) { "" } }.isFailure)
        draft.subject = "ok"; draft.body = "x".repeat(60_001)
        assertTrue(runCatching { draft.payload(DeviceInfo()) { "" } }.isFailure)
    }
    @Test fun preferencesRetainIdentityAndUnknownFields() {
        val original = """{"state":{"displayName":"Alice","preferredCurrency":"EUR","bitcoinAmountUnit":"sats","future":true},"version":0}"""
        val result = SettingsPreferences.update(original, mapOf("preferredCurrency" to JsonPrimitive("JPY")))
        val state = SettingsPreferences.state(result)
        assertEquals("Alice", state["displayName"]!!.jsonPrimitive.content)
        assertEquals("sats", state["bitcoinAmountUnit"]!!.jsonPrimitive.content)
        assertEquals("JPY", state["preferredCurrency"]!!.jsonPrimitive.content)
        assertTrue(state["future"]!!.jsonPrimitive.boolean)
    }
    @Test fun oldAttemptedFlagAllowsCurrentDepositOnceAndToggleCannotReplayIt() {
        var settings = """{"state":{"isAutoBoardingEnabled":true,"hasAttemptedAutoBoarding":true},"version":1}"""
        var fundingDisk: String? = null
        fun ledger() = com.noahwallet.onchain.AutoBoardFunding({ fundingDisk }, { fundingDisk = it })
        val funding = com.noahwallet.onchain.AutoBoardFunding.Snapshot("wallet", setOf("current:0"))
        assertTrue(SettingsPreferences.transaction(settings)["isAutoBoardingEnabled"]!!.jsonPrimitive.boolean)
        assertTrue(ledger().hasUnhandled(funding)); assertTrue(ledger().markHandled(funding))
        settings = SettingsPreferences.autoBoard(SettingsPreferences.autoBoard(settings, false), true)
        assertTrue(SettingsPreferences.transaction(settings)["isAutoBoardingEnabled"]!!.jsonPrimitive.boolean)
        assertFalse(ledger().hasUnhandled(funding))
    }
    @Test fun autoBoardMigratesVersionZeroAndResetsAttemptOnlyWhenEnabled() {
        val old = """{"state":{"isAutoBoardingEnabled":false,"hasAttemptedAutoBoarding":true},"version":0}"""
        assertFalse(SettingsPreferences.state(SettingsPreferences.autoBoard(old, false))["hasAttemptedAutoBoarding"]!!.jsonPrimitive.boolean)
        val current = old.replace("\"version\":0", "\"version\":1")
        assertTrue(SettingsPreferences.state(SettingsPreferences.autoBoard(current, false))["hasAttemptedAutoBoarding"]!!.jsonPrimitive.boolean)
        assertFalse(SettingsPreferences.state(SettingsPreferences.autoBoard(current, true))["hasAttemptedAutoBoarding"]!!.jsonPrimitive.boolean)
    }
}
