package com.noahwallet.ui.settings

import androidx.navigation3.runtime.NavKey
import com.noahwallet.onchain.VtxoModel
import com.noahwallet.recurring.RecurringInterval
import com.noahwallet.recurring.RecurringPayment
import com.noahwallet.ui.Destination
import com.noahwallet.ui.QRDestination
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** docs/native-rewrite/design/settings.md, Testing: the rules behind Settings' rows, gates and deep links. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsRulesTest {
    private fun vtxo(id: String, expiry: Long = 1_000, state: String = "Spendable", expiring: Boolean = false) =
        VtxoModel(id, 1_000, expiry, state, "round", 0, 0, true, "", 144, "anchor:0", "point:0", expiring, 100)

    @Test fun fiveQuickTapsUnlockDebugAndSlowTapsStartOver() {
        val taps = SettingsRules.DebugTaps()
        assertEquals(listOf(4, 3, 2, 1), (0 until 4).map { taps.tap(it * 1_000L) })
        assertEquals(0, taps.tap(4_000))
        // Unlocking starts over, and a gap of two seconds or more resets the count.
        assertEquals(4, taps.tap(5_000))
        assertEquals(3, taps.tap(6_500))
        assertEquals(4, taps.tap(8_600))
    }

    @Test fun backupRowPrefersFailureAndRunningOverTheSwitch() {
        assertEquals(SettingsRules.Backup.failed, SettingsRules.backup(true, "failed"))
        assertEquals(SettingsRules.Backup.failed, SettingsRules.backup(false, "failed"))
        assertEquals(SettingsRules.Backup.running, SettingsRules.backup(false, "in_progress"))
        assertEquals(SettingsRules.Backup.on, SettingsRules.backup(true, "success"))
        assertEquals(SettingsRules.Backup.off, SettingsRules.backup(false, "idle"))
    }

    @Test fun vtxoRowSaysRenewOnlyWhileOneIsDue() {
        assertEquals(SettingsRules.Vtxos.Unknown, SettingsRules.vtxos(null))
        assertEquals(SettingsRules.Vtxos.Count(2), SettingsRules.vtxos(listOf(vtxo("a"), vtxo("b"))))
        assertEquals(SettingsRules.Vtxos.Renew, SettingsRules.vtxos(listOf(vtxo("a"), vtxo("b", expiring = true))))
        // Locked or expired VTXOs can't be renewed, so they don't ask.
        assertEquals(SettingsRules.Vtxos.Count(2), SettingsRules.vtxos(listOf(vtxo("a", state = "Locked", expiring = true), vtxo("b", expiry = 90, expiring = true))))
    }

    @Test fun recurringRowCountsApprovalsAndActivePayments() {
        fun schedule(status: String) = RecurringPayment(status, status, "a@b.c", "lnurl", 1, interval = RecurringInterval("month", 1),
            startAt = 0, createdAt = 0, updatedAt = 0, status = status)
        assertEquals(SettingsRules.Recurring(1, 2), SettingsRules.recurring(listOf(schedule("active"), schedule("active"), schedule("needs_approval"), schedule("paused"))))
    }

    @Test fun batteryRowShowsAndroidsOptimisation() {
        assertEquals(SettingsRules.Battery.optimized, SettingsRules.battery(optimizationEnabled = true))
        assertEquals(SettingsRules.Battery.unrestricted, SettingsRules.battery(optimizationEnabled = false))
    }

    @Test fun deleteChecklistAndTypedDelete() {
        val checklist = SettingsRules.deleteChecklist(152_742uL, phraseSaved = false, lastBackupAt = 1_700_000_000_000.0, backupStatus = "failed")
        assertEquals(SettingsRules.DeleteChecklist(152_742, false, 1_700_000_000_000, true), checklist)
        assertEquals(SettingsRules.DeleteChecklist(null, true, null, false), SettingsRules.deleteChecklist(null, true, null, "success"))
        assertTrue(SettingsRules.deleteConfirmed("delete"))
        assertTrue(SettingsRules.deleteConfirmed("  DELETE \n"))
        assertFalse(SettingsRules.deleteConfirmed("delet"))
        assertFalse(SettingsRules.deleteConfirmed("delete wallet"))
    }

    @Test fun everyDeepLinkLandsOnItsPageWithSettingsBeneath() {
        val settings = SettingsDestination.Settings
        val links: Map<String, Pair<NavKey, List<NavKey>>> = mapOf(
            "Home's Renew" to (SettingsDestination.Vtxos to listOf(settings, SettingsDestination.Vtxos)),
            "Home's backup nudge" to (SettingsDestination.Seed to listOf(settings, SettingsDestination.Seed)),
            "Home's suspended state" to (SettingsDestination.Advanced to listOf(settings, SettingsDestination.Advanced)),
            "Home's and Receive's profile" to (SettingsDestination.Profile to listOf(settings, SettingsDestination.Profile)),
            "Activity's VTXO" to (VtxoPage("v1") to listOf(settings, SettingsDestination.Vtxos, VtxoPage("v1"))),
            "Activity's schedule" to (SchedulePage("s1") to listOf(settings, SettingsDestination.Recurring, SchedulePage("s1"))),
            "Activity's exit" to (SettingsDestination.Exits to listOf(settings, SettingsDestination.Exits)),
            "a recurring notification" to (SettingsDestination.Recurring to listOf(settings, SettingsDestination.Recurring)),
            "Home's gear" to (settings to listOf(settings)),
        )
        for ((name, link) in links) {
            // From anywhere: My code open over Home, or a stale Settings stack.
            val home = mutableListOf<NavKey>(Destination.Home, QRDestination(false))
            val stack = mutableListOf<NavKey>(settings, SettingsDestination.Logs)
            SettingsRoutes.open(home, stack, link.first)
            assertEquals(name, listOf(Destination.Home, settings), home)
            assertEquals(name, link.second, stack)
        }
        assertEquals(listOf(settings, SettingsDestination.Profile, SettingsDestination.Lightning), SettingsRoutes.chain(SettingsDestination.Lightning))
        assertEquals(listOf(settings, SettingsDestination.Notifications, SettingsDestination.UnifiedPush), SettingsRoutes.chain(SettingsDestination.UnifiedPush))
        assertEquals(listOf(settings, SettingsDestination.Advanced, SettingsDestination.Debug), SettingsRoutes.chain(SettingsDestination.Debug))
        assertEquals(listOf(settings, SettingsDestination.Recurring, EditorPage(null)), SettingsRoutes.chain(EditorPage(null)))
        // The emergency exit's pages sit above its warning and fill a wide window; nothing else does.
        for (page in listOf(ExitChoosePage, ExitStartPage(listOf("v1")), ExitPage("v1"), ExitClaimPage, ExitsPage)) {
            assertEquals(listOf(settings, SettingsDestination.Exits, page), SettingsRoutes.chain(page))
            assertTrue(SettingsRoutes.exit(page))
        }
        assertTrue(SettingsRoutes.exit(SettingsDestination.Exits))
        assertFalse(SettingsRoutes.exit(SettingsDestination.Vtxos)); assertFalse(SettingsRoutes.exit(null))
    }

    @Test fun navigationIsHeldWhileAnySaveThatNavigatesRuns() {
        val hold = NavigationHold()
        assertFalse(hold.held)
        hold.set(true); hold.set(true); hold.set(false)
        assertTrue("one save still running", hold.held)
        hold.set(false); hold.set(false)
        assertFalse("never below none", hold.held)
        hold.set(true)
        assertTrue(hold.held)
    }

    @Test fun aFinishingSaveLeavesOnlyItsOwnPage() {
        assertTrue(SettingsRoutes.top(SettingsRoutes.chain(SettingsDestination.Lightning), SettingsDestination.Lightning))
        // A recurring notification replaced Settings' stack while the save ran: its completion leaves that page alone.
        assertFalse(SettingsRoutes.top(SettingsRoutes.chain(SettingsDestination.Recurring), SettingsDestination.Lightning))
        assertFalse(SettingsRoutes.top(SettingsRoutes.chain(SettingsDestination.Email), SettingsDestination.Profile))
    }

    @Test fun phraseCopyIsMarkedSensitiveAndClearsAfterTwoMinutes() {
        val clip = PhraseClip.clip("noah-recovery-test", "sample phrase only")
        assertTrue(clip.description.extras.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE))
        assertEquals("noah-recovery-test", clip.description.label)
        assertEquals(120_000L, PhraseClip.clearAfterMillis)
        val clipboard = org.robolectric.RuntimeEnvironment.getApplication().getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(clip)
        assertTrue(PhraseClip.ours(clipboard, "noah-recovery-test"))
        assertFalse(PhraseClip.ours(clipboard, "noah-recovery-other"))
    }

    @Test fun theCopiedClipKeepsItsDeadlineOutsideThePage() {
        val phrase = RevealedPhrase()
        phrase.clipDeadline = 1_000 + PhraseClip.clearAfterMillis
        // Leaving Noah clears the words, not the clip's deadline; a page recreated later waits only what's left.
        phrase.clear()
        assertEquals(121_000L, phrase.clipDeadline)
        assertEquals(30_000L, PhraseClip.remaining(phrase.clipDeadline!!, now = 91_000))
        assertEquals(0L, PhraseClip.remaining(phrase.clipDeadline!!, now = 200_000))
    }

    @Test fun copyingAgainKeepsTheNewDeadline() {
        val phrase = RevealedPhrase()
        phrase.clipDeadline = 120_000
        // Copied again just before the first deadline: the first timer, resuming late, leaves the new clip alone.
        phrase.clipDeadline = 239_000
        assertFalse(phrase.expireClip(120_000))
        assertEquals(239_000L, phrase.clipDeadline)
        assertTrue(phrase.expireClip(239_000))
        assertNull(phrase.clipDeadline)
    }

    @Test fun revealedWordsLiveInMemoryAndClear() {
        val phrase = RevealedPhrase()
        assertTrue(phrase.words.isEmpty())
        phrase.show(listOf("sample", "phrase"), generation = 3)
        assertEquals(3, phrase.generation)
        phrase.clear()
        assertTrue(phrase.words.isEmpty())
        assertEquals(-1, phrase.generation)
    }
}
