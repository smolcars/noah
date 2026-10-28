package com.noahwallet.history

import com.noahwallet.services.AppServices
import com.noahwallet.services.wallet.WalletSession
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

class HistoryModelTest {
    private fun row(id: String = "movement-1") = HistoryTransaction(id, "Arkoor", 2100, "2026-07-15T12:00:00.000Z", "incoming", source = "ark")
    private fun dependencies(rows: List<HistoryTransaction> = listOf(row())) =
        HistoryModel.Dependencies(load = { rows }, sync = {}, changes = { emptyFlow() }, blockTime = { null }, price = { _, _ -> 100000.0 })

    @Test fun everyCompletedReadMovesTheRevisionEvenWhenNothingChanged() = runBlocking {
        val model = HistoryModel(dependencies())
        model.refresh("USD"); model.awaitEnrichment()
        val first = model.revision
        val before = model.transactions
        model.refresh("USD", sync = true); model.awaitEnrichment()
        assertEquals(before, model.transactions)
        assertEquals(first + 1, model.revision)
        // A failed read doesn't count.
        val failing = HistoryModel(dependencies().copy(load = { error("offline") }))
        failing.refresh("USD")
        assertEquals(0L, failing.revision)
    }

    @Test fun closedAndSuspendedWalletsClearHistory() = runBlocking {
        for (failure in listOf(WalletSession.SessionError(), AppServices.WalletSuspended())) {
            var closed = false
            val model = HistoryModel(dependencies().copy(load = { if (closed) throw failure else listOf(row()) }))
            model.refresh("USD"); model.awaitEnrichment(); closed = true; model.refresh("USD")
            assertTrue(model.transactions.isEmpty()); assertNotNull(model.error)
            assertFalse(model.isLoading); assertFalse(model.isRefreshing)
        }
    }
    @Test fun observationCanRestartAfterSuspensionAndClearCannotLeakTheDeletedWallet() = runBlocking {
        val events = Channel<String>(Channel.UNLIMITED)
        val reads = Channel<Unit>(Channel.UNLIMITED)
        var id = "old-wallet"
        val stalled = CompletableDeferred<Unit>()
        val model = HistoryModel(dependencies().copy(
            load = { reads.send(Unit); listOf(row(id)) }, changes = { events.receiveAsFlow() },
            price = { _, _ -> stalled.complete(Unit); awaitCancellation() }))
        val first = launch { model.observe("USD") }
        withTimeout(2000) { reads.receive(); stalled.await() }
        first.cancelAndJoin(); model.clear()
        assertTrue(model.transactions.isEmpty())
        val resumed = launch { model.observe("USD") }
        withTimeout(2000) { reads.receive() }
        yield(); assertEquals("old-wallet", model.transactions.single().id)
        resumed.cancelAndJoin(); model.clear(); id = "new-wallet"
        assertTrue(model.transactions.isEmpty())
        model.refresh("USD")
        assertEquals("new-wallet", model.transactions.single().id)
        model.clear(); yield(); assertTrue(model.transactions.isEmpty())
    }

