package com.noahwallet.ui.receive

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.noahwallet.formatting.BitcoinAmountUnit
import com.noahwallet.services.wallet.ReceiveRequest
import com.noahwallet.ui.*
import com.noahwallet.ui.send.SendFixtures
import java.util.Locale

/** Debug-only Receive fixtures: public sample addresses and invoices, never a real wallet. */
object ReceiveFixtures {
    const val address = "maya@localhost.com"
    val plain = ReceiveRequest(SendFixtures.ark, SendFixtures.onchain)
    val withAmount = plain.copy(amountSat = 2_100, description = "Coffee", lightningInvoice = SendFixtures.invoice, paymentHash = "hash")

    /** [state]: address, no-address, request, amount, invoice, ark, onchain, loading, error. */
    fun ui(state: String, arrival: ReceiveArrival? = null, confirmedAt: Long? = null): ReceiveUi {
        val request = when (state) { "amount", "invoice" -> withAmount; "loading", "error" -> null; else -> plain }
        val format = when (state) {
            "request", "amount" -> ReceiveFormat.request
            "invoice" -> ReceiveFormat.invoice
            "ark" -> ReceiveFormat.ark
            "onchain" -> ReceiveFormat.onchain
            "error" -> ReceiveFormat.request
            else -> ReceiveFormat.lightningAddress
        }
        return ReceiveUi(request, if (state == "no-address") null else address, format, BitcoinAmountUnit.bip177, Locale.US, 112_000.0, "USD",
            error = if (state == "error") "Could not generate a receive request. Please try again." else null,
            arrival = arrival, tip = 921_345, confirmedAt = confirmedAt, listening = state != "error" && state != "loading" && arrival == null)
    }

    /** An arrival whose clock reads [at] ms now. */
    fun arrival(rail: ReceiveArrival.Rail, at: Long, sats: Long = if (rail == ReceiveArrival.Rail.onchain) 100_000 else 2_100) =
        ReceiveArrival(rail, sats, SystemClock.elapsedRealtime() - at, if (rail == ReceiveArrival.Rail.onchain) "e".repeat(64) else null)
}

/** Receive as the app hosts it: safe-drawing insets, the navigation bar or rail, and the frame that resolves the pose. */
@Composable
fun ReceivePreviewHost(ui: ReceiveUi, actions: ReceiveActions = ReceiveActions()) {
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
                AdaptiveNavigation(Destination.Receive, {}, fullWindow = true) { padding ->
                    Box(Modifier.padding(padding)) {
                        com.noahwallet.ui.onboarding.OnboardingFrame(null, splitAtWindowCentre = true) { ReceiveContent(ui, actions) }
                    }
                }
            }
        }
    }
}
