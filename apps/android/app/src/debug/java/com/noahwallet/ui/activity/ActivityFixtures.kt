@file:Suppress("LongParameterList") // fixture builders: every field is a named, defaulted argument

package com.noahwallet.ui.activity

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.noahwallet.formatting.BitcoinAmountUnit
import com.noahwallet.history.HistoryMapping
import com.noahwallet.history.HistoryRules
import com.noahwallet.history.HistoryTransaction
import com.noahwallet.recurring.RecurringInterval
import com.noahwallet.send.PaymentEncoding
import com.noahwallet.ui.*
import io.github.niteshbalusu11.bark.BlockRef
import io.github.niteshbalusu11.bark.Movement
import io.github.niteshbalusu11.bark.MovementDestination
import io.github.niteshbalusu11.bark.PaymentMethod
import io.github.niteshbalusu11.bark.WalletTransaction
import java.util.Date
import java.util.Locale

/**
 * Debug-only Activity fixtures: public sample data for galleries and device tests, after the prototype's sets
 * (activity-v1, now only in git history). Never a real wallet. States: everyday, busy, empty, loading, error.
 */
object ActivityFixtures {
    private const val minute = 60_000L
    const val tip = 921_345L
    val signedTxid = "5e".repeat(32)

    /** A checksummed BOLT11 invoice carrying [description] (unsigned: decoding only reads tagged fields). */
    fun invoice(description: String, seed: Int): String {
        val words = mutableListOf<Int>()
        fun field(tag: Int, data: List<Int>) { words += tag; words += data.size / 32; words += data.size % 32; words += data }
        fun toWords(bytes: ByteArray): List<Int> {
            val out = mutableListOf<Int>(); var acc = 0; var bits = 0
            for (b in bytes) { acc = (acc shl 8) or (b.toInt() and 255); bits += 8; while (bits >= 5) { bits -= 5; out += (acc shr bits) and 31 }
                acc = acc and ((1 shl bits) - 1) }
            if (bits > 0) out += (acc shl (5 - bits)) and 31
            return out
        }
        words += List(7) { (seed + it) % 32 }
        field(1, toWords(ByteArray(32) { (seed * 7 + it).toByte() }))
        field(13, toWords(description.toByteArray()))
        words += List(104) { 0 }
        return bech32("lnbcrt", words)
    }
    private fun bech32(prefix: String, words: List<Int>): String {
        val alphabet = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        fun polymod(values: List<Int>): Int {
            var check = 1
            val generators = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
            for (word in values) { val top = check ushr 25; check = ((check and 0x1ffffff) shl 5) xor word
                for (i in 0..4) if ((top shr i) and 1 != 0) check = check xor generators[i] }
            return check
        }
        val expanded = prefix.map { it.code shr 5 } + 0 + prefix.map { it.code and 31 }
        val check = polymod(expanded + words + List(6) { 0 }) xor 1
        return prefix + "1" + (words + List(6) { (check shr (5 * (5 - it))) and 31 }).joinToString("") { alphabet[it].toString() }
    }
    fun offer(description: String): String {
        val text = description.toByteArray()
        val tlv = byteArrayOf(10, text.size.toByte()) + text + byteArrayOf(22, 33, 2) + ByteArray(32) { it.toByte() }
        return PaymentEncoding.encodeBech32("lno", tlv).dropLast(6)
    }
    private fun hex(seed: String, length: Int = 64) = seed.hashCode().toUInt().toString(16).padStart(8, '0').repeat(length / 8 + 1).take(length)

    private fun at(now: Date, minutes: Long) = HistoryRules.isoDate(Date(now.time - minutes * minute))
    private fun vtxos(id: String, n: Int, vout: Int = 0) = List(n) { hex("$id-$vout-$it") + ":$it" }
    private fun to(method: String, value: String, amount: Long) = MovementDestination(PaymentMethod(method, value), amount.toULong())

