package com.noahwallet.ui

import android.app.Application
import androidx.activity.BackEventCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.services.storage.PersistedWalletState
import com.noahwallet.ui.settings.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Settings on a device (docs/native-rewrite/design/settings.md, Testing): Back and predictive back on every page, the
 * phrase gate and its clearing, the suspend and delete confirmations, the debug switch, Home's hide flag, and the
 * book and tabletop postures with pose changes. Synthetic folds make the postures independent of the device. Nothing
 * here confirms a destructive action, and no screenshot is taken.
 */
class SettingsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private lateinit var model: AppModel
    private var fold by mutableStateOf<WindowRegion?>(null)
    private lateinit var stack: NavBackStack<NavKey>
    private var closed = 0
    private var hosts by mutableIntStateOf(0)
    /** A narrow window (compact Settings) on a wide device; null fills the window. */
    private var width by mutableStateOf<androidx.compose.ui.unit.Dp?>(null)
    /** The host's hold on navigation, which a save that navigates sets (SettingsNav.save). */
    private val hold = NavigationHold()

    @Before fun model() {
        compose.runOnUiThread { model = AppModel(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application) }
    }

    /** Settings over [chain]'s stack, as Home's stack shows it, inside today's app theme. */
    private fun host(page: NavKey, sheet: String? = null, phrase: List<String>? = null) {
        val generation = ++hosts
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                // A fresh stack per call (a recreation restores the same call's state).
                key(generation) { NoahTheme {
                    CompositionLocalProvider(LocalFoldingFeatures provides listOfNotNull(fold)) {
                        stack = rememberNavBackStack(*SettingsRoutes.chain(page).toTypedArray())
                        // Home's own Back under Settings: it leaves Settings once no page can pop.
                        androidx.activity.compose.BackHandler { closed++ }
                        Box(width?.let { Modifier.fillMaxHeight().width(it) } ?: Modifier.fillMaxSize()) { SettingsHost(model, stack, close = { closed++ }, myCode = {}, preview = SettingsPreview(sheet, phrase), hold = hold) }
                    }
                } }
            }
        }
        compose.waitForIdle()
    }

    private fun back(predictive: Boolean) = compose.activityRule.scenario.onActivity { activity ->
        val dispatcher = activity.onBackPressedDispatcher
        if (predictive) {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 800f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(200f, 800f, .5f, BackEventCompat.EDGE_LEFT))
        }
        dispatcher.onBackPressed()
    }

    @Test fun backAndPredictiveBackReturnToTheParentOnEveryPage() {
        val pages: List<NavKey> = SettingsDestination.entries.filter { it != SettingsDestination.Settings && it != SettingsDestination.Debug } +
            listOf(VtxoPage("missing"), EditorPage(null))
        for (predictive in listOf(false, true)) for (page in pages) {
            host(page)
            assertEquals("$page opens", SettingsRoutes.chain(page), stack.toList())
            // In a split a first-level page sits beside the list, so Back leaves Settings like the list's Back.
            val split = compose.onAllNodesWithTag("settings-list-pane").fetchSemanticsNodes().isNotEmpty()
            val leaves = split && SettingsRoutes.chain(page).size == 2
            val before = closed
            back(predictive)
            compose.waitForIdle()
            assertEquals("$page (predictive=$predictive)", SettingsRoutes.chain(page).let { if (leaves) it else it.dropLast(1) }, stack.toList())
            assertEquals("$page leaves Settings", if (leaves) before + 1 else before, closed)
        }
        // The list's Back leaves Settings for Home.
        host(SettingsDestination.Settings)
        val before = closed
        compose.onNodeWithTag("settings-back").performClick()
        compose.waitForIdle()
        assertEquals(before + 1, closed)
    }

    @Test fun phraseWordsAreAbsentUntilShownAndClearOnBackground() {
        host(SettingsDestination.Seed)
        compose.onNodeWithTag("recovery-show").assertExists()
        assertTrue(compose.onAllNodesWithTag("recovery-word-1", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        lateinit var revealed: RevealedPhrase
        compose.activityRule.scenario.onActivity { revealed = ViewModelProvider(it)[RevealedPhrase::class.java] }
        val sample = "sample phrase only never use this example test word copy nothing real".split(' ')
        // Stand-in for a successful authentication in this generation (no real phrase is read).
        compose.runOnUiThread { revealed.show(sample, model.walletLock.generation) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("recovery-word-12", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("recovery-copy").assertExists()
        // Leaving Noah (a new lock generation) clears them; Show asks again.
        compose.runOnUiThread { model.walletLock.background() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("recovery-word-1", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("recovery-show").assertExists()
        // So does the activity stopping for real.
        compose.runOnUiThread { revealed.show(sample, model.walletLock.generation) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("recovery-word-1", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        assertTrue(revealed.words.isEmpty())
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
    }

    @Test fun aCopiedPhraseStillClearsAtItsDeadlineAfterRecreation() {
        val sample = "sample phrase only never use this example test word copy nothing real".split(' ')
        host(SettingsDestination.Seed)
        lateinit var revealed: RevealedPhrase
        compose.activityRule.scenario.onActivity { revealed = ViewModelProvider(it)[RevealedPhrase::class.java]; revealed.show(sample, model.walletLock.generation) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("recovery-copy").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("recovery-copy").performClick()
        compose.waitForIdle()
        val clipboard = compose.activity.getSystemService(android.content.ClipboardManager::class.java)
        fun ours() = clipboard.primaryClipDescription?.label?.startsWith("noah-recovery-") == true
        val deadline = checkNotNull(revealed.clipDeadline)
        assertTrue(ours())
        compose.activityRule.scenario.recreate()
        hosts--; host(SettingsDestination.Seed)
        assertEquals("a rotation keeps the original deadline", deadline, revealed.clipDeadline)
        assertTrue(ours())
        // Bring the deadline close: the recreated page still clears the clip on it.
        compose.runOnUiThread { revealed.clipDeadline = android.os.SystemClock.elapsedRealtime() + 300 }
        compose.waitUntil(5_000) { !ours() }
        assertNull(revealed.clipDeadline)
    }

    @Test fun aRecurringAmountStaysAsTyped() {
        host(EditorPage(null))
        val field = compose.onNodeWithTag("recurring-amount-input")
        field.performTextInput("1.5")
        field.assert(hasText("1.5"))
        field.performTextReplacement("-1000")
        field.assert(hasText("-1000"))
    }

    @Test fun aMigratedPaymentLimitShowsAndEditsItsLongCount() {
        val now = System.currentTimeMillis()
        // Never saved: the editor only shows it.
        val migrated = com.noahwallet.recurring.RecurringPayment("migrated", "Rent", "a@b.c", "lnurl", 1_000,
            interval = com.noahwallet.recurring.RecurringInterval("month", 1), startAt = now + 86_400_000, maxOccurrences = 3_000_000_000,
            createdAt = now, updatedAt = now)
        val executor = com.noahwallet.recurring.RecurringRuntime.shared(compose.activity).executor
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { NoahTheme { com.noahwallet.ui.onboarding.OnboardingFrame(null) {
            androidx.compose.foundation.layout.Column {
                // The schedule page's Ends row.
                androidx.compose.material3.Text(com.noahwallet.localization.trPlural("After {n} payments", migrated.maxOccurrences!!), Modifier.testTag("ends"))
                com.noahwallet.recurring.RecurringPaymentEditorScreen(executor, migrated) {}
            }
        } } } }
        compose.onNodeWithTag("ends").assert(hasText("After 3,000,000,000 payments"))
        compose.onNode(hasText("After 3,000,000,000 payments") and !hasTestTag("ends")).assertExists()
        // The second stepper is the payment count: More adds one, with no 9,999 cap.
        val more = compose.onAllNodesWithContentDescription("More")
        assertEquals("the interval's stepper and the count's", 2, more.fetchSemanticsNodes().size)
        more[1].performScrollTo().performClick()
        compose.onNode(hasText("After 3,000,000,001 payments") and !hasTestTag("ends")).assertExists()
    }

    @Test fun navigationWaitsOnlyForASaveThatNavigates() {
        val page = SettingsRoutes.chain(SettingsDestination.Lightning)
        // Long work that doesn't navigate (Back up now): Back still leaves the page while it carries on.
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        host(SettingsDestination.Lightning)
        try {
            compose.runOnUiThread { model.action("Couldn't back up your wallet.") { gate.await() } }
            compose.waitUntil(5_000) { model.busy }
            back(false)
            compose.waitForIdle()
            assertEquals("Back during Back up now", page.dropLast(1), stack.toList())
        } finally { compose.runOnUiThread { gate.complete(Unit) } }
        compose.waitUntil(5_000) { !model.busy }

        // A save whose completion navigates (the Lightning address) holds navigation until it ends.
        host(SettingsDestination.Lightning)
        val before = closed
        try {
            compose.runOnUiThread { hold.set(true) }
            for (predictive in listOf(false, true)) {
                back(predictive); compose.waitForIdle()
                assertEquals("Back (predictive=$predictive)", page, stack.toList())
                compose.onNodeWithTag("lightning-npub-input").assertIsDisplayed()
            }
            compose.onNode(hasTestTag("settings-back") and hasAnyAncestor(hasTestTag("settings-page-pane"))).performClick()
            compose.waitForIdle()
            assertEquals("the bar's back", page, stack.toList())
            // In a split, picking another page from the list waits too.
            pose(book)
            compose.onNodeWithTag("settings-backup").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals("a list pick", page, stack.toList())
            pose(null)
            // On the list, Back would close Settings: that waits too.
            host(SettingsDestination.Settings)
            back(false)
            compose.waitForIdle()
            assertEquals("Settings stays open", before, closed)
        } finally { compose.runOnUiThread { hold.set(false) } }
        host(SettingsDestination.Lightning)
        back(false)
        compose.waitForIdle()
        assertEquals("once it ends, Back works", page.dropLast(1), stack.toList())
    }

    @Test fun suspendWalletAsksFirst() {
        assumeNotSuspended()
        host(SettingsDestination.Advanced)
        compose.onNodeWithTag("advanced-suspend").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("suspend-sheet").fetchSemanticsNodes().isNotEmpty() }
        assertFalse("the switch alone never suspends", model.isWalletSuspended)
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("suspend-sheet").fetchSemanticsNodes().isEmpty() }
        assertFalse(model.isWalletSuspended)
    }

    private fun assumeNotSuspended() = org.junit.Assume.assumeFalse(model.isWalletSuspended)

    @Test fun deleteNeedsTypedDeleteAndNeverRunsHere() {
        host(SettingsDestination.Settings, sheet = "delete")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("delete-wallet-sheet").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("delete-wallet-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("delete-wallet-input").performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("delete-wallet-input")).or(hasTestTag("delete-wallet-input"))).performTextInput("delet")
        compose.onNodeWithTag("delete-wallet-confirm").assertIsNotEnabled()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("delete-wallet-input")).or(hasTestTag("delete-wallet-input"))).performTextReplacement("  DELETE ")
        compose.onNodeWithTag("delete-wallet-confirm").assertIsEnabled()
        // Checklist: what you'd need to restore.
        val sheet = hasAnyAncestor(hasTestTag("delete-wallet-sheet"))
        for (row in listOf("In this wallet", "Recovery phrase", "Last backup")) compose.onNode(hasText(row) and sheet, useUnmergedTree = true).assertExists()
    }

    @Test fun aDeletionInProgressStaysLockedAcrossRecreation() {
        // The model says a deletion is running (nothing is deleted here); the sheet locks from that alone.
        val sheet = hasAnyAncestor(hasTestTag("delete-wallet-sheet"))
        fun assertLocked() {
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("delete-wallet-sheet").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("Deleting") and sheet).assertExists()
            compose.onNode(hasText("Cancel") and sheet).assertIsNotEnabled()
        }
        try {
            compose.runOnUiThread { model.deletingWallet = true }
            host(SettingsDestination.Settings, sheet = "delete")
            assertLocked()
            compose.activityRule.scenario.recreate()
            hosts--; host(SettingsDestination.Settings, sheet = "delete")
            assertLocked()
            // How it ended still reaches the recreated sheet: a failure unlocks it and says why.
            compose.runOnUiThread { model.deletingWallet = false; model.deleteWalletError = "Couldn't delete wallet." }
            compose.onNode(hasText("Couldn't delete wallet.") and sheet).assertExists()
            compose.onNode(hasText("Cancel") and sheet).assertIsEnabled()
        } finally { compose.runOnUiThread { model.deletingWallet = false; model.deleteWalletError = null } }
    }

    @Test fun debugModeTurnsOffAndFiveTapsTurnItOn() {
        val initial = model.settings.persistedState<PersistedWalletState>(PersistedWalletState.key) ?: PersistedWalletState()
        fun debug(on: Boolean) = model.settings.setPersistedState((model.settings.persistedState<PersistedWalletState>(PersistedWalletState.key)
            ?: PersistedWalletState()).copy(isDebugModeEnabled = on), PersistedWalletState.key, 0)
        try {
            debug(true)
            host(SettingsDestination.Advanced)
            compose.onNodeWithTag("advanced-debug").assertExists()
            compose.onNodeWithTag("advanced-debug-mode").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("advanced-debug").fetchSemanticsNodes().isEmpty() }
            assertNotEquals(true, model.settings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isDebugModeEnabled)
            host(SettingsDestination.Settings)
            compose.onNodeWithTag("settings-version").performScrollTo()
            repeat(5) { compose.onNodeWithTag("settings-version").performClick() }
            compose.waitForIdle()
            assertEquals(true, model.settings.persistedState<PersistedWalletState>(PersistedWalletState.key)?.isDebugModeEnabled)
        } finally { debug(initial.isDebugModeEnabled == true) }
    }

    @Test fun hideAmountsIsHomesHideFlag() {
        val before = model.hidden
        fun stored() = Json.parseToJsonElement(model.settings.string("privacy-storage")!!).jsonObject["state"]!!.jsonObject["isHomeBalanceHidden"]!!.jsonPrimitive.boolean
        try {
            host(SettingsDestination.Settings)
            compose.onNodeWithTag("settings-hide-amounts").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(!before, model.hidden)
            assertEquals(model.hidden, stored())
        } finally { compose.runOnUiThread { if (model.hidden != before) model.toggleBalance() } }
    }

    private fun nodes(): List<SemanticsNode> {
        val all = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) { all += node; node.children.forEach(::walk) }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return all.filter { it.config.getOrNull(SemanticsProperties.Text) != null || it.config.getOrNull(SemanticsActions.OnClick) != null }
    }
    private fun assertClear(label: String, region: WindowRegion) {
        val vertical = region.height > region.width
        for (node in nodes()) {
            val bounds = node.boundsInWindow
            if (bounds.width <= 0f || bounds.height <= 0f) continue
            val what = "$label: ${node.config.getOrNull(SemanticsProperties.Text)?.joinToString() ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()} $bounds $region"
            if (vertical) assertFalse("crosses the vertical fold: $what", bounds.left < region.left && bounds.right > region.right)
            else assertFalse("crosses the horizontal fold: $what", bounds.top < region.top && bounds.bottom > region.bottom)
        }
    }
    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
    private fun pose(hinge: ((Int, Int) -> WindowRegion)?) {
        val view = compose.activity.window.decorView
        compose.runOnUiThread { fold = hinge?.invoke(view.width, view.height) }
        compose.waitForIdle()
    }
    private val book = { width: Int, height: Int -> WindowRegion(width / 2 - 12, 0, width / 2 + 12, height) }
    private val tabletop = { width: Int, height: Int -> WindowRegion(0, height / 2 - 12, width, height / 2 + 12) }

    @Test fun bookAndTabletopSplitAtTheFoldAndKeepThePageAcrossPoses() {
        host(SettingsDestination.Lightning)
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("lightning-npub-input")).or(hasTestTag("lightning-npub-input")))
            .performTextReplacement("npub1posechange")
        pose(book)
        val region = fold!!
        assertClear("book", region)
        assertTrue("list leads", bounds("settings-list-pane").right <= region.left)
        assertTrue("page trails", bounds("settings-page-pane").left >= region.right)
        compose.onNodeWithText("npub1posechange").assertExists()
        assertEquals(SettingsDestination.Lightning, stack.last())
        pose(tabletop)
        val flat = fold!!
        assertClear("tabletop", flat)
        assertTrue("list above", bounds("settings-list-pane").bottom <= flat.top)
        assertTrue("page below", bounds("settings-page-pane").top >= flat.bottom)
        compose.onNodeWithText("npub1posechange").assertExists()
        pose(null)
        compose.onNodeWithText("npub1posechange").assertExists()
        assertEquals(SettingsRoutes.chain(SettingsDestination.Lightning), stack.toList())
    }

    @Test fun livePoseChangesKeepOneListWithItsSheet() {
        // The compact list on top, with its delete sheet open (nothing is deleted).
        width = 400.dp
        host(SettingsDestination.Settings)
        compose.onNodeWithTag("settings-delete-wallet").performScrollTo().performClick()
        fun sheetOpen() = compose.waitUntil(5_000) { compose.onAllNodesWithTag("delete-wallet-sheet").fetchSemanticsNodes().isNotEmpty() }
        sheetOpen()
        // Live posture changes, without recreation: the list moves between the first page and the leading pane.
        pose(tabletop)
        compose.onNodeWithTag("settings-list-pane").assertExists()
        sheetOpen()
        compose.runOnUiThread { width = null }
        pose(book)
        compose.onNodeWithTag("settings-list-pane").assertExists()
        sheetOpen()
        compose.runOnUiThread { width = 400.dp }
        pose(null)
        compose.onNodeWithTag("settings-list-pane").assertDoesNotExist()
        sheetOpen()
        assertEquals(listOf<NavKey>(SettingsDestination.Settings), stack.toList())
    }

    @Test fun foldingAndUnfoldingKeepTheListsSheetAndScroll() {
        // Folded: one column, scrolled to the bottom, with the delete sheet open (nothing is deleted).
        width = 400.dp
        host(SettingsDestination.Settings)
        compose.onNodeWithTag("settings-delete-wallet").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("delete-wallet-sheet").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("settings-profile").assertIsNotDisplayed()
        // Unfolding recreates the activity into the split: the list beside the page keeps both.
        compose.activityRule.scenario.recreate()
        val region = compose.activity.window.decorView.let { book(it.width, it.height) }
        compose.runOnUiThread { width = null; fold = region }
        hosts--; host(SettingsDestination.Settings)
        compose.onNodeWithTag("settings-list-pane").assertExists()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("delete-wallet-sheet").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("settings-profile").assertIsNotDisplayed()
        // And folding again brings both back to the one column.
        compose.activityRule.scenario.recreate()
        compose.runOnUiThread { width = 400.dp; fold = null }
        hosts--; host(SettingsDestination.Settings)
        compose.onNodeWithTag("settings-list-pane").assertDoesNotExist()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("delete-wallet-sheet").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("settings-profile").assertIsNotDisplayed()
    }

    @Test fun recreationKeepsThePageAndTheRevealedPhraseButNeverReShowsIt() {
        val sample = "sample phrase only never use this example test word copy nothing real".split(' ')
        host(SettingsDestination.Seed)
        lateinit var revealed: RevealedPhrase
        compose.activityRule.scenario.onActivity { revealed = ViewModelProvider(it)[RevealedPhrase::class.java]; revealed.show(sample, model.walletLock.generation) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("recovery-word-1", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // A fold or rotation recreates the activity: the page and the words stay, without asking again.
        compose.activityRule.scenario.recreate()
        hosts--; host(SettingsDestination.Seed)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("recovery-word-1", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // After leaving Noah, a recreation shows the gate, not the words.
        compose.runOnUiThread { model.walletLock.background() }
        compose.activityRule.scenario.recreate()
        host(SettingsDestination.Seed)
        compose.onNodeWithTag("recovery-show").assertExists()
        assertTrue(compose.onAllNodesWithTag("recovery-word-1", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }
}
