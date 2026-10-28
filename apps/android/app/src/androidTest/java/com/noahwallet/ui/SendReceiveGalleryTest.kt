package com.noahwallet.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.noahwallet.send.*
import com.noahwallet.ui.receive.*
import com.noahwallet.ui.send.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Opt-in screenshot gallery of every Send and Receive state from public fixtures (`-e noahSRShots <label>`),
 * on the current device, posture, theme and animation setting. Send runs today's SendModel and SendController
 * against a fake wallet; nothing touches a real wallet. Motion frames are timed on the wall clock.
 */
class SendReceiveGalleryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun captureEveryState() {
        val label = InstrumentationRegistry.getArguments().getString("noahSRShots")
        assumeTrue(label != null)
        val only = InstrumentationRegistry.getArguments().getString("noahSRScenes")?.split(",")?.toSet()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.filesDir, "sr-shots/$label").apply { deleteRecursively(); mkdirs() }
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        val shots = Shots(folder, only)
        compose.setContent { NoahTheme { shots.scene?.let { (name, content) -> key(name) { content() } } } }
        shots.sendSteps()
        shots.reviews()
        shots.sending()
        shots.endings()
        shots.receiving()
    }

    /** Shows each scene and screenshots it into [folder], skipping those not in [only]. */
    private inner class Shots(val folder: File, val only: Set<String>?) {
        var scene by mutableStateOf<Pair<String, @Composable () -> Unit>?>(null)
        fun settle(millis: Long) { compose.mainClock.advanceTimeBy(100); Thread.sleep(millis); compose.mainClock.advanceTimeBy(400) }
        fun save(name: String) {
            val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun show(name: String, wait: Long = 1_200, then: () -> Unit = {}, content: @Composable () -> Unit) {
            if (only != null && name !in only) return
            compose.runOnUiThread { scene = name to content }
            settle(wait); then(); settle(400); save(name)
        }
        fun main(block: suspend () -> Unit) = runBlocking(Dispatchers.Main) { block() }
        /** A Send step over today's model, set up by [setup]. */
        fun send(name: String, wallet: SendFixtures.Wallet = SendFixtures.Wallet(), env: SendEnv = SendFixtures.env(), wait: Long = 1_200,
            setup: suspend (SendPresentation) -> Unit) {
            if (only != null && name !in only) return
            val presentation = SendFixtures.presentation(wallet)
            main { setup(presentation) }
            show(name, wait) { SendPreviewHost(presentation, env) }
        }
    }

    private fun Shots.sendSteps() {
        send("send-amount-empty") { }
        send("send-amount") { it.model.amount = "500" }
        send("send-amount-over") { it.model.amount = "200000" }
        send("send-amount-fiat") { it.model.amount = "12.5"; it.model.useFiat = true }
        send("send-to-recents") { it.model.amount = "500"; it.model.continueStage() }
        send("send-to-noah-user") {
            it.model.amount = "500"
            it.model.continueStage()
            it.model.setRecipient("maya@localhost.com")
            it.model.previewRecipient()
        }
        send("send-to-note") {
            it.model.amount = "500"
            it.model.continueStage()
            it.model.setRecipient("lucas@localhost.com")
            it.model.previewRecipient()
            it.model.comment = "Coffee"
        }
        send("send-to-address") { it.model.amount = "500"; it.model.continueStage(); it.model.setRecipient(SendFixtures.onchain) }
        send("send-to-no-matches") { it.model.amount = "500"; it.model.continueStage(); it.model.setRecipient("zzz") }
        send("send-to-error") {
            it.model.amount = "500"
            it.model.continueStage()
            it.model.setRecipient("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4")
            it.model.continueStage()
        }
        send("send-method") { it.model.amount = "500"; it.model.continueStage(); it.model.setRecipient(SendFixtures.request()); it.model.continueStage() }
        send("send-method-no-estimates", env = SendFixtures.env(estimates = emptyMap())) {
            it.model.amount = "500"
            it.model.continueStage()
            it.model.setRecipient(SendFixtures.request())
            it.model.continueStage()
        }
        send("send-method-unavailable") {
            it.model.arkBalance = 8_000
            it.model.amount = "9000"
            it.model.continueStage()
            it.model.setRecipient(SendFixtures.request())
            it.model.continueStage()
        }
        send("send-balance") { it.model.amount = "5000"; it.model.continueStage(); it.model.setRecipient(SendFixtures.onchain); it.model.continueStage() }
    }

    private fun Shots.reviews() {
        send("send-review-ark") { it.model.amount = "500"; it.model.continueStage(); it.model.setRecipient(SendFixtures.ark); it.model.continueStage() }
        send("send-review-lightning", SendFixtures.Wallet(quote = SendQuote(2_500, 12, 2_512, walletIdentity = SendFixtures.owner))) {
            it.model.amount = "2500"; it.model.continueStage(); it.model.setRecipient("maya@localhost.com"); it.model.continueStage()
            it.model.progress = it.model.progress.copy(selectedRail = SendRail.lightning, railConfirmed = true); it.model.continueStage()
        }
        send("send-review-note", SendFixtures.Wallet(quote = SendQuote(2_500, 12, 2_512, walletIdentity = SendFixtures.owner))) {
            it.model.amount = "2500"; it.model.continueStage(); it.model.setRecipient("lucas@localhost.com"); it.model.previewRecipient()
            it.model.comment = "Coffee and a croissant, thanks!"; it.model.continueStage()
        }
        send("send-review-onchain", SendFixtures.Wallet(quote = SendQuote(50_000, 420, 50_420, approximate = true, walletIdentity = SendFixtures.owner))) {
            it.model.amount = "50000"; it.model.continueStage(); it.model.setRecipient(SendFixtures.onchain); it.model.continueStage()
            it.model.source = SendSource.offchain; it.model.continueStage()
        }
        send("send-review-max", SendFixtures.Wallet(quote = SendQuote(9_580, 420, 10_000, approximate = true, vtxoIDs = listOf("v"),
            walletIdentity = SendFixtures.owner))) {
            it.model.maxSend()
            it.model.source = SendSource.offchain
            it.model.continueStage()
            it.model.setRecipient(SendFixtures.onchain)
            it.model.continueStage()
        }
    }

    private fun Shots.sending() {
        // Sending: phase frames (the controller holds in flight) and every ending (static last frames).
        fun inFlight(name: String, route: SendRoute, at: Long, vararg reached: Pair<SendMilestone, Long>, env: SendEnv = SendFixtures.env(),
            destination: String = SendFixtures.ark, method: String = "ark") {
            if (only != null && name !in only) return
            val wallet = SendFixtures.Wallet(SendFixtures.Outcome.never)
            val presentation = SendFixtures.presentation(wallet)
            val payment = SendPayment(destination, method, 500, if (route == SendRoute.chainWallet) SendSource.onchain else null)
            main { presentation.model.controller.submit(payment, SendQuote(500, 0, 500, walletIdentity = SendFixtures.owner)) }
            // Shown 1.9 s after it's set, so the motion starts that much earlier.
            presentation.motion = SendFixtures.motion(route, at - 1_900, *reached)
            show(name, 1_000) { SendPreviewHost(presentation, env) }
        }
        val paying = SendMilestone.paying to 300L
        inFlight("send-ark-split", SendRoute.ark, 900)
        inFlight("send-ark-sign", SendRoute.ark, 2_200, paying)
        inFlight("send-ark-deliver", SendRoute.ark, 2_550, paying, SendMilestone.sent to 2_000)
        inFlight("send-ark-finale", SendRoute.ark, 3_600, paying, SendMilestone.sent to 2_000)
        val ln = "maya@localhost.com"
        inFlight("send-ln-lock", SendRoute.lightning, 1_500, paying, destination = ln, method = "lnurl")
        inFlight("send-ln-hold", SendRoute.lightning, 2_150, paying, destination = ln, method = "lnurl")
        inFlight("send-ln-pay", SendRoute.lightning, 2_520, paying, destination = ln, method = "lnurl")
        inFlight("send-ln-key", SendRoute.lightning, 3_400, paying, SendMilestone.sent to 2_800, destination = ln, method = "lnurl")
        inFlight("send-ln-paid", SendRoute.lightning, 5_400, paying, SendMilestone.sent to 2_800, destination = ln, method = "lnurl")
        inFlight("send-ln-still-paying", SendRoute.lightning, 12_000, paying, destination = ln, method = "lnurl")
        inFlight("send-ln-fizzle", SendRoute.lightning, 3_150, paying, SendMilestone.refunded to 2_900, destination = ln, method = "lnurl")
        inFlight("send-ln-sign-back", SendRoute.lightning, 3_900, paying, SendMilestone.refunded to 2_900, destination = ln, method = "lnurl")
        inFlight("send-ln-back", SendRoute.lightning, 5_600, paying, SendMilestone.refunded to 2_900, destination = ln, method = "lnurl")
        inFlight("send-chain-swap", SendRoute.chainArk, 1_700, paying, destination = SendFixtures.onchain, method = "onchain")
        inFlight("send-chain-drop", SendRoute.chainArk, 2_250, paying, SendMilestone.sent to 1_500, destination = SendFixtures.onchain, method = "onchain")
        inFlight("send-chain-waiting", SendRoute.chainArk, 3_800, paying, SendMilestone.sent to 1_500, destination = SendFixtures.onchain, method = "onchain")
        inFlight("send-chain-wallet", SendRoute.chainWallet, 3_000, paying, SendMilestone.sent to 900, destination = SendFixtures.onchain, method = "onchain")
    }

    private fun Shots.endings() {
        fun ended(name: String, outcome: SendFixtures.Outcome, payment: SendPayment, env: SendEnv = SendFixtures.env(),
            quote: SendQuote = SendQuote(payment.amount, 0, payment.amount, walletIdentity = SendFixtures.owner),
            block: suspend (SendController) -> Unit = {}) {
            if (only != null && name !in only) return
            val wallet = SendFixtures.Wallet(outcome, quote).apply { gate.complete(Unit) }
            val presentation = SendFixtures.presentation(wallet)
            main {
                presentation.model.controller.submit(payment, quote)?.await()
                block(presentation.model.controller)
            }
            show(name, 1_400) { SendPreviewHost(presentation, env) }
        }
        val arkPayment = SendPayment(SendFixtures.ark, "ark", 500)
        val lnPayment = SendPayment("maya@localhost.com", "lnurl", 2_500)
        val chainPayment = SendPayment(SendFixtures.onchain, "onchain", 50_000, SendSource.offchain)
        ended("send-result-ark", SendFixtures.Outcome.arkSent, arkPayment)
        ended("send-result-lightning", SendFixtures.Outcome.lightningSent, lnPayment, quote = SendQuote(2_500, 12, 2_512, walletIdentity = SendFixtures.owner))
        ended("send-result-chain", SendFixtures.Outcome.chainSent, chainPayment,
            quote = SendQuote(50_000, 420, 50_420, approximate = true, walletIdentity = SendFixtures.owner))
        ended("send-result-chain-broadcast-pending", SendFixtures.Outcome.broadcastPending, chainPayment,
            quote = SendQuote(50_000, 420, 50_420, approximate = true, walletIdentity = SendFixtures.owner))
        ended("send-result-chain-confirmed", SendFixtures.Outcome.chainConfirmed, chainPayment, SendFixtures.env(tip = 921_346),
            SendQuote(50_000, 420, 50_420, approximate = true, walletIdentity = SendFixtures.owner))
        ended("send-result-chain-confirmed-2", SendFixtures.Outcome.chainConfirmed, chainPayment, SendFixtures.env(tip = 921_347),
            SendQuote(50_000, 420, 50_420, approximate = true, walletIdentity = SendFixtures.owner))
        ended("send-result-pending", SendFixtures.Outcome.pending, lnPayment, quote = SendQuote(2_500, 12, 2_512, walletIdentity = SendFixtures.owner))
        ended("send-result-refunded", SendFixtures.Outcome.refunded, lnPayment, SendFixtures.env(refund = SendRefund.recorded))
        ended("send-result-failed-no-refund", SendFixtures.Outcome.refunded, lnPayment, SendFixtures.env(refund = SendRefund.none))
        ended("send-result-failed-retry", SendFixtures.Outcome.refunded, SendPayment(SendFixtures.invoice, "lightning", 5_000),
            SendFixtures.env(refund = SendRefund.untied))
        ended("send-result-not-sent", SendFixtures.Outcome.notSent, arkPayment)
        ended("send-result-already-paid", SendFixtures.Outcome.alreadyPaid, lnPayment)
        ended("send-result-unknown", SendFixtures.Outcome.unknown, arkPayment)
        ended("send-result-unavailable", SendFixtures.Outcome.arkSent, arkPayment) { it.invalidateForWalletChange() }
    }

    private fun Shots.receiving() {
        // Receive: every format, the sheets, errors and each arrival.
        fun receive(name: String, ui: ReceiveUi, wait: Long = 2_200, then: () -> Unit = {}) = show(name, wait, then) { ReceivePreviewHost(ui) }
        for (state in listOf("address", "no-address", "request", "amount", "invoice", "ark", "onchain", "loading", "error"))
            receive("receive-$state", ReceiveFixtures.ui(state))
        fun tap(tag: String) { compose.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            compose.mainClock.advanceTimeBy(500); Thread.sleep(700); compose.mainClock.advanceTimeBy(1_500) }
        // Compact only: wide and tabletop show the formats beside or below the card, with no sheet.
        receive("receive-formats-sheet", ReceiveFixtures.ui("address")) {
            if (compose.onAllNodesWithTag("receive-formats").fetchSemanticsNodes().isNotEmpty()) tap("receive-formats")
        }
        receive("receive-formats-sheet-amount", ReceiveFixtures.ui("amount")) {
            if (compose.onAllNodesWithTag("receive-formats").fetchSemanticsNodes().isNotEmpty()) tap("receive-formats")
        }
        receive("receive-amount-sheet", ReceiveFixtures.ui("address")) {
            tap("receive-amount-button")
            for (key in "2100") {
                compose.onNodeWithTag("send-key-$key", useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
                compose.mainClock.advanceTimeBy(300)
            }
            tap("receive-note-chip")
        }
        val ark = ReceiveArrival.Rail.ark; val lightning = ReceiveArrival.Rail.lightning; val chain = ReceiveArrival.Rail.onchain
        // Arrival frames: the arrival is set 2.6 s before the capture.
        for ((name, rail, at) in listOf(Triple("receive-ark-drop", ark, 700L), Triple("receive-ark-ripple", ark, 1_000L),
            Triple("receive-ark-flipped", ark, 2_600L),
            Triple("receive-ln-incoming", lightning, 500L), Triple("receive-ln-holds", lightning, 1_200L), Triple("receive-ln-claiming", lightning, 1_900L),
            Triple("receive-ln-received", lightning, 2_600L), Triple("receive-ln-flipped", lightning, 3_900L),
            Triple("receive-chain-seen", chain, 500L), Triple("receive-chain-flipped", chain, 2_400L), Triple("receive-chain-waiting", chain, 5_000L))) {
            if (only != null && name !in only) continue
            receive(name, ReceiveFixtures.ui("address", ReceiveFixtures.arrival(rail, at - 2_600)), wait = 2_100)
        }
        receive("receive-chain-confirmed", ReceiveFixtures.ui("address", ReceiveFixtures.arrival(chain, 6_000), confirmedAt = 921_345))
        // A payment to your address after an on-chain arrival: it takes the card while the block row keeps following the deposit.
        receive("receive-ark-over-chain",
            ReceiveFixtures.ui("address", ReceiveFixtures.arrival(ark, 1_000 - 2_600)).copy(chain = ReceiveFixtures.arrival(chain, 20_000)), wait = 2_100)
    }
}
