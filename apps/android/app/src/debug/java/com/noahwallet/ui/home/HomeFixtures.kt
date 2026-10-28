@file:Suppress("LongParameterList") // fixture builders: every field is a named, defaulted argument

package com.noahwallet.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.noahwallet.formatting.BitcoinAmountUnit
import com.noahwallet.history.HistoryRules
import com.noahwallet.history.HistoryTransaction
import com.noahwallet.services.wallet.WalletBalance
import com.noahwallet.ui.*
import java.util.Date
import java.util.Locale

/**
 * Debug-only Home fixtures: public sample data for previews, galleries and device tests. Never a real
 * wallet, balance or seed. States follow docs/native-rewrite/design/home.md (Fresh, Everyday, Busy, Suspended).
 */
object HomeFixtures {
    const val address = "maya@noahwallet.io"
    private const val minute = 60_000L

    private fun at(now: Date, minutesAgo: Long) = HistoryRules.isoDate(Date(now.time - minutesAgo * minute))
    private fun ark(id: String, type: String, amount: Long, direction: String, now: Date, minutesAgo: Long, destination: String = "",
        status: String = "successful", kind: String? = null, effective: Long = if (direction == "outgoing") -amount else amount) =
        HistoryTransaction(id, type, amount, at(now, minutesAgo), direction, sortTimestamp = (now.time - minutesAgo * minute).toDouble(),
            source = "ark", destination = destination, movementStatus = status, movementKind = kind, effectiveBalanceSat = effective,
            intendedBalanceSat = if (status == "canceled") -amount else effective, offchainFeeSat = 0)
    private fun chain(id: String, amount: Long, now: Date, minutesAgo: Long, confirmed: Boolean) =
        HistoryTransaction(id, "Onchain", amount, if (confirmed) at(now, minutesAgo) else HistoryRules.isoDate(Date(0)), "incoming",
            dateLabel = if (confirmed) null else "Unconfirmed", sortTimestamp = if (confirmed) (now.time - minutesAgo * minute).toDouble() else null,
            source = "onchain-wallet", txid = id, balanceChangeSat = amount, hasConfirmation = confirmed)

    fun everydayRows(now: Date) = listOf(
        ark("f-1", "Arkoor", 2_100, "incoming", now, 40, kind = "arkoor-receive"),
        ark("f-2", "Lnurl", 4_200, "outgoing", now, 60 * 27, destination = "coffee@getalby.com"),
        ark("f-3", "Lnurl", 4_842, "outgoing", now, 60 * 18, destination = "lucas@noahwallet.io"),
        ark("f-4", "Bolt11", 21_000, "incoming", now, 60 * 30, kind = "lightning-receive"),
        ark("f-5", "Onchain", 150_000, "incoming", now, 60 * 24 * 10, kind = "onboard"),
        chain("f-6", 10_000, now, 60 * 24 * 13, confirmed = true),
        ark("f-7", "Arkoor", 500, "outgoing", now, 60 * 24 * 20, destination = "tark1qexample"),
    )
    fun busyRows(now: Date) = listOf(
        chain("b-1", 100_000, now, 0, confirmed = false),
        ark("b-2", "Lnurl", 5_000, "outgoing", now, 3, destination = "bob@getalby.com", status = "pending"),
        ark("b-3", "Onchain", 4_200, "incoming", now, 12, status = "failed", kind = "onboard", effective = 0),
        ark("b-4", "Bolt11", 1_500, "outgoing", now, 25, status = "canceled", effective = 0),
    ) + everydayRows(now)

    private fun balance(spendable: ULong, onchainConfirmed: ULong = 0u, onchainPending: ULong = 0u) = WalletBalance(
        WalletBalance.Ark(spendable, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u),
        WalletBalance.Onchain(onchainConfirmed, onchainPending, onchainConfirmed + onchainPending))

    val renew = HomeRules.Renew(20_000, 108, listOf("vtxo-a", "vtxo-b"))

    /** [state]: fresh, fresh-no-address, everyday, busy, suspended, loading. */
    fun ui(state: String, hidden: Boolean = false, now: Date = Date(), variant: String = "regtest", newRows: Set<String> = emptySet(),
        backedUp: Boolean = false, emailPrompt: Boolean = true, address: String = HomeFixtures.address): HomeUi {
        val locale = Locale.US
        // In history's own order, as the wallet's records come (pending on-chain first, then newest): unsorted, a row
        // 18 hours old after a 27-hour-old one splits "Today" in two after 18:00.
        val rows = HistoryRules.sorted(when (state) { "everyday" -> everydayRows(now); "busy" -> busyRows(now); else -> emptyList() })
        val wallet = when (state) {
            "fresh", "fresh-no-address" -> balance(0u)
            "everyday" -> balance(142_742u, onchainConfirmed = 10_000u)
            "busy" -> balance(142_742u, onchainConfirmed = 10_000u, onchainPending = 100_000u)
            else -> null
        }
        val home = HomeRules.state(state == "suspended", wallet?.totalSats, state == "loading", null, rows.isNotEmpty())
        val price = 112_000.0
        return HomeUi(
            state = home, address = if (state == "fresh-no-address") null else address, variant = variant, balance = wallet,
            unit = BitcoinAmountUnit.bip177, locale = locale, hidden = hidden,
            fiat = HomeRules.fiat(wallet?.totalSats?.toLong(), price, "USD", locale, hidden),
            status = HomeRules.status(null, wallet, rows, 0),
            nudge = HomeRules.nudge(emptyList(), if (state == "busy") renew else null, null, backedUp, emailPrompt, false),
            rows = rows, historyError = null, newRows = newRows, now = now,
        )
    }
}

/**
 * Home as the app hosts it: inside the safe-drawing insets, the monochrome navigation (bar or rail) with
 * Home owning the whole window, and [HomeContent] in the onboarding frame that resolves the pose. [content] is
 * Home's own sheets, in the same frame.
 */
@Composable
fun HomePreviewHost(ui: HomeUi, memory: HomeMemory = remember { HomeMemory() }, toasts: HomeToasts = remember { HomeToasts() },
    actions: HomeActions = HomeActions(), content: @Composable () -> Unit = {}) {
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
                AdaptiveNavigation(Destination.Home, {}, fullWindow = true) { padding ->
                    Box(Modifier.padding(padding)) {
                        com.noahwallet.ui.onboarding.OnboardingFrame(null, splitAtWindowCentre = true) {
                            HomeContent(ui, actions, memory, toasts, refreshing = false)
                            content()
                        }
                    }
                }
            }
        }
    }
}
