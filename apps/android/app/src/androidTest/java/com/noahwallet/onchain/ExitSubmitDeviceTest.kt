package com.noahwallet.onchain

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.services.storage.WalletDataLocation
import com.noahwallet.services.wallet.ReceiveRequest
import com.noahwallet.services.wallet.WalletDatabase
import com.noahwallet.services.wallet.WalletLifecycle
import com.noahwallet.services.wallet.WalletSession
import com.noahwallet.ui.LocalFoldingFeatures
import com.noahwallet.ui.NoahTheme
import com.noahwallet.ui.WindowRegion
import com.noahwallet.ui.settings.SettingsNav
import io.github.niteshbalusu11.bark.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Start and claim submit once through the pages' own wiring (exit.md, Testing): the page, its model, the session's locks,
 * the operations and the journal on disk, with only bark faked. A posture change while bark is still starting or claiming
 * recreates the page and its model on the same session; process death brings a new session reading the same journal.
 * Through both, bark is asked to start once and to claim once.
 */
class ExitSubmitDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val f = ExitFixtures
    private val regtest = "bcrt1psagmjg628zl8zj5qzxszhtvlx3hhhq8y5a438l9vmfasu2yschnqwyxvxg"
    private val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "exit-submit-${System.nanoTime()}")
    private val location = WalletDataLocation(root, "regtest")
    private val id = f.vtxos[0].id
    @After fun clean() { root.deleteRecursively() }

    /** bark's side, which outlives any one process: the coin, its exit, and every call that changes them. */
    private class Bark(val id: String) : OnchainWalletAccess {
        override val walletID = "fixture-wallet"
        private val tip = 921_345
        @Volatile var spendable = true
        @Volatile var exit: ExitState? = null
        val calls: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        /** Holds a start or claim broadcast in flight until released. */
        @Volatile var gate: CompletableDeferred<Unit>? = null
        /** Holds an estimate until released; [estimating] says one is waiting. */
        @Volatile var estimateGate: CompletableDeferred<Unit>? = null
        @Volatile var estimating = false
        @Volatile var drainFee = 262uL
        private fun record() = Vtxo("server", 144u, OutPoint("anchor", 0u), OutPoint("point", 0u), id, 31_000u, (tip + 4_176).toUInt(), "round",
            if (spendable) VtxoState.Spendable else VtxoState.Exited, 4u, 1_620u, true)
        override suspend fun tip() = tip.toLong()
        override suspend fun expiring(threshold: UInt) = emptySet<String>()
        override suspend fun vtxos() = listOf(record())
        override suspend fun vtxo(id: String) = record()
        override suspend fun confirmed() = 18_400L
        override suspend fun pendingTotal() = 0L
        override suspend fun claimableHeight(): Long? = null
        override suspend fun transactions() = emptyList<WalletTransaction>()
        override suspend fun transactionStatus(txid: String): TransactionStatus = TransactionStatus.InMempool
        override suspend fun exits() = listOfNotNull(exit?.let { ExitVtxo(id, 31_000u, it, it is ExitState.Claimable) })
        override suspend fun status(id: String) = exit?.let { ExitTransactionStatus(id, 31_000u, it, listOf(ExitState.Start(tip.toUInt())), 0u, emptyList()) }
        override suspend fun allExits() = listOfNotNull(status(id))
        override suspend fun exitFee(ids: List<String>, destination: String?): EmergencyExitFeeEstimate {
            estimating = true; try { estimateGate?.await() } finally { estimating = false }
            return EmergencyExitFeeEstimate(5_533u, 262u, 5_795u, 3u, 4u, emptyList())
        }
        override suspend fun destinationIsMine(address: String) = false
        override suspend fun start(ids: List<String>) {
            calls += "start"; gate?.await()
            spendable = false; exit = ExitState.Start(tip.toUInt())
        }
        override suspend fun drain(ids: List<String>, destination: String): ExitClaimTransaction {
            calls += "drain"
            return ExitClaimTransaction("psbt", drainFee)
        }
        override suspend fun claimTransaction(psbt: String) = ExitFixtures.signedClaim
        override suspend fun broadcastClaim(psbt: String): String {
            calls += "broadcast"; gate?.await()
            exit = ExitState.ClaimInProgress(tip.toUInt(), BlockRef(tip.toUInt(), "block"), "claim-tx"); return "claim-tx"
        }
    }

    /** The native wallet a session opens: only bark's on-chain side is used here. */
    private class Handles(override val m6: OnchainWalletAccess) : WalletSession.Handles {
        override fun notifications(): WalletSession.Notifications = error("unused")
        override suspend fun history(): List<Movement> = emptyList()
        override suspend fun receiveRequest(): ReceiveRequest = error("unused")
        override suspend fun receiveInvoice(request: ReceiveRequest, amountSat: Long, description: String): ReceiveRequest = error("unused")
        override suspend fun initializeAddress() = Unit
        override suspend fun peekPublicKey(index: UInt): String = error("unused")
        override suspend fun peekAddress(index: UInt): String = error("unused")
        override suspend fun summary(): WalletSession.Summary = error("unused")
        override suspend fun sync() = Unit
        override suspend fun maintenanceDelegated() = Unit
        override suspend fun bolt11Invoice(amountSats: ULong): String = error("unused")
        override suspend fun claimLightningReceives() = Unit
        override suspend fun createSnapshot(destination: File): WalletSnapshotInfo = error("unused")
        override suspend fun stopDaemonWait() = Unit
        override fun close() = Unit
    }

    /** One app process: its session (with its journal on disk) and its wallet-mutation gate, as AppServices holds them. */
    private class Process(val session: WalletSession, val lifecycle: WalletLifecycle)
    private fun launch(bark: Bark): Process = runBlocking {
        val session = WalletSession("regtest", location, inspect = { WalletDatabase.Properties("regtest", "fixture") },
            opener = { _, _, _, _, _ -> Handles(bark) })
        if (location.databaseExists) session.openExisting("unused", false, "test")
        else { session.createNew("unused", false, "test"); location.database.writeText("fixture") }
        Process(session, WalletLifecycle(session, location, setSuspended = {}, deregister = {}, readSeed = { error("unused") }, deleteSeed = {},
            clearToken = {}, clearSettings = {}, clearWidget = {}, sweepScratch = {}))
    }

    private lateinit var process: Process
    private var fold by mutableStateOf<WindowRegion?>(null)
    private val models: MutableList<OnchainModel> = java.util.Collections.synchronizedList(mutableListOf())
    private val nav = SettingsNav(back = {}, open = {}, pop = {}, myCode = {}, home = {})

    /** Noah's claim records, on "disk": they outlive the process like the journal. */
    @Volatile private var claimed: String? = null
    private val claims = ExitClaimRecords({ claimed }, { claimed = it })
    private var shown by mutableStateOf(true)
    private var registry: SaveableStateRegistry? = null
    private var saved: Map<String, List<Any?>>? = null

    /**
     * A page as its Page composable builds it: a fresh model on this process's dependencies, read when it appears, under a
     * saved-state registry the test saves and restores as Android does when it recreates the activity.
     */
    private fun host(page: @Composable (OnchainModel) -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { NoahTheme { CompositionLocalProvider(LocalFoldingFeatures provides listOfNotNull(fold)) { ExitPreviewHost {
            if (shown) {
                val restored = remember { SaveableStateRegistry(saved) { true }.also { registry = it } }
                CompositionLocalProvider(LocalSaveableStateRegistry provides restored) {
                    val model = remember { OnchainModel(onchainDependencies(process.session, process.lifecycle, AutoBoardFunding({ null }, {}), claims,
                        ExitSeen({ null }, {})) { true })
                        .also { models += it } }
                    LaunchedEffect(model) { model.load() }
                    page(model)
                }
            }
        } } } }
    }

    /** Recreation: saved state is kept, the page and its model go, then [between] runs and they come back new. */
    private fun recreate(between: () -> Unit = {}) {
        compose.runOnUiThread { saved = checkNotNull(registry).performSave(); shown = false }
        compose.mainClock.advanceTimeBy(100)
        between()
        compose.runOnUiThread { shown = true }
        compose.mainClock.advanceTimeBy(100)
    }

    /** Lets the wallet's calls (on IO) and the page's own delays run until [done]. */
    private fun settle(what: String, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!done()) {
            assertTrue("timed out waiting for $what", System.currentTimeMillis() < deadline)
            compose.mainClock.advanceTimeBy(100); Thread.sleep(20)
        }
        compose.mainClock.advanceTimeBy(300)
    }
    private fun journal() = runBlocking { process.session.onchainJournal.loadAll() }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun enabled(tag: String) = compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    private fun hold(tag: String) {
        compose.onNodeWithTag(tag).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(300)
    }
    /** A posture change: the activity is recreated in a book posture, so the page and its model are new. */
    private fun turn() {
        lateinit var book: WindowRegion
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView
            book = WindowRegion(view.width / 2 - 12, 0, view.width / 2 + 12, view.height)
        }
        recreate { compose.runOnUiThread { fold = book } }
    }
    /** Process death: a new session reads the same files; saved state comes back as the system restores it. */
    private fun relaunch(bark: Bark) = recreate {
        runBlocking { process.session.close() }
        process = launch(bark)
    }

    @Test fun startingAsksBarkOnceThroughAPostureChangeAndProcessDeath() {
        val bark = Bark(id)
        process = launch(bark)
        // A Check the chain proof from an earlier run of this exit: starting must forget it, even though the page goes mid-start.
        ExitFunding.shared.checked(listOf(f.nofunds), completed = true)
        host { model -> ExitStartScreen(model, nav, listOf(id), false, 144, f.money) {} }
        settle("the review") { enabled("exit-hold-start") }
        bark.gate = CompletableDeferred()
        hold("exit-hold-start")
        settle("bark's start") { bark.calls.isNotEmpty() }
        // Still starting: the page comes back new, but the journal it reads is busy with the start.
        turn()
        compose.mainClock.advanceTimeBy(2_000); Thread.sleep(500); compose.mainClock.advanceTimeBy(500)
        assertEquals("a new page and model", 2, models.size)
        if (enabled("exit-hold-start")) hold("exit-hold-start")
        checkNotNull(bark.gate).complete(Unit)
        settle("the start to land") { journal().any { it.receipt != null } && models.last().revision > 0 && !models.last().busy }
        assertEquals(listOf("start"), bark.calls)
        assertNull("the journal holds the started exit", models.last().quote)
        assertFalse("nothing left to start", exists("exit-hold-start"))
        relaunch(bark)
        settle("the relaunched page") { models.size == 3 && models.last().revision > 0 && !models.last().busy }
        if (enabled("exit-review-again")) {
            compose.onNodeWithTag("exit-review-again").performClick()
            settle("another review") { !models.last().busy }
        }
        assertFalse("the journal blocks a second start", exists("exit-hold-start"))
        assertEquals(listOf("start"), bark.calls)
        assertEquals(1, journal().count { it.quote.action == OnchainAction.startExit })
        assertTrue("the earlier run's proof is gone", ExitFunding.shared.needsFunds(listOf(f.nofunds), 0, null).isEmpty())
        ExitFunding.shared.forget(listOf(id))
    }

    @Test fun aClaimReviewDroppedByPausingIsAskedForAgain() {
        val bark = Bark(id).apply { spendable = false; exit = ExitState.Claimable(921_493u, BlockRef(921_493u, "block"), null) }
        process = launch(bark)
        host { model -> ExitClaimScreen(model, nav, { error("unused") }, false, f.money) {} }
        settle("the claimable exit") { exists("exit-claim-address") }
        compose.onNode(hasSetTextAction() and (hasTestTag("exit-claim-address") or hasAnyAncestor(hasTestTag("exit-claim-address")))).performTextInput(regtest)
        settle("the review") { enabled("exit-hold-claim") }
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        // Paused, the page isn't in Compose's test hierarchy: the model says the review is gone.
        settle("pausing to drop it") { models.last().quote == null }
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        settle("the review again") { enabled("exit-hold-claim") }
        assertTrue("nothing was submitted", bark.calls.isEmpty())
    }

    @Test fun aPauseDuringASlowEstimateStillEndsInAClaimable() {
        val bark = Bark(id).apply { spendable = false; exit = ExitState.Claimable(921_493u, BlockRef(921_493u, "block"), null) }
        process = launch(bark)
        host { model -> ExitClaimScreen(model, nav, { error("unused") }, false, f.money) {} }
        settle("the claimable exit") { exists("exit-claim-address") }
        bark.estimateGate = CompletableDeferred()
        compose.onNode(hasSetTextAction() and (hasTestTag("exit-claim-address") or hasAnyAncestor(hasTestTag("exit-claim-address")))).performTextInput(regtest)
        settle("the slow estimate") { bark.estimating }
        // Paused and back while bark still estimates: the replacement waits for it rather than being dropped.
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        compose.mainClock.advanceTimeBy(500)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue("bark is still estimating the first review", bark.estimating)
        checkNotNull(bark.estimateGate).complete(Unit)
        settle("the replacement review") { enabled("exit-hold-claim") }
    }

    @Test fun aChangedClaimFeeOffersReviewAgain() {
        val bark = Bark(id).apply { spendable = false; exit = ExitState.Claimable(921_493u, BlockRef(921_493u, "block"), null) }
        process = launch(bark)
        host { model -> ExitClaimScreen(model, nav, { error("unused") }, false, f.money) {} }
        settle("the claimable exit") { exists("exit-claim-address") }
        compose.onNode(hasSetTextAction() and (hasTestTag("exit-claim-address") or hasAnyAncestor(hasTestTag("exit-claim-address")))).performTextInput(regtest)
        settle("the review") { enabled("exit-hold-claim") }
        bark.drainFee = 300u
        hold("exit-hold-claim")
        // The drain's fee differs from the review's: nothing is sent, and the same destination can be reviewed again.
        settle("Review again") { enabled("exit-review-again") }
        assertEquals(listOf("drain"), bark.calls)
        bark.drainFee = 262u
        compose.onNodeWithTag("exit-review-again").performClick()
        settle("the new review") { enabled("exit-hold-claim") }
    }

    @Test fun claimingAsksBarkOnceThroughAPostureChangeAndProcessDeath() {
        val bark = Bark(id).apply { spendable = false; exit = ExitState.Claimable(921_493u, BlockRef(921_493u, "block"), null) }
        process = launch(bark)
        host { model -> ExitClaimScreen(model, nav, { error("unused") }, false, f.money) {} }
        settle("the claimable exit") { exists("exit-claim-address") }
        compose.onNode(hasSetTextAction() and (hasTestTag("exit-claim-address") or hasAnyAncestor(hasTestTag("exit-claim-address")))).performTextInput(regtest)
        settle("the review") { enabled("exit-hold-claim") }
        bark.gate = CompletableDeferred()
        hold("exit-hold-claim")
        settle("bark's broadcast") { "broadcast" in bark.calls }
        // Still broadcasting: the new page keeps the address, but the journal holds the claim.
        turn()
        compose.mainClock.advanceTimeBy(2_000); Thread.sleep(500); compose.mainClock.advanceTimeBy(500)
        assertEquals("a new page and model", 2, models.size)
        if (enabled("exit-hold-claim")) hold("exit-hold-claim")
        checkNotNull(bark.gate).complete(Unit)
        settle("the claim to land") { journal().any { it.receipt?.txid == "claim-tx" } }
        compose.mainClock.advanceTimeBy(2_000); Thread.sleep(500); compose.mainClock.advanceTimeBy(500)
        assertFalse("the claimed exit can't be claimed again", enabled("exit-hold-claim"))
        relaunch(bark)
        settle("the relaunched page") { models.size == 3 && models.last().revision > 0 }
        compose.onNodeWithText("No exits are ready to claim.").assertExists()
        // Its hold stays in the foot, off: holding it anyway reaches nothing.
        assertFalse(enabled("exit-hold-claim"))
        hold("exit-hold-claim"); settle("the hold to end") { !models.last().busy }
        assertEquals(listOf("drain", "broadcast"), bark.calls)
        assertEquals(1, journal().count { it.quote.action == OnchainAction.claim })
        assertEquals("the claim's record, for its exit's page", ExitClaimRecords.Record("claim-tx", 262, listOf(id), false, regtest), claims.of(id, "claim-tx"))
    }
}
