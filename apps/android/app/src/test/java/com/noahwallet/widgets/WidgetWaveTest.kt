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
        val now = instant("2026-10-27T15:00:00Z"); val tip = 1_000L
        var movements = 0; var transactions = 0
        for (case in SharedFixtures.load("transaction-history")) {
            val operation = case.input.jsonObject.getValue("operation").jsonPrimitive.content
            val mapped = operation == "movement" || operation == "onchain"
            if (!mapped || !case.expected.jsonObject.getValue("included").jsonPrimitive.boolean) continue
            val expected = case.expected.jsonObject
            val row = json.decodeFromJsonElement<HistoryTransaction>(expected.getValue("transaction"))
            val changes = WidgetWave.changes(listOf(row), tip, now)
            if (operation == "movement") {
                movements++
                val sats = row.effectiveBalanceSat ?: 0L
                if (row.movementStatus == "successful" && sats != 0L) assertEquals(case.name, listOf(WidgetWave.Change(row.timestamp!!.time, sats)), changes)
                else assertEquals(case.name, emptyList<WidgetWave.Change>(), changes)
            } else {
                transactions++
                val height = row.confirmationHeight?.toLong()
                if (row.hasConfirmation == true && height != null) assertEquals(case.name,
                    listOf(WidgetWave.Change(now - (tip - height).coerceAtLeast(0) * 600_000, row.balanceChangeSat!!)), changes)
                else assertEquals(case.name, emptyList<WidgetWave.Change>(), changes)
            }
        }
        assertTrue(movements > 20); assertTrue(transactions > 0)
    }

    /** A board's two halves, merged as Activity shows them, move the balance by the fee alone, on the movement's day. */
    @Test fun aMergedBoardMovesTheBalanceByItsFee() {
        val now = instant("2026-10-27T15:00:00Z")
        val board = HistoryTransaction("movement-7", "Onchain", 50_000, "2026-10-20T12:00:00.000Z", "incoming", source = "ark",
            txid = "board-txid", movementKind = "onboard", movementStatus = "successful", effectiveBalanceSat = 50_000)
        val chain = HistoryTransaction("onchain-wallet-board-txid", "Onchain", 50_500, "1970-01-01T00:00:00.000Z", "outgoing",
            source = "onchain-wallet", txid = "board-txid", balanceChangeSat = -50_500, hasConfirmation = true, confirmationHeight = 900u)
        val deposit = HistoryTransaction("onchain-wallet-deposit", "Onchain", 60_000, "1970-01-01T00:00:00.000Z", "incoming",
            source = "onchain-wallet", txid = "deposit", balanceChangeSat = 60_000, hasConfirmation = true, confirmationHeight = 880u)
        val pending = deposit.copy(id = "onchain-wallet-pending", txid = "pending", hasConfirmation = false, confirmationHeight = null)
        val changes = WidgetWave.changes(HistoryRules.unified(listOf(board, chain, deposit, pending)), 1_000, now)
        assertEquals(setOf(WidgetWave.Change(instant("2026-10-20T12:00:00Z"), -500), WidgetWave.Change(now - 120 * 600_000L, 60_000)), changes.toSet())
        // An offboard counts its Ark half once settled, and its on-chain half once that's confirmed.
        val offboard = board.copy(id = "movement-8", txid = "offboard-txid", movementKind = "offboard", effectiveBalanceSat = -30_000)
        val credit = chain.copy(id = "onchain-wallet-offboard-txid", txid = "offboard-txid", balanceChangeSat = 29_700, hasConfirmation = false,
            confirmationHeight = null)
        assertEquals(listOf(-30_000L), WidgetWave.changes(HistoryRules.unified(listOf(offboard, credit)), 1_000, now).map { it.sats })
        val confirmed = credit.copy(hasConfirmation = true, confirmationHeight = 990u)
        assertEquals(listOf(-300L), WidgetWave.changes(HistoryRules.unified(listOf(offboard, confirmed)), 1_000, now).map { it.sats })
        // Pending movements change nothing.
        assertEquals(emptyList<WidgetWave.Change>(), WidgetWave.changes(listOf(board.copy(movementStatus = "pending")), 1_000, now))
    }
}
