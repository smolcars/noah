package com.noahwallet.ui.send

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.noahwallet.formatting.BitcoinAmountUnit
import com.noahwallet.history.HistoryTransaction
import com.noahwallet.send.*
import com.noahwallet.services.wallet.ChainStatus
import com.noahwallet.ui.*
import java.util.Locale

/**
 * Debug-only Send fixtures: public sample data driving today's SendModel and SendController through a fake
 * wallet, for previews, galleries and device tests. Never a real wallet, balance or seed.
 */
object SendFixtures {
    const val domain = "localhost.com"
    // Public regtest addresses (the dev stack's own), so today's parser accepts them; the invoice is display-only.
    const val invoice = "lnbcrt50u1pjsrfixturexyzqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
    const val onchain = "bcrt1q5mq29kaq9kq660m3eepqy8pkv7jhefd69klc22"
    const val ark = "tark1pj8a9t8ezqqpfdvjfqfst3xzwxnhq7r5lyzg8hp2g7gqdun2vevz5jaaqd7ry7xezqyp6e3ghflqezh7k5hkwav8m4fahfaaqtnuqhykjjf5tsvwyt309xkctc2n22"
    val owner = SendIdentity("fixture", "regtest")

    /** How a fixture submission ends. */
    enum class Outcome { arkSent, lightningSent, chainSent, chainConfirmed, broadcastPending, pending, refunded, notSent, alreadyPaid, unknown, never }

    /** A fake wallet: quotes [quote], and pays by ending as [outcome] once [gate] opens. */
    class Wallet(var outcome: Outcome = Outcome.never, var quote: SendQuote = SendQuote(500, 0, 500, walletIdentity = owner)) : SendWallet {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        override suspend fun identity() = owner
        override suspend fun quote(payment: SendPayment) = quote
        override suspend fun prepare(payment: SendPayment, quote: SendQuote) = SendSnapshot(walletIdentity = owner)
        override suspend fun pay(payment: SendPayment, quote: SendQuote, snapshot: SendSnapshot): SendReceipt {
            calls++
            if (outcome == Outcome.never) kotlinx.coroutines.awaitCancellation()
            gate.await()
            return when (outcome) {
                Outcome.arkSent -> SendReceipt(payment.amount, proof = SendReceipt.Proof.submitReturnedArkSuccess)
                Outcome.lightningSent -> SendReceipt(payment.amount, paymentHash = "hash", movementID = 9u, fee = 4, proof = SendReceipt.Proof.actualMovement)
                Outcome.chainSent,
                Outcome.chainConfirmed -> SendReceipt(payment.amount, txid = "f".repeat(64), fee = 420, proof = SendReceipt.Proof.submitReturnedTxid)
                Outcome.broadcastPending -> SendReceipt(payment.amount, txid = "f".repeat(64), fee = 420, proof = SendReceipt.Proof.submitReturnedTxid,
                    broadcastRetryPending = true)
                Outcome.pending -> throw SendExistingLightning(SendLightningProgress(payment.amount, 12))
                Outcome.notSent -> throw SendNotSubmitted(SendFailure.Reason.fundsChanged)
                Outcome.alreadyPaid -> throw SendAlreadyPaid(SendReceipt(payment.amount, paymentHash = "hash", movementID = 9u, fee = 4,
                    proof = SendReceipt.Proof.actualMovement))
                Outcome.refunded, Outcome.unknown -> error("Fixture payment call failed")
                Outcome.never -> error("unreachable")
            }
        }
        override suspend fun refreshReceipt(payment: SendPayment, receipt: SendReceipt) = receipt.copy(chainStatus =
            if (outcome == Outcome.chainConfirmed) ChainStatus(ChainStatus.Kind.confirmed, 921_346) else ChainStatus(ChainStatus.Kind.inMempool))
        override suspend fun reconcile(attempt: SendAttempt): SendResolution =
            if (outcome == Outcome.refunded) SendResolution.Failed else SendResolution.Uncertain
        override suspend fun discard(quote: SendQuote, includingClaimed: Boolean) = Unit
        override suspend fun discardAll() = Unit
    }