    /** A bark movement read through the app's own mapping, so fixtures show exactly what a wallet's records would. */
    private fun ark(n: Int, subsystem: String, now: Date, minutes: Long, intended: Long, effective: Long = intended, fee: Long = 0,
        status: String = "successful", sentTo: List<MovementDestination> = emptyList(), receivedOn: List<MovementDestination> = emptyList(),
        inputs: Int = 1, outputs: Int = 1, exited: Int = 0, metadata: String = "{}"): HistoryTransaction {
        val (name, kind) = subsystem.split(':')
        val created = at(now, minutes)
        return HistoryMapping.transaction(Movement(n.toUInt(), status, name, kind, metadata, intended, effective, fee.toULong(), sentTo, receivedOn,
            vtxos("m$n-in", inputs), vtxos("m$n-out", outputs, 1), vtxos("m$n-exit", exited, 2), created, created, null, null, null, null), now)
    }
    private fun chain(id: String, amount: Long, now: Date, minutes: Long, confirmed: Boolean, fee: Long? = null): HistoryTransaction {
        val height = tip - minutes / 10
        val transaction = WalletTransaction(hex(id), "02000000000101" + hex("$id-raw", 120) + "00000000", fee?.toULong(), amount,
            if (confirmed) BlockRef(height.toUInt(), "0000000000000000000" + hex("$id-bh", 45)) else null, false)
        return HistoryMapping.transaction(transaction, if (confirmed) Date(now.time - minutes * minute) else null)
    }
    private fun repeat(destination: String, amount: Long, comment: String = "", fiat: String? = null): String {
        val mode = if (fiat != null) "fiat" else "sats"
        val currency = if (fiat != null) ",\"fiat_currency\":\"USD\"" else ""
        val payment = """"version":1,"destination":"$destination","comment":"$comment","amount_mode":"$mode","amount_value":"${fiat ?: amount}"$currency"""
        return """{"noah":{"repeat_payment":{$payment}}}"""
    }
    private fun preimage(id: String) = """{"payment_preimage":"${hex("$id-preimage")}"}"""

    private fun settled(now: Date): List<HistoryTransaction> {
        val day = 60L * 24
        val monthly = HistoryTransaction.Recurring("savings", "Savings", RecurringInterval("month", 1))
        return listOf(
            ark(2, "bark.lightning_receive:receive", now, 60 * 15 + 36, 4_842, receivedOn = listOf(to("invoice", invoice("Lunch split", 2), 4_842)),
                metadata = preimage("r2")),
            ark(3, "bark.lightning_send:send", now, 60 * 25 + 1, -4_200, -4_212, 12, sentTo = listOf(to("invoice", invoice("Coffee Collective", 3), 4_200)),
                metadata = preimage("r3")),
            ark(4, "bark.lightning_send:send", now, 60 * 36 + 11, -5_000, -5_015, 15, sentTo = listOf(to("lightning-address", "bob@getalby.com", 5_000)),
                metadata = repeat("bob@getalby.com", 5_000, "Pizza night", "5.60").dropLast(1) + "," + preimage("r4").drop(1)),
            ark(6, "bark.arkoor:send", now, day * 3 + 101, -10_000, sentTo = listOf(to("ark", "tark1qrosa", 10_000)),
                metadata = repeat("rosa@noahwallet.io", 10_000))
                .copy(recurring = monthly),
            ark(7, "bark.arkoor:send", now, day * 5 - 400, -500, sentTo = listOf(to("ark", "tark1qnitesh", 500)),
                metadata = repeat("nitesh@noahwallet.io", 500)),
            ark(8, "bark.round:refresh", now, day * 5 + 389, 0, -45, 45, inputs = 3),
            ark(9, "bark.offboard:send_onchain", now, day * 7 - 66, -53_000, -53_420, 420,
                sentTo = listOf(to("bitcoin", "bcrt1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0h7r3", 53_000)), metadata = """{"offboard_txid":"${hex("r9-tx")}"}"""),
            ark(11, "bark.board:board", now, day * 11 + 1_100, 140_000, inputs = 0,
                metadata = """{"chain_anchor":"${hex("r12")}:0","onchain_fee_sat":310}"""),
            chain("r12", -140_310, now, day * 11 + 1_100, confirmed = true, fee = 310),
            chain("r13", 150_000, now, day * 11 + 1_180, confirmed = true),
            ark(14, "bark.lightning_send:send", now, day * 15 - 10, -3_300, -3_310, 10, sentTo = listOf(to("offer", offer("Satoshi Station"), 3_300)),
                metadata = preimage("r14")),
            ark(15, "bark.arkoor:receive", now, day * 16, 12_000, receivedOn = listOf(to("ark", "tark1qme", 12_000)), inputs = 0),
            ark(19, "bark.arkoor:send", now, day * 24 + 380, -1_000, sentTo = listOf(to("ark", "tark1qmaya", 1_000)),
                metadata = repeat("maya@noahwallet.io", 1_000, "For coffee")),
            ark(26, "bark.lightning_send:send", now, day * 35, -2_000, 0, status = "canceled",
                sentTo = listOf(to("lightning-address", "bob@getalby.com", 2_000)), outputs = 0),
        )
    }

