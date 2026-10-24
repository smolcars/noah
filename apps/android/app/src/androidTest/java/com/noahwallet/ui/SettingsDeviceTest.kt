package com.noahwallet.ui

import android.app.Application
import androidx.activity.BackEventCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
                        Box(Modifier.fillMaxSize()) { SettingsHost(model, stack, close = { closed++ }, myCode = {}, preview = SettingsPreview(sheet, phrase)) }
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