    /** A presentation over a fresh controller and [wallet]; balances as Home's Everyday fixture. */
    internal fun presentation(wallet: Wallet = Wallet()): SendPresentation {
        val controller = SendController(SendController.Dependencies(read = { null }, write = {}, reservation = { it(wallet) }))
        val model = SendModel(controller, "regtest", { destination ->
            SendRecipientRoute(destination, 1_000, 2_100_000_000_000L, 144, if (destination.endsWith("@$domain")) ark else null,
                okhttp3.HttpUrl.Builder().scheme("https").host(domain).build(), null)
        }, wallet::quote, {})
        model.arkBalance = 152_742; model.onchainBalance = 10_000; model.btcPrice = 112_000.0; model.fiatCurrency = "USD"
        return SendPresentation(model)
    }

    fun history(now: java.util.Date = java.util.Date()): List<HistoryTransaction> = listOf(
        row("r-1", "maya@$domain", now, 3), row("r-2", "lucas@getalby.com", now, 30), row("r-3", "bob@getalby.com", now, 90),
        row("r-4", onchain, now, 300, "Onchain"),
        HistoryTransaction("movement-9", "Bolt11", 2_500, com.noahwallet.history.HistoryRules.isoDate(now), "outgoing", source = "ark", movementId = 9u,
            movementStatus = "successful", preimage = "9f2c" + "0".repeat(56) + "a41e"),
    )
    private fun row(id: String, destination: String, now: java.util.Date, minutes: Long, type: String = "Lnurl") =
        HistoryTransaction(id, type, 1_000, com.noahwallet.history.HistoryRules.isoDate(java.util.Date(now.time - minutes * 60_000)), "outgoing",
            source = "ark", destination = destination, movementStatus = "successful")

    fun env(tip: Long? = 921_345, refund: SendRefund? = null, estimates: Map<SendRail,
        Long> = mapOf(SendRail.ark to 0L, SendRail.lightning to 12L, SendRail.onchain to 420L),
        chainNeeded: Long = 2) = SendEnv(BitcoinAmountUnit.bip177, Locale.US, "regtest", domain, history(), tip = tip, chainNeeded = chainNeeded,
        refund = refund, estimates = estimates)

    fun request(amount: Long? = null): String {
        val amounts = if (amount != null) "amount=${java.math.BigDecimal.valueOf(amount, 8).stripTrailingZeros().toPlainString()}&lightning=$invoice&" else ""
        return "bitcoin:$onchain?" + amounts + "ark=$ark"
    }

    /** Starts a motion whose clock reads [at] ms now, with [reached] milestones. */
    fun motion(route: SendRoute, at: Long, vararg reached: Pair<SendMilestone, Long>) =
        SendMotion(route, SystemClock.elapsedRealtime() - at, mapOf(SendMilestone.submitted to 0L) + reached)
}

/**
 * Send as the app hosts it: inside the safe-drawing insets, the monochrome navigation (bar or rail) with Send
 * owning the whole window, and [SendContent] in the frame that resolves the pose.
 */
@Composable
internal fun SendPreviewHost(presentation: SendPresentation, env: SendEnv, actions: SendActions = SendActions()) {
    val chrome = remember { WindowChrome() }
    val insets = WindowInsets.safeDrawing
    CompositionLocalProvider(LocalWindowChrome provides chrome) {
        Surface {
            Box(Modifier.fillMaxSize().drawBehind {
                chrome.background?.let { drawRect(it) }
                chrome.bottom?.let { val inset = insets.getBottom(this).toFloat(); drawRect(it, Offset(0f, size.height - inset), Size(size.width, inset)) }
            }.windowInsetsPadding(insets)) {
                val palette = com.noahwallet.ui.onboarding.noahPalette()
                SideEffect { chrome.background = palette.background }
                val model = presentation.model
                AdaptiveNavigation(Destination.Send, {}, fullWindow = true, showBar = model.stage == SendStage.amount && !model.controller.locked) { padding ->
                    Box(Modifier.padding(padding)) {
                        com.noahwallet.ui.onboarding.OnboardingFrame(null, splitAtWindowCentre = true) { SendContent(presentation, env, actions) }
                    }
                }
            }
        }
    }
}