    private fun pending(now: Date): List<HistoryTransaction> = listOf(
        chain("p1", 100_000, now, 0, confirmed = false),
        ark(102, "bark.lightning_send:send", now, 11, -5_000, -5_015, 15, status = "pending",
            sentTo = listOf(to("lightning-address", "bob@getalby.com", 5_000))),
        ark(103, "bark.offboard:send_onchain", now, 39, -40_000, -40_420, 420, status = "pending",
            sentTo = listOf(to("bitcoin", "bcrt1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr", 40_000)),
            metadata = """{"offboard_txid":"$signedTxid"}"""),
        ark(104, "bark.offboard:offboard", now, 51, -30_150, -30_150, 150, status = "pending",
            sentTo = listOf(to("bitcoin", "bcrt1qd4vf2m5yc8wz0jw7k3l9n8s6h2t5q3x7r0p9a", 30_000)), outputs = 0),
        ark(105, "bark.exit:start", now, 60 * 13 + 41, -20_000, status = "pending",
            sentTo = listOf(to("bitcoin", "bcrt1qexitaddress0000000000000000000000", 20_000)), outputs = 0),
        ark(201, "bark.lightning_send:send", now, 63, -4_200, 0, 12, status = "failed", sentTo = listOf(to("invoice", invoice("Coffee Collective", 11), 4_200)),
            outputs = 0),
        ark(202, "bark.lightning_send:send", now, 106, -8_900, 0, status = "failed", sentTo = listOf(to("invoice", invoice("Nakamoto Books", 12), 8_900)),
            outputs = 2),
    )

    /** [state]: everyday, busy, empty, loading, error. */
    fun records(state: String, now: Date = Date()): List<HistoryTransaction> = HistoryRules.unified(when (state) {
        "everyday" -> settled(now)
        "busy" -> pending(now) + settled(now)
        else -> emptyList()
    })

    fun ui(state: String, hidden: Boolean = false, now: Date = Date(), newRows: Set<String> = emptySet()) = ActivityUi(
        records = records(state, now), loading = state == "loading", error = if (state == "error") "Your wallet isn't ready. Please try again." else null,
        refreshing = false, unit = BitcoinAmountUnit.bip177, locale = Locale.US, hidden = hidden, ownDomain = "noahwallet.io", variant = "regtest",
        currency = "USD", price = 112_000.0, newRows = newRows, tip = tip, confirmedAt = mapOf(signedTxid to tip), now = now,
    ).let { ui -> ui.copy(records = ui.records.map { if (it.source == "ark" || it.hasConfirmation == true) it.copy(btcPrice = 110_500.0) else it }) }
}

/**
 * Activity as the app hosts it: inside the safe-drawing insets, the monochrome navigation (bar or rail) with
 * Activity owning the whole window, and [ActivityContent] in the onboarding frame that resolves the pose.
 */
@Composable
fun ActivityPreviewHost(ui: ActivityUi, state: ActivityState = rememberActivityState(), actions: ActivityActions = ActivityActions(),
    keepBar: Boolean = false) {
    val chrome = remember { WindowChrome() }
    val insets = WindowInsets.safeDrawing
    var barHidden by remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalWindowChrome provides chrome) {
        Surface {
            Box(Modifier.fillMaxSize().drawBehind {
                chrome.background?.let { drawRect(it) }
                chrome.bottom?.let { val inset = insets.getBottom(this).toFloat(); drawRect(it, Offset(0f, size.height - inset), Size(size.width, inset)) }
            }.windowInsetsPadding(insets)) {
                val palette = com.noahwallet.ui.onboarding.noahPalette()
                SideEffect { chrome.background = palette.background }
                AdaptiveNavigation(Destination.History, {}, fullWindow = true, showBar = !barHidden) { padding ->
                    Box(Modifier.padding(padding)) {
                        com.noahwallet.ui.onboarding.OnboardingFrame(null, splitAtWindowCentre = true) {
                            ActivityContent(ui,
                                ActivityActions(refresh = actions.refresh, retry = actions.retry, receive = actions.receive, repeat = actions.repeat,
                                    share = actions.share, export = actions.export, explorer = actions.explorer, recurring = actions.recurring,
                                    exits = actions.exits,
                                    coin = actions.coin, barHidden = { barHidden = it && !keepBar; actions.barHidden(it) }, seen = actions.seen,
                                    shown = actions.shown), state)
                        }
                    }
                }
            }
        }
    }
}
