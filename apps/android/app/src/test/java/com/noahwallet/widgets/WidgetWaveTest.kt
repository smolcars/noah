package com.noahwallet.widgets

import com.noahwallet.fixtures.SharedFixtures
import com.noahwallet.history.HistoryRules
import com.noahwallet.history.HistoryTransaction
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
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
                        instant(input.getValue("now").jsonPrimitive.content), ZoneId.of(input.getValue("timeZone").jsonPrimitive.content))
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
        val day = LocalDate.of(2026, 10, 27)
        assertEquals(values, WidgetWave.shifted(values, day, day))
        assertEquals((2L..30L).map { it * 10 } + listOf(300L, 300L), WidgetWave.shifted(values, day, day.plusDays(2)))
        assertEquals(List(31) { 300L }, WidgetWave.shifted(values, day, day.plusDays(40)))
        // A clock set back never shifts the other way.
        assertEquals(values, WidgetWave.shifted(values, day, day.minusDays(1)))
    }

    /**
     * The movement → balance change mapping, against the history fixtures' own rows: a successful movement moves the
     * balance by its effective delta; pending, failed and canceled movements, and unconfirmed transactions, don't.
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
                if (row.hasConfirmation == true) assertEquals(case.name, listOf(WidgetWave.Change(confirmed, row.balanceChangeSat!!)), changes)
                else assertEquals(case.name, emptyList<WidgetWave.Change>(), changes)
            }
        }
        assertTrue(movements > 20); assertTrue(transactions > 0)
    }

    private val now = instant("2026-10-27T15:00:00Z")
    private val board = HistoryTransaction("movement-7", "Onchain", 50_000, "2026-10-20T12:00:00.000Z", "incoming", source = "ark",
        txid = "board-txid", movementKind = "onboard", movementStatus = "successful", effectiveBalanceSat = 50_000)
    private val chain = HistoryTransaction("onchain-wallet-board-txid", "Onchain", 50_500, "1970-01-01T00:00:00.000Z", "outgoing",
        source = "onchain-wallet", txid = "board-txid", balanceChangeSat = -50_500, hasConfirmation = true, confirmationHeight = 900u)

    /** A board's two halves, merged as Activity shows them, move the balance by the fee alone, on the movement's day. */
    @Test fun aMergedBoardMovesTheBalanceByItsFee() {
        val deposit = HistoryTransaction("onchain-wallet-deposit", "Onchain", 60_000, "1970-01-01T00:00:00.000Z", "incoming",
            source = "onchain-wallet", txid = "deposit", balanceChangeSat = 60_000, hasConfirmation = true, confirmationHeight = 880u)
        val pending = deposit.copy(id = "onchain-wallet-pending", txid = "pending", hasConfirmation = false, confirmationHeight = null)
        val dates = mapOf("deposit" to instant("2026-10-26T08:00:00Z"))
        val changes = WidgetWave.changes(HistoryRules.unified(listOf(board, chain, deposit, pending)), dates)
        assertEquals(setOf(WidgetWave.Change(instant("2026-10-20T12:00:00Z"), -500), WidgetWave.Change(instant("2026-10-26T08:00:00Z"), 60_000)),
            changes.toSet())
        // An offboard counts its Ark half once settled, and its on-chain half once that's confirmed.
        val offboard = board.copy(id = "movement-8", txid = "offboard-txid", movementKind = "offboard", effectiveBalanceSat = -30_000)
        val credit = chain.copy(id = "onchain-wallet-offboard-txid", txid = "offboard-txid", balanceChangeSat = 29_700, hasConfirmation = false,
            confirmationHeight = null)
        assertEquals(listOf(-30_000L), WidgetWave.changes(HistoryRules.unified(listOf(offboard, credit)), emptyMap()).map { it.sats })
        val confirmed = credit.copy(hasConfirmation = true, confirmationHeight = 990u)
        assertEquals(listOf(-300L), WidgetWave.changes(HistoryRules.unified(listOf(offboard, confirmed)), emptyMap()).map { it.sats })
        // Pending movements change nothing.
        assertEquals(emptyList<WidgetWave.Change>(), WidgetWave.changes(listOf(board.copy(movementStatus = "pending")), emptyMap()))
    }

    /**
     * A round offboard names its transaction only as `funding_txid`: an old self-offboard whose debit is outside the
     * window must not read as income because its credit was confirmed inside it.
     */
    @Test fun aRoundOffboardPairsByFundingTxidAcrossTheWindowEdge() {
        val old = HistoryTransaction("movement-9", "Onchain", 30_000, "2026-09-20T12:00:00.000Z", "outgoing", source = "ark",
            txid = "vtxo-in", movementKind = "offboard", movementStatus = "successful", effectiveBalanceSat = -30_000,
            metadataJson = """{"funding_txid":"round-txid"}""")
        val credit = chain.copy(id = "onchain-wallet-round-txid", txid = "round-txid", balanceChangeSat = 29_700, confirmationHeight = 995u)
        val rows = HistoryRules.unified(listOf(old, credit))
        assertEquals("Activity's merge leaves it unpaired", 2, rows.size)
        // The credit's estimated date falls inside the window; paired, both halves stay on the offboard's day, outside it.
        val dates = WidgetWave.chainDates(rows, emptyMap(), emptyMap(), 1_000, now).mapValues { it.value.at }
        assertTrue(dates.getValue("round-txid") > instant("2026-10-27T00:00:00Z"))
        val changes = WidgetWave.changes(rows, dates)
        assertEquals(listOf(WidgetWave.Change(instant("2026-09-20T12:00:00Z"), -300)), changes)
        assertTrue(WidgetWave.calm(WidgetWave.dailyBalances(100_000, changes, now, ZoneId.of("UTC"))))
        // A recent one moves the wave by its fee, inside the window, whichever half is newer.
        val recent = old.copy(date = "2026-10-26T12:00:00.000Z")
        val wave = WidgetWave.dailyBalances(100_000, WidgetWave.changes(HistoryRules.unified(listOf(recent, credit)), dates), now, ZoneId.of("UTC"))
        assertEquals(300L, wave.first() - wave.last())
        // Unpaired, the credit alone would have read as +29,700 of income.
        assertEquals(29_700L, WidgetWave.change(WidgetWave.dailyBalances(100_000, WidgetWave.changes(listOf(credit), dates), now, ZoneId.of("UTC"))))
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
        // Unconfirmed transactions have no date; transactions no longer listed are dropped; no tip, no estimate.
        assertEquals(emptyMap<String, WidgetWave.ChainDate>(),
            WidgetWave.chainDates(listOf(deposit.copy(hasConfirmation = false)), first, emptyMap(), 1_000, now))
        assertEquals(emptyMap<String, WidgetWave.ChainDate>(), WidgetWave.chainDates(emptyList(), first, emptyMap(), 1_000, now))
        assertEquals(emptyMap<String, WidgetWave.ChainDate>(), WidgetWave.chainDates(listOf(deposit), emptyMap(), emptyMap(), null, now))
    }
}