    @Test fun usesHistoricalTimestampAndCurrency() = runBlocking {
        val model = HistoryModel(dependencies().copy(price = { timestamp, currency ->
            assertEquals(1784116800, timestamp); assertEquals("EUR", currency); 85000.0
        }))
        model.refresh("EUR"); model.awaitEnrichment(); assertEquals(85000.0, model.transactions.single().btcPrice)
    }
    @Test fun missingMarketDataKeepsPayments() = runBlocking {
        val model = HistoryModel(dependencies().copy(price = { _, _ -> error("offline") }))
        model.refresh("USD")
        model.awaitEnrichment()
        assertEquals(1, model.transactions.size)
        assertNull(model.transactions.single().btcPrice)
        assertNull(model.error)
    }
    @Test fun missingHistoricalRateUsesUncachedCurrentRate() = runBlocking {
        var calls = 0
        val model = HistoryModel(dependencies().copy(price = { _, _ -> null }, currentPrice = { ++calls * 100000.0 }))
        model.refresh("USD"); model.awaitEnrichment(); assertEquals(100000.0, model.transactions.single().btcPrice)
        model.refresh("USD"); model.awaitEnrichment(); assertEquals(200000.0, model.transactions.single().btcPrice)
    }
    @Test fun quotesAreCachedByDayAndCurrency() = runBlocking {
        var calls = 0
        val model = HistoryModel(dependencies(listOf(row(), row("movement-2").copy(date = "2026-07-15T13:00:00.000Z")))
            .copy(price = { _, _ -> calls++; 100000.0 }))
        model.refresh("USD"); model.awaitEnrichment(); assertEquals(1, calls)
        model.refresh("EUR"); model.awaitEnrichment(); assertEquals(2, calls)
    }
    @Test fun staleEnrichmentCannotOverwriteNewRefresh() = runBlocking {
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Double>()
        val model = HistoryModel(dependencies().copy(price = { _, currency ->
            if (currency == "USD") { started.complete(Unit); finish.await() } else 85000.0
        }))
        val old = launch { model.refresh("USD") }; started.await()
        assertEquals(1, model.transactions.size)
        model.refresh("EUR"); model.awaitEnrichment(); finish.complete(100000.0); old.join()
        assertEquals(85000.0, model.transactions.single().btcPrice)
    }
    @Test fun observerSubscribesBeforeReadAndStopsOnCancellation() = runBlocking {
        var subscribed = false; var stopped = false
        val reads = Channel<Unit>(Channel.UNLIMITED); val events = Channel<String>(Channel.UNLIMITED)
        val model = HistoryModel(dependencies().copy(
            changes = { flow { subscribed = true; try { emitAll(events.receiveAsFlow()) } finally { stopped = true } } },
            load = { assertTrue(subscribed); reads.send(Unit); listOf(row()) }))
        val observation = launch { model.observe("USD") }
        withTimeout(2000) { reads.receive(); events.send("databaseChanged"); reads.receive() }
        observation.cancelAndJoin(); assertTrue(stopped); assertEquals(1, model.transactions.size)
    }
    @Test fun resyncRequiredReopensSubscription() = runBlocking {
        var subscriptions = 0
        val opened = Channel<Int>(Channel.UNLIMITED)
        val model = HistoryModel(dependencies().copy(price = { _, _ -> awaitCancellation() }, changes = { flow {
            val number = ++subscriptions; opened.send(number)
            emit(if (number == 1) "resyncRequired" else "initial")
            awaitCancellation()
        } }))
        val observation = launch { model.observe("USD") }
        withTimeout(2000) { assertEquals(1, opened.receive()); assertEquals(2, opened.receive()) }
        observation.cancelAndJoin()
    }
    @Test fun confirmedBlockTimeAndFiatAreEnrichedBeforeMerging() = runBlocking {
        val movement = row().copy(movementKind = "onboard", txid = "tx", date = "1970-01-01T00:00:00.000Z")
        val chain = row("chain").copy(source = "onchain-wallet", txid = "tx", hasConfirmation = true,
            dateLabel = "Confirmed at block 2", confirmationHeight = 2u, hasOnchainFee = true, onchainFeeSat = 42)
        var blocks = 0
        val model = HistoryModel(dependencies(listOf(movement, chain)).copy(blockTime = { blocks++; Date(1784116800000) }))
        model.refresh("USD"); model.awaitEnrichment(); model.refresh("USD"); model.awaitEnrichment()
        assertEquals(1, blocks); assertEquals(1, model.transactions.size)
        assertEquals(100000.0, model.transactions.single().btcPrice)
        assertEquals(42L, model.transactions.single().onchainFeeSat)
    }
    @Test fun cancelingEnrichmentSettlesRefresh() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val model = HistoryModel(dependencies().copy(price = { _, _ -> started.complete(Unit); awaitCancellation() }))
        val job = launch { model.refresh("USD") }; started.await(); job.cancelAndJoin()
        assertFalse(model.isRefreshing); assertEquals(1, model.transactions.size); assertNull(model.error)
    }
    @Test fun stalledPricesDoNotBlockWalletEventsOrRefreshAndPublishIncrementally() = runBlocking {
        val events = Channel<String>(Channel.UNLIMITED)
        val started = Channel<Unit>(Channel.UNLIMITED)
        var settled = false
        var canceled = 0
        var active = 0
        val model = HistoryModel(dependencies().copy(
            changes = { events.receiveAsFlow() },
            load = { listOf(row().copy(movementStatus = if (settled) "successful" else "pending"),
                row("old").copy(date = "2026-07-14T12:00:00.000Z")) },
            price = { timestamp, _ ->
                if (timestamp == 1784116800L) 100000.0 else {
                    active++; assertEquals(1, active); started.send(Unit)
                    try { awaitCancellation() } finally { active--; canceled++ }
                }
            }))
        val observation = launch { model.observe("USD") }
        withTimeout(2000) {
            started.receive()
            assertFalse(model.isRefreshing); assertEquals(100000.0, model.transactions.first().btcPrice)
            settled = true; events.send("databaseChanged"); started.receive()
            assertEquals("successful", model.transactions.first().movementStatus)
            assertFalse(model.isRefreshing); assertTrue(canceled >= 1)
        }
        observation.cancelAndJoin(); assertEquals(0, active)
    }

    @Test(timeout = 30_000)
    fun warmQuoteCacheYieldsToWalletEventsDuringLargeEnrichment() = runBlocking {
        val events = Channel<String>(Channel.UNLIMITED)
        var rows = listOf(row())
        var quotes = 0
        var settled = false
        var enrichedAtEvent: Int? = null
        lateinit var model: HistoryModel
        model = HistoryModel(dependencies().copy(
            changes = { events.receiveAsFlow() },
            load = {
                if (settled) enrichedAtEvent = model.transactions.count { it.btcPrice != null }
                rows
            }, price = { _, _ -> quotes++; 100000.0 }))
        model.refresh("USD"); model.awaitEnrichment() // warm the real day/currency cache
        rows = (1..3000).map { row("movement-$it").copy(sortTimestamp = 1784116800000.0) }
        val observation = launch { model.observe("USD") }
        try {
            withTimeout(5000) {
                while (model.transactions.size != rows.size || model.transactions.none { it.btcPrice != null }) yield()
                assertTrue("cached enrichment must yield before completing all rows",
                    model.transactions.count { it.btcPrice != null } < rows.size)
                settled = true; events.send("databaseChanged")
                while (enrichedAtEvent == null) yield()
                assertTrue("wallet event must be consumed during enrichment", enrichedAtEvent < rows.size)
            }
            model.awaitEnrichment()
            assertTrue(model.transactions.all { it.btcPrice == 100000.0 })
            assertEquals(1, quotes)
        } finally { observation.cancelAndJoin() }
    }

    @Test fun filteredCSVUsesSignedAccountingAndEscapesFields() {
        val outgoing = row().copy(direction = "outgoing", destination = "a,\"b\"\nc")
        val canceled = row("movement-2").copy(type = "Onchain", movementKind = "exit", movementStatus = "canceled")
        val csv = HistoryCSV.content(listOf(outgoing, canceled).filter(HistoryFilter.Ark::includes), "USD")
        assertTrue(csv.contains("Amount (sats)")); assertTrue(csv.contains("Outgoing,-2100"))
        assertTrue(csv.contains("\"a,\"\"b\"\"\nc\"")); assertFalse(csv.contains("movement-2"))
        assertTrue(HistoryCSV.content(listOf(canceled), "USD").contains("None,0"))
    }
    @Test fun explorerMatchesRNVariantsAndStripsOutpoint() {
        val chain = row().copy(type = "Onchain", txid = "funding:1")
        assertEquals("https://mempool.space/tx/funding", chain.explorerURL("mainnet"))
        assertEquals("https://mempool.space/signet/tx/funding", chain.explorerURL("signet"))
        assertEquals(chain.explorerURL("signet"), chain.explorerURL("regtest"))
        assertNull(chain.copy(type = "Arkoor").explorerURL("mainnet"))
    }
}
