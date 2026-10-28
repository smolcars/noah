package com.noahwallet.widgets

import com.noahwallet.fixtures.SharedFixtures
import com.noahwallet.history.HistoryTransaction
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.OffsetDateTime
import java.util.TimeZone
import kotlin.random.Random

class WidgetWaveTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun instant(value: String) = OffsetDateTime.parse(value).toInstant().toEpochMilli()

    /** apps/fixtures/widgets.json: daily balances, the change, calm water and the swell smoothing, shared with iOS. */
    @Test fun sharedFixture() {
        val cases = SharedFixtures.load("widgets")
        assertEquals(13, cases.size)
        for (case in cases) {
            val input = case.input.jsonObject
            val expected = case.expected.jsonObject
            when (input.getValue("kind").jsonPrimitive.content) {
                "dailyBalances" -> {
                    val changes = input.getValue("changes").jsonArray.map {
                        WidgetWave.Change(instant(it.jsonObject.getValue("at").jsonPrimitive.content), it.jsonObject.getValue("delta").jsonPrimitive.long)
                    }
                    val values = WidgetWave.dailyBalances(input.getValue("currentBalance").jsonPrimitive.long, changes,
                        instant(input.getValue("now").jsonPrimitive.content), TimeZone.getTimeZone(input.getValue("timeZone").jsonPrimitive.content))
                    assertEquals(case.name, expected.getValue("values").jsonArray.map { it.jsonPrimitive.long }, values)
                    assertEquals(case.name, expected.getValue("change").jsonPrimitive.long, WidgetWave.change(values))
                    assertEquals(case.name, expected.getValue("calm").jsonPrimitive.boolean, WidgetWave.calm(values))
                }
                "swell" -> {
                    val smoothed = WidgetWave.swell(input.getValue("series").jsonArray.map { it.jsonPrimitive.double })
                    val values = expected.getValue("values").jsonArray.map { it.jsonPrimitive.double }
                    assertEquals(case.name, values.size, smoothed.size)
                    values.zip(smoothed).forEach { (want, got) -> assertEquals(case.name, want, got, 1e-6) }
                }
                else -> fail("Unknown fixture kind in ${case.name}")
            }
        }
    }

    @Test fun monotoneCurveNeverOvershootsItsPoints() {
        val random = Random(7)
        repeat(200) {
            val ys = List(31) { random.nextDouble(0.0, 100.0).let { v -> if (random.nextInt(4) == 0) 50.0 else v } }
            val xs = List(31) { it * 4.0 }
            var x0 = xs[0]; var y0 = ys[0]
            for (s in WidgetWave.monotone(xs, ys)) {
                val low = minOf(y0, s.y) - 1e-9; val high = maxOf(y0, s.y) + 1e-9
                for (step in 0..20) {
                    val t = step / 20.0; val u = 1 - t
                    val y = u * u * u * y0 + 3 * u * u * t * s.c1y + 3 * u * t * t * s.c2y + t * t * t * s.y
                    val x = u * u * u * x0 + 3 * u * u * t * s.c1x + 3 * u * t * t * s.c2x + t * t * t * s.x
                    assertTrue("y $y outside [$low, $high]", y in low..high)
                    assertTrue(x in x0 - 1e-9..s.x + 1e-9)
                }
                x0 = s.x; y0 = s.y
            }
        }
    }

    @Test fun aLaterDayMovesTheWindowOn() {
        val values = (0L..30L).map { it * 10 }
        val day = WidgetWave.day(instant("2026-10-27T12:00:00Z"), TimeZone.getTimeZone("UTC"))
        assertEquals(values, WidgetWave.shifted(values, day, day))
        assertEquals((2L..30L).map { it * 10 } + listOf(300L, 300L), WidgetWave.shifted(values, day, day + 2))
        assertEquals(List(31) { 300L }, WidgetWave.shifted(values, day, day + 40))
        // A clock set back never shifts the other way.
        assertEquals(values, WidgetWave.shifted(values, day, day - 1))
    }

    /** Local days without java.time (Android 7 has none): whole days across daylight saving, and the zone's own date. */
    @Test fun localDaysFollowTheZone() {
        val newYork = TimeZone.getTimeZone("America/New_York")
        // 23:30 on Oct 31 and 01:30 on Nov 1 (before and after the clocks go back) are one day apart; Nov 2 is two.
        val before = WidgetWave.day(instant("2026-10-31T23:30:00-04:00"), newYork)
        assertEquals(before + 1, WidgetWave.day(instant("2026-11-01T01:30:00-05:00"), newYork))
        assertEquals(before + 2, WidgetWave.day(instant("2026-11-02T00:00:00-05:00"), newYork))
        // The same instant is a day later in Tokyo than in UTC.
        val at = instant("2026-10-26T16:30:00Z")
        assertEquals(WidgetWave.day(at, TimeZone.getTimeZone("UTC")) + 1, WidgetWave.day(at, TimeZone.getTimeZone("Asia/Tokyo")))
        assertEquals(0L, WidgetWave.day(0, TimeZone.getTimeZone("UTC")))
        assertEquals(-1L, WidgetWave.day(-1, TimeZone.getTimeZone("UTC")))
    }

    /**
     * The movement → balance change mapping, against the history fixtures' own rows: a successful movement moves the
     * balance by its effective delta; failed and canceled movements don't, nor pending ones that still hold their sats
     * (receives and boards count by their own state, [com.noahwallet.services.push.WidgetPendingPaymentsTest]); a
     * transaction does, confirmed or not, at its date (an unconfirmed one when first seen).
     */
    @Test fun historyFixtureRowsMapToSettledChanges() {
        val now = instant("2026-10-27T15:00:00Z"); val confirmed = instant("2026-10-25T09:00:00Z")
        var movements = 0; var transactions = 0
        for (case in SharedFixtures.load("transaction-history")) {
            val operation = case.input.jsonObject.getValue("operation").jsonPrimitive.content
            val mapped = operation == "movement" || operation == "onchain"
            if (!mapped || !case.expected.jsonObject.getValue("included").jsonPrimitive.boolean) continue
            val expected = case.expected.jsonObject
            val row = json.decodeFromJsonElement<HistoryTransaction>(expected.getValue("transaction"))
            val dates = WidgetWave.chainDates(listOf(row), emptyMap(), row.txid?.let { mapOf(it to confirmed) }.orEmpty(), 1_000, now)
            val changes = WidgetWave.changes(listOf(row), dates.mapValues { it.value.at })
            if (operation == "movement") {
                movements++
                val sats = row.effectiveBalanceSat ?: 0L
                if (row.movementStatus == "successful" && sats != 0L) assertEquals(case.name, listOf(WidgetWave.Change(row.timestamp!!.time, sats)), changes)
                else assertEquals(case.name, emptyList<WidgetWave.Change>(), changes)
            } else {
                transactions++
                val at = if (row.hasConfirmation == true) confirmed else now
                assertEquals(case.name, listOf(WidgetWave.Change(at, row.balanceChangeSat!!)), changes)
            }
        }
        assertTrue(movements > 20); assertTrue(transactions > 0)
    }

    private val now = instant("2026-10-27T15:00:00Z")
    private val board = HistoryTransaction("movement-7", "Onchain", 50_000, "2026-10-20T12:00:00.000Z", "incoming", source = "ark",
        txid = "board-txid", movementKind = "onboard", movementStatus = "successful", effectiveBalanceSat = 50_000,
        metadataJson = """{"chain_anchor":"board-txid:0"}""")
    private val chain = HistoryTransaction("onchain-wallet-board-txid", "Onchain", 50_500, "1970-01-01T00:00:00.000Z", "outgoing",
        source = "onchain-wallet", txid = "board-txid", balanceChangeSat = -50_500, hasConfirmation = true, confirmationHeight = 900u)

    /** A board's two halves move the balance by the fee alone, on the movement's day. */
    @Test fun aBoardMovesTheBalanceByItsFee() {
        val deposit = HistoryTransaction("onchain-wallet-deposit", "Onchain", 60_000, "1970-01-01T00:00:00.000Z", "incoming",
            source = "onchain-wallet", txid = "deposit", balanceChangeSat = 60_000, hasConfirmation = true, confirmationHeight = 880u)
        val pending = deposit.copy(id = "onchain-wallet-pending", txid = "pending", hasConfirmation = false, confirmationHeight = null)
        val dates = mapOf("deposit" to instant("2026-10-26T08:00:00Z"))
        val changes = WidgetWave.changes(listOf(board, chain, deposit, pending), dates)
        assertEquals(setOf(WidgetWave.Change(instant("2026-10-20T12:00:00Z"), 50_000), WidgetWave.Change(instant("2026-10-20T12:00:00Z"), -50_500),
            WidgetWave.Change(instant("2026-10-26T08:00:00Z"), 60_000)), changes.toSet())
        // A settled offboard to this wallet moves it by the fee, its credit confirmed or not.
        val offboard = board.copy(id = "movement-8", txid = "offboard-txid", movementKind = "offboard", effectiveBalanceSat = -30_000,
            metadataJson = """{"offboard_txid":"offboard-txid"}""")
        val credit = chain.copy(id = "onchain-wallet-offboard-txid", txid = "offboard-txid", balanceChangeSat = 29_700, hasConfirmation = false,
            confirmationHeight = null)
        assertEquals(-300L, WidgetWave.changes(listOf(offboard, credit), emptyMap()).sumOf { it.sats })
        val confirmed = credit.copy(hasConfirmation = true, confirmationHeight = 990u)
        assertEquals(-300L, WidgetWave.changes(listOf(offboard, confirmed), emptyMap()).sumOf { it.sats })
        // A pending board: its funding transaction as the on-chain total counts it, the board once bark reports its VTXO
        // in pending_board (pendingBoardVtxos), confirmed funding or not; another board's VTXO doesn't count it.
        val boarding = board.copy(movementStatus = "pending", outputVtxos = listOf("board-vtxo"))
        val funding = WidgetWave.Change(instant("2026-10-20T12:00:00Z"), -50_500)
        for (transaction in listOf(chain, chain.copy(hasConfirmation = false, confirmationHeight = null))) {
            assertEquals(listOf(funding), WidgetWave.changes(listOf(boarding, transaction), emptyMap()))
            val another = WidgetWave.Pending(boardVtxos = setOf("another-board"))
            assertEquals(listOf(funding), WidgetWave.changes(listOf(boarding, transaction), emptyMap(), another))
            assertEquals(setOf(WidgetWave.Change(instant("2026-10-20T12:00:00Z"), 50_000), funding),
                WidgetWave.changes(listOf(boarding, transaction), emptyMap(), WidgetWave.Pending(boardVtxos = setOf("board-vtxo"))).toSet())
        }
    }

    /**
     * A round offboard names its transaction only as `funding_txid`: an old self-offboard whose debit is outside the
     * window must not read as income because its credit was confirmed inside it.
     */
    @Test fun aRoundOffboardOwnsItsFundingTxidAcrossTheWindowEdge() {
        val old = HistoryTransaction("movement-9", "Onchain", 30_000, "2026-09-20T12:00:00.000Z", "outgoing", source = "ark",
            txid = "vtxo-in", movementKind = "offboard", movementStatus = "successful", effectiveBalanceSat = -30_000,
            metadataJson = """{"funding_txid":"round-txid"}""")
        val credit = chain.copy(id = "onchain-wallet-round-txid", txid = "round-txid", balanceChangeSat = 29_700, confirmationHeight = 995u)
        // The credit's estimated date falls inside the window; owned, both halves stay on the offboard's day, outside it.
        val dates = WidgetWave.chainDates(listOf(old, credit), emptyMap(), emptyMap(), 1_000, now).mapValues { it.value.at }
        assertTrue(dates.getValue("round-txid") > instant("2026-10-27T00:00:00Z"))
        val changes = WidgetWave.changes(listOf(old, credit), dates)
        assertEquals(-300L, changes.sumOf { it.sats }); assertTrue(changes.all { it.at == instant("2026-09-20T12:00:00Z") })
        assertTrue(WidgetWave.calm(WidgetWave.dailyBalances(100_000, changes, now, TimeZone.getTimeZone("UTC"))))
        // A recent one moves the wave by its fee, inside the window.
        val recent = old.copy(date = "2026-10-26T12:00:00.000Z")
        val wave = WidgetWave.dailyBalances(100_000, WidgetWave.changes(listOf(recent, credit), dates), now, TimeZone.getTimeZone("UTC"))
        assertEquals(300L, wave.first() - wave.last())
        // Unowned, the credit alone would have read as +29,700 of income.
        assertEquals(29_700L, WidgetWave.change(WidgetWave.dailyBalances(100_000, WidgetWave.changes(listOf(credit), dates), now, TimeZone.getTimeZone("UTC"))))
    }

    /**
     * A fee-paying renewal can share the round's funding transaction with an offboard (Sol review: a pending renewal
     * took the offboard's credit, −50,300 instead of −300). The offboard owns it, in either order; an explicit
     * reference beats both.
     */
    @Test fun anOffboardOwnsItsCreditOverARenewalSharingTheRound() {
        val offboard = HistoryTransaction("movement-10", "Onchain", 50_000, "2026-10-25T12:00:00.000Z", "outgoing", source = "ark",
            txid = "vtxo-a", movementKind = "offboard", movementStatus = "successful", effectiveBalanceSat = -50_000,
            metadataJson = """{"funding_txid":"round-txid"}""")
        val renewal = HistoryTransaction("movement-11", "Arkoor", 120, "2026-10-26T12:00:00.000Z", "outgoing", source = "ark",
            txid = "vtxo-b", movementKind = "refresh", movementStatus = "pending", effectiveBalanceSat = -120,
            metadataJson = """{"funding_txid":"round-txid"}""")
        val credit = chain.copy(id = "onchain-wallet-round-txid", txid = "round-txid", balanceChangeSat = 49_700, confirmationHeight = 990u)
        val statuses = listOf("pending", "successful")
        for ((renewalStatus, offboardStatus) in statuses.flatMap { renewal -> statuses.map { renewal to it } }) {
            val rows = listOf(offboard.copy(movementStatus = offboardStatus), renewal.copy(movementStatus = renewalStatus))
            for (order in listOf(rows, rows.reversed())) {
                val changes = WidgetWave.changes(order + credit, emptyMap())
                // The credit is in the on-chain total either way; the round's Ark side holds its sats until it settles.
                val expected = 49_700L + (if (offboardStatus == "successful") -50_000L else 0L) + (if (renewalStatus == "successful") -120L else 0L)
                assertEquals("offboard $offboardStatus, renewal $renewalStatus, ${order.first().movementKind} first", expected, changes.sumOf { it.sats })
                // The credit lands on the offboard's day, never the renewal's.
                assertTrue(changes.filter { it.sats == 49_700L }.all { it.at == instant("2026-10-25T12:00:00Z") })
                assertEquals("offboard owns it", "movement-10", WidgetWave.owners(order)["round-txid"]?.id)
            }
        }
        // A movement naming the transaction outright beats a round's funding transaction.
        val named = renewal.copy(id = "movement-12", movementKind = "send-onchain", metadataJson = """{"offboard_txid":"round-txid"}""")
        assertEquals("movement-12", WidgetWave.owners(listOf(offboard, named))["round-txid"]?.id)
        assertEquals("movement-12", WidgetWave.owners(listOf(named, offboard))["round-txid"]?.id)
    }

    /** A confirmation's date is estimated once and kept: a tip that stands still never makes it drift towards today. */
    @Test fun confirmationDatesAreKeptAndExactTimesWin() {
        val deposit = chain.copy(id = "onchain-wallet-deposit", txid = "deposit", balanceChangeSat = 60_000, confirmationHeight = 994u)
        val first = WidgetWave.chainDates(listOf(deposit), emptyMap(), emptyMap(), 1_000, now)
        assertEquals(WidgetWave.ChainDate(now - 6 * 600_000L), first["deposit"])
        // Three days later, same tip (regtest, or a phone that hasn't synced): the stored date stands.
        val later = now + 3 * 86_400_000L
        assertEquals(first, WidgetWave.chainDates(listOf(deposit), first, emptyMap(), 1_000, later))
        // Activity's block time replaces the estimate; an exact date then stands too.
        val exact = WidgetWave.chainDates(listOf(deposit), first, mapOf("deposit" to instant("2026-10-27T13:30:00Z")), 1_000, later)
        assertEquals(WidgetWave.ChainDate(instant("2026-10-27T13:30:00Z"), exact = true), exact["deposit"])
        assertEquals(exact, WidgetWave.chainDates(listOf(deposit), exact, emptyMap(), 2_000, later))
        // An unconfirmed one is dated when first seen, exactly: confirming, even with Activity's block time, never moves it.
        val unconfirmed = deposit.copy(hasConfirmation = false, confirmationHeight = null)
        val seen = WidgetWave.chainDates(listOf(unconfirmed), emptyMap(), emptyMap(), 1_000, now)
        assertEquals(WidgetWave.ChainDate(now, exact = true), seen["deposit"])
        assertEquals(seen, WidgetWave.chainDates(listOf(unconfirmed), seen, emptyMap(), 1_000, later))
        assertEquals(seen, WidgetWave.chainDates(listOf(deposit), seen, mapOf("deposit" to instant("2026-10-30T10:00:00Z")), 1_001, later))
        // Transactions no longer listed are dropped; no tip, no estimate.
        assertEquals(emptyMap<String, WidgetWave.ChainDate>(), WidgetWave.chainDates(emptyList(), first, emptyMap(), 1_000, now))
        assertEquals(emptyMap<String, WidgetWave.ChainDate>(), WidgetWave.chainDates(listOf(deposit), emptyMap(), emptyMap(), null, now))
    }
}
