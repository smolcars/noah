package com.noahwallet.ui.activity

import com.noahwallet.fixtures.SharedFixtures
import com.noahwallet.formatting.BitcoinAmountUnit
import com.noahwallet.history.*
import com.noahwallet.ui.home.HomeDays
import com.noahwallet.ui.home.rowAmount
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Activity's list, search, filter, export and receipt rules (docs/native-rewrite/design/activity.md, Testing). */
class ActivityRulesTest {
    private val lenient = Json { ignoreUnknownKeys = true }
    private fun at(zone: TimeZone, day: Int, hour: Int, minute: Int = 0) =
        Calendar.getInstance(zone).apply { clear(); set(2026, Calendar.OCTOBER, day, hour, minute) }.time
    @Suppress("LongParameterList") // Fixture builder: each parameter is a named, defaulted field of the model it builds.
    private fun record(id: String, date: Date?, status: String = "successful", type: String = "Arkoor", direction: String = "outgoing",
        amount: Long = 2_500, destination: String = "", source: String = "ark", confirmed: Boolean? = null) =
        HistoryTransaction(id, type, amount, if (date == null) HistoryRules.isoDate(Date(0)) else HistoryRules.isoDate(date), direction,
            dateLabel = if (date == null) "Unconfirmed" else null, sortTimestamp = date?.time?.toDouble(), source = source, txid = "tx-$id",
            destination = destination, movementId = id.hashCode().toUInt(), movementStatus = if (source == "ark") status else null,
            effectiveBalanceSat = if (status == "failed") 0 else -amount, offchainFeeSat = 0, hasConfirmation = confirmed,
            sentTo = listOf(HistoryTransaction.Destination(destination, amount, "lightning-address")).filter { destination.isNotEmpty() })
    private fun rows(vararg records: HistoryTransaction) = ActivityRules.rows(records.toList(), "noahwallet.io")
    private fun <T> zone(id: String, body: () -> T): T {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(id))
        try { return body() } finally { TimeZone.setDefault(previous) }
    }

    @Test fun pendingComesFirstThenDaysAcrossMidnight() = zone("Europe/Stockholm") {
        val tz = TimeZone.getDefault()
        val now = at(tz, 23, 0, 5)
        val groups = ActivityRules.groups(rows(
            record("deposit", null, source = "onchain-wallet", confirmed = false, direction = "incoming"),
            record("sending", at(tz, 22, 23, 50), status = "pending"),
            record("just-after", at(tz, 23, 0, 1)),
            record("just-before", at(tz, 22, 23, 59)),
            record("older", at(tz, 9, 11, 30)),
        ), now)
        assertEquals(listOf(ActivityRules.Group.Pending, ActivityRules.Group.Day(HomeDays.Day.Today), ActivityRules.Group.Day(HomeDays.Day.Yesterday)),
            groups.take(3).map { it.first })
        assertEquals(listOf("deposit", "sending"), groups[0].second.map { it.id })
        assertEquals(listOf("just-after"), groups[1].second.map { it.id })
        assertEquals(listOf("just-before"), groups[2].second.map { it.id })
        assertTrue(groups[3].first is ActivityRules.Group.Day && (groups[3].first as ActivityRules.Group.Day).day is HomeDays.Day.On)
    }

    @Test fun daysFollowTheTimeZoneTheReaderIsIn() {
        val instant = at(TimeZone.getTimeZone("UTC"), 22, 23, 30)
        val now = at(TimeZone.getTimeZone("UTC"), 23, 9, 0)
        val payment = rows(record("p", instant))
        // 23:30 UTC on the 22nd is the 22nd in New York but already the 23rd in Tokyo.
        assertEquals(HomeDays.Day.Yesterday, zone("America/New_York") { (ActivityRules.groups(payment, now)[0].first as ActivityRules.Group.Day).day })
        assertEquals(HomeDays.Day.Today, zone("Asia/Tokyo") { (ActivityRules.groups(payment, now)[0].first as ActivityRules.Group.Day).day })
    }

    @Test fun searchMatchesNamesNotesKindsAmountsAndIds() {
        val bob = record("movement-4", Date(), destination = "bob@getalby.com", amount = 5_000, type = "Lnurl")
            .copy(repeatPayment = RepeatPayment("bob@getalby.com", "Pizza night", "SATS", "5000", 5_000))
        val row = rows(bob).single()
        val hay = ActivityRules.haystack(row, listOf("Lightning"))
        for (query in listOf("BOB", "getalby", "pizza", "lightning", "5000", "5,000", "₿5,000", "5 000", "movement-4", "tx-movement-4"))
            assertTrue(query, ActivityRules.matches(hay, bob.amount, query))
        assertFalse(ActivityRules.matches(hay, bob.amount, "maya"))
        assertFalse("digits that aren't in the amount", ActivityRules.matches(hay, bob.amount, "6,000"))
        assertTrue("blank shows everything", ActivityRules.matches(hay, bob.amount, "  "))
        // Payment hashes, from the invoice or bark's record.
        val hashed = rows(bob.copy(metadataJson = """{"payment_hash":"ABCDEF0123"}""")).single()
        assertTrue(ActivityRules.matches(ActivityRules.haystack(hashed), 5_000, "abcdef0123"))
        assertEquals(emptyList<String>(), ActivityRules.visible(listOf(row), HistoryFilter.All, "nothing like it", emptyMap()).map { it.id })
    }

    @Test fun theFilterNarrowsTheListAndNamesItsEmptyState() {
        val list = rows(record("ark", Date()), record("ln", Date(), type = "Bolt11"), record("chain", Date(), type = "Onchain"))
        assertEquals(listOf("ln"), ActivityRules.visible(list, HistoryFilter.Lightning, "", emptyMap()).map { it.id })
        assertEquals(listOf("ark"), ActivityRules.visible(list, HistoryFilter.Ark, "", emptyMap()).map { it.id })
        assertEquals(emptyList<String>(), ActivityRules.visible(list.take(1), HistoryFilter.Onchain, "", emptyMap()))
        assertEquals("No on-chain payments", ActivityRules.noneMessage(HistoryFilter.Onchain))
        assertEquals("No Lightning payments", ActivityRules.noneMessage(HistoryFilter.Lightning))
        assertEquals("On-chain", ActivityRules.filterName(HistoryFilter.Onchain))
    }

    @Test fun csvExportsTheFilteredRowsWithoutTheNewlyShownFailures() {
        val records = listOf(record("sent", Date(), type = "Bolt11"), record("failed", Date(), status = "failed", type = "Bolt11"),
            record("ark", Date()), record("board", Date(), status = "failed", type = "Onchain").copy(movementKind = "onboard"))
        assertEquals(listOf("sent", "ark", "board"), ActivityRules.exported(records, HistoryFilter.All).map { it.id })
        assertEquals(listOf("sent"), ActivityRules.exported(records, HistoryFilter.Lightning).map { it.id })
        // Today's columns, unchanged.
        val csv = HistoryCSV.content(ActivityRules.exported(records, HistoryFilter.All), "USD")
        assertEquals("Payment ID,Date,Type,Status,Direction,Amount (sats),BTC Price (USD),Transaction ID,Destination", csv.lines().first())
        assertEquals(4, csv.trim().lines().size)
    }

    @Test fun hiddenAmountsAreDotsEverywhereTheyShow() {
        val row = rows(record("a", Date(), amount = 2_500)).single().home
        assertEquals("••••", rowAmount(row, hidden = true, BitcoinAmountUnit.sats, Locale.US))
        assertEquals("−", row.sign)
    }

    @Test fun theNewDotIsHomesInMemorySet() {
        val incoming = record("in", Date(), direction = "incoming")
        val outgoing = record("out", Date())
        assertEquals(emptySet<String>(), ActivityRules.newRows(listOf(incoming), null))
        assertEquals(setOf("in"), ActivityRules.newRows(listOf(incoming, outgoing), emptySet()))
        val seen = ActivityRules.seen(emptySet(), "in")
        assertEquals(emptySet<String>(), ActivityRules.newRows(listOf(incoming), seen))
        assertNull("Home hasn't loaded yet: nothing to share", ActivityRules.seen(null, "in"))
    }

    private val words: (String, Array<out Any>) -> String = { source, arguments ->
        arguments.fold(source) { text, value -> text.replaceFirst(Regex("\\{\\w+\\}"), value.toString()) }
    }

    @Test fun shareSendsAPlainReceiptWithoutSecrets() {
        val proof = "ab".repeat(32)
        val lightning = record("ln", Date(), type = "Lnurl", destination = "bob@getalby.com").copy(preimage = proof, subsystemName = "bark.lightning_send")
        val text = ActivityRules.receipt(rows(lightning).single(), "−₿2,500", "Today at 9:12", null, words)
        assertEquals("Sent −₿2,500\nDate: Today at 9:12\nTo: bob@getalby.com\nVia: Lightning\nProof of payment: $proof", text)
        // A receive never shares its preimage, and nothing from Details goes out.
        val received = record("rx", Date(), type = "Bolt11", direction = "incoming").copy(preimage = proof, subsystemName = "bark.lightning_receive",
            txHex = "0200", metadataJson = """{"payment_preimage":"$proof"}""")
        val receipt = ActivityRules.receipt(rows(received).single(), "+₿2,500", "Today", null, words)
        assertFalse(receipt.contains(proof)); assertFalse(receipt.contains("0200"))
        assertEquals("Received +₿2,500\nDate: Today\nVia: Lightning", receipt)
        // On-chain: the transaction ID.
        val txid = "cd".repeat(32)
        val chain = record("c", Date(), type = "Onchain", source = "onchain-wallet", confirmed = true).copy(txid = txid, balanceChangeSat = -2_500)
        assertTrue(ActivityRules.receipt(rows(chain).single(), "−₿2,500", "Today", ActivityRules.chainTxid(chain), words)
            .endsWith("Via: On-chain\nTransaction ID: $txid"))
        // Confirmed before its block time is known: no date line rather than a wrong one.
        assertFalse(ActivityRules.receipt(rows(chain).single(), "−₿2,500", null, null, words).contains("Date"))
        // A renewal says how many coins it renewed.
        val renewal = record("r", Date()).copy(movementKind = "refresh", inputVtxos = listOf("a:0", "b:0", "c:0"))
        assertTrue(ActivityRules.receipt(rows(renewal).single(), "−₿45", "Today", null, words).startsWith("Renewed 3 coins −₿45"))
    }

    @Test fun onlyASettledRenewalCountsItsCoins() {
        // Only "Renewed {n} coins" has a plural resource; a renewal's other lines are plain strings.
        val renewal = record("r", Date()).copy(movementKind = "refresh", inputVtxos = listOf("a:0", "b:0", "c:0"))
        val lines = listOf("successful", "pending", "failed", "canceled").map { ActivityRules.status(rows(renewal.copy(movementStatus = it)).single()).first }
        assertEquals(listOf(ActivityRules.renewed, "Sending", "Failed", "Canceled"), lines)
        assertTrue(ActivityRules.receipt(rows(renewal.copy(movementStatus = "pending")).single(), "−₿45", "Today", null, words).startsWith("Sending −₿45\n"))
    }

    @Test fun settledLightningFeesComeFromTheBalanceTheyTook() {
        val fixed =
            "lnbc15u1p3xnhl2pp5jptserfk3zk4qy42tlucycrfwxhydvlemu9pqr93tuzlv9cc7g3sdqsvfhkcap3xyhx7un8cqzpgxqzjcsp5f8c52y2stc300gl6s4xswtjpc37hrnnr3c9wvt" +
                "gjfuvqmpm35evq9qyyssqy4lgd8tj637qcjp05rdpxxykjenthxftej7a2zzmwrmrl70fyj9hvj0rewhzj7jfyuwkwcg9g2jpwtk3wkjtwnkdks84hsnu8xps5vsq4gj5hs"
        fun fees(record: HistoryTransaction) = rows(record).single().let { ActivityRules.fees(record, it.home) to ActivityRules.total(record, it.home) }
        // A retried invoice: bark kept the first attempt's fee field (12), but the movement took 1,530 for 1,500.
        val retried = record("ln", Date(), type = "Bolt11", amount = 1_500).copy(subsystemName = "bark.lightning_send", offchainFeeSat = 12,
            effectiveBalanceSat = -1_530, sentTo = listOf(HistoryTransaction.Destination(fixed, 1_500, "invoice")))
        assertEquals(listOf(ActivityRules.FeeLine("Fee", ActivityRules.FeeValue.Sats(30))) to 1_530L, fees(retried))
        // An amountless invoice proves no fee (Send's already-paid rule): no Fee row, no Total, never "Free".
        val amountless = retried.copy(sentTo = listOf(HistoryTransaction.Destination(ActivityFixtures.invoice("Tea", 1), 1_500, "invoice")))
        assertEquals(emptyList<ActivityRules.FeeLine>() to null, fees(amountless))
        // Still in flight: the quoted fee, as the most it may take.
        assertEquals(listOf(ActivityRules.FeeLine("Fee", ActivityRules.FeeValue.UpTo(12))) to 1_512L, fees(retried.copy(movementStatus = "pending")))
        // Ark sends prove their fee field; a proven zero is "Free".
        assertEquals(listOf(ActivityRules.FeeLine("Fee", ActivityRules.FeeValue.Free)) to null, fees(record("ark", Date()).copy(subsystemName = "bark.arkoor")))
        // An on-chain wallet send without a recorded fee claims nothing.
        assertEquals(emptyList<ActivityRules.FeeLine>() to null, fees(record("c", Date(), type = "Onchain", source = "onchain-wallet", confirmed = true)))
    }

    @Test fun anAmountlessRetryShowsOnlyWhatLeftTheWallet() {
        // Failed at 1,000, then paid at 2,000: bark's reused movement keeps the first attempt's destination amount.
        val proof = "ab".repeat(32)
        val retried = record("ln", Date(), type = "Bolt11", amount = 1_000).copy(subsystemName = "bark.lightning_send", effectiveBalanceSat = -2_010,
            sentTo = listOf(HistoryTransaction.Destination(ActivityFixtures.invoice("Tea", 1), 1_000, "invoice")), preimage = proof)
        val row = rows(retried).single()
        assertEquals("Sent", ActivityRules.status(row).first)
        assertEquals(2_010L, row.home.amount)
        assertTrue(ActivityRules.receipt(row, "−2,010 sats", "Today", null, words).startsWith("Sent −2,010 sats\n"))
        // Nothing proves a debit: no amount, in the row or the receipt.
        val unproven = rows(retried.copy(effectiveBalanceSat = 0)).single()
        assertNull(unproven.home.amount)
        assertEquals("", rowAmount(unproven.home, false, BitcoinAmountUnit.sats, Locale.US))
        assertEquals("Sent\nDate: Today\nVia: Lightning\nProof of payment: $proof", ActivityRules.receipt(unproven, null, "Today", null, words))
        // An invoice that sets its amount, and anything still in flight, keep the recorded amount.
        val fixed =
            "lnbc15u1p3xnhl2pp5jptserfk3zk4qy42tlucycrfwxhydvlemu9pqr93tuzlv9cc7g3sdqsvfhkcap3xyhx7un8cqzpgxqzjcsp5f8c52y2stc300gl6s4xswtjpc37hrnnr3c9wvt" +
                "gjfuvqmpm35evq9qyyssqy4lgd8tj637qcjp05rdpxxykjenthxftej7a2zzmwrmrl70fyj9hvj0rewhzj7jfyuwkwcg9g2jpwtk3wkjtwnkdks84hsnu8xps5vsq4gj5hs"
        assertEquals(1_000L, rows(retried.copy(sentTo = listOf(HistoryTransaction.Destination(fixed, 1_000, "invoice")))).single().home.amount)
        assertEquals(1_000L, rows(retried.copy(movementStatus = "pending")).single().home.amount)
    }

    @Test fun amountSearchMatchesTheAmountShown() {
        val retried = record("ln", Date(), type = "Bolt11", amount = 1_000).copy(subsystemName = "bark.lightning_send", effectiveBalanceSat = -2_010,
            sentTo = listOf(HistoryTransaction.Destination(ActivityFixtures.invoice("Tea", 1), 1_000, "invoice")))
        fun found(record: HistoryTransaction, query: String) = ActivityRules.visible(rows(record), HistoryFilter.All, query, emptyMap()).isNotEmpty()
        assertTrue(found(retried, "2,010")); assertTrue(found(retried, "₿2010"))
        assertFalse("bark's recorded 1,000 isn't what the row shows", found(retried, "1,000"))
        // No amount shown, none matched.
        assertFalse(found(retried.copy(effectiveBalanceSat = 0), "1,000"))
    }

    @Test fun aLightningAddressPaidByInvoiceKeepsItsRecipient() {
        val invoice = ActivityFixtures.invoice("Pizza", 4)
        // bark records the invoice Noah fetched; Noah's repeat metadata records the address.
        val viaAddress = record("a", Date(), type = "Lnurl").copy(subsystemName = "bark.lightning_send",
            sentTo = listOf(HistoryTransaction.Destination(invoice, 2_500, "invoice")), destination = "bob@getalby.com",
            repeatPayment = RepeatPayment("bob@getalby.com", "", "SATS", "2500", 2_500))
        assertEquals("bob@getalby.com", ActivityRules.recipient(viaAddress))
        assertEquals("bob@getalby.com", rows(viaAddress).single().home.title)
        // A bare invoice names no one.
        assertNull(ActivityRules.recipient(viaAddress.copy(repeatPayment = null, destination = invoice, type = "Bolt11")))
    }

    @Test fun receivesShowTheFeeTheyPaid() {
        val received = record("rx", Date(), type = "Bolt11", direction = "incoming", amount = 5_000)
            .copy(subsystemName = "bark.lightning_receive", effectiveBalanceSat = 4_950, offchainFeeSat = 50)
        val home = rows(received).single().home
        assertEquals(listOf(ActivityRules.FeeLine("Fee", ActivityRules.FeeValue.Sats(50))), ActivityRules.fees(received, home))
        assertNull("no total on receives", ActivityRules.total(received, home))
        // Nothing paid: no Fee row ("Free" is for sends).
        val free = received.copy(offchainFeeSat = 0)
        assertEquals(emptyList<ActivityRules.FeeLine>(), ActivityRules.fees(free, rows(free).single().home))
    }

    @Test fun onlyRecordedOnChainTransactionsAreNamed() {
        val coin = "ef".repeat(32)
        // An offboard still waiting for its transaction: its txid falls back to a spent coin's ID, which names that
        // coin's parent transaction. No Transaction ID, explorer link or receipt line until bark records the offboard.
        val waiting = record("w", Date(), type = "Onchain").copy(movementKind = "send-onchain", txid = "$coin:1", metadataJson = "{}")
        assertNull(ActivityRules.chainTxid(waiting)); assertNull(ActivityRules.explorerURL(waiting, "regtest"))
        assertFalse(ActivityRules.receipt(rows(waiting).single(), "−₿2,500", "Today", ActivityRules.chainTxid(waiting), words).contains(coin))
        val signed = "ab".repeat(32)
        val offboard = waiting.copy(txid = signed, metadataJson = """{"offboard_txid":"$signed"}""")
        assertEquals(signed, ActivityRules.chainTxid(offboard))
        assertEquals("https://mempool.space/signet/tx/$signed", ActivityRules.explorerURL(offboard, "regtest"))
        // A board's anchor, without its vout.
        val board = record("b", Date(), type = "Onchain").copy(movementKind = "onboard", metadataJson = """{"chain_anchor":"$signed:0"}""")
        assertEquals(signed, ActivityRules.chainTxid(board))
        // A renewal's round funding transaction.
        val renewal = record("r", Date()).copy(movementKind = "refresh", metadataJson = """{"funding_txid":"$signed"}""")
        assertEquals(signed, ActivityRules.chainTxid(renewal))
        assertNull(ActivityRules.chainTxid(renewal.copy(movementKind = "arkoor-receive")))
        // The on-chain wallet's own transaction.
        assertEquals(signed, ActivityRules.chainTxid(record("c", Date(), type = "Onchain", source = "onchain-wallet").copy(txid = signed)))
        // Movement placeholders and Ark payments name none.
        assertNull(ActivityRules.chainTxid(record("p", Date(), type = "Onchain").copy(txid = "movement-104")))
        assertNull(ActivityRules.chainTxid(record("a", Date()).copy(txid = coin)))
    }

    @Test fun aFailedSendNeverReadsSentOrShowsItsPlannedFee() {
        fun send(status: String, effective: Long, fee: Long, outputs: List<String> = emptyList()) = HistoryTransaction(
            "movement-7", "Lnurl", 5_000, HistoryRules.isoDate(Date()), "outgoing", source = "ark", destination = "bob@getalby.com",
            movementId = 7u, movementStatus = status, subsystemName = "bark.lightning_send", subsystemKind = "send",
            intendedBalanceSat = -5_000, effectiveBalanceSat = effective, offchainFeeSat = fee,
            sentTo = listOf(HistoryTransaction.Destination("bob@getalby.com", 5_000, "lightning-address")), outputVtxos = outputs)
        fun summary(record: HistoryTransaction) = rows(record).single().let { ActivityRules.status(it) to ActivityRules.fees(record, it.home) }
        val none = listOf(ActivityRules.FeeLine("Fee", ActivityRules.FeeValue.None))
        // bark 0.8 keeps the planned fee on a send that never left.
        assertEquals(("Failed · nothing left your wallet" to ActivityRules.Tone.failed) to none, summary(send("failed", 0, 15)))
        assertEquals(("Refunded · back in your wallet" to ActivityRules.Tone.failed) to none, summary(send("failed", 0, 0, outputs = listOf("revoked:0"))))
        assertEquals(("Sent" to ActivityRules.Tone.plain) to listOf(ActivityRules.FeeLine("Fee", ActivityRules.FeeValue.Sats(15))),
            summary(send("successful", -5_015, 15)))
    }

    @Test fun aBoardShowsTheArkFeeItRecorded() {
        fun fees(record: HistoryTransaction) = ActivityRules.fees(record, rows(record).single().home)
        // The shared fixture's successful board without metadata: no network fee, a 500-sat Ark fee.
        val case = SharedFixtures.load("transaction-history").single { it.name == "bark.board:board successful" }
        val board = lenient.decodeFromJsonElement(HistoryTransaction.serializer(), case.expected.jsonObject.getValue("transaction"))
        assertEquals(listOf(ActivityRules.FeeLine("Fee", ActivityRules.FeeValue.Sats(500))), fees(board))
        // A board that recorded no fee shows none ("Free" is a claim for sends).
        assertEquals(emptyList<ActivityRules.FeeLine>(), fees(board.copy(offchainFeeSat = 0)))
    }
}
