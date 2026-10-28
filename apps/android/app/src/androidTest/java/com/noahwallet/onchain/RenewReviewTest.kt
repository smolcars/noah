package com.noahwallet.onchain

import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Home's Renew opens today's refresh review for exactly the expiring VTXOs that are still due, even when the quote
 * is slow, and checks again at confirmation.
 */
class RenewReviewTest {
    @get:Rule val compose = createComposeRule()

    private val ids = listOf("vtxo-b", "vtxo-a")
    private val gate = CompletableDeferred<Unit>()
    private val journal = mutableMapOf<String, OnchainOperationJournal.Attempt>()
    /** What the wallet says is due for renewal right now. */
    @Volatile private var due = setOf("vtxo-a", "vtxo-b")
    private var quoted = 0
    private val model = OnchainModel(OnchainModel.Dependencies(
        ready = { true },
        overview = { OnchainOverview("wallet", emptyList(), emptyList(), 0, 0, 0, null) },
        quote = { action, ids, _, _ -> quoted++; gate.await(); OnchainQuote(action, "wallet", ids.sorted(), fee = 12, chainFee = 0) },
        execute = { error("Renew is submitted through executeRenewal") },
        attempts = { emptyList() }, acknowledge = {}, check = { false }, override = {}, detail = { null }, refreshReceipts = {},
        // As the session does, under the wallet's locks: every VTXO must still be due, or nothing is submitted.
        executeRenewal = { quote ->
            if (!quote.ids.all { it in due }) fail(OnchainFailure.changed)
            journal[quote.id] = OnchainOperationJournal.Attempt(quote, OnchainReceipt("Refresh submitted to delegated round 7."))
            "Refresh submitted to delegated round 7."
        },
    ))
    private var started: List<String>? = null

    /** Hosts the request the way VtxosScreen does, with the request read during composition (so clearing it recomposes). */
    private fun show() {
        var request by mutableStateOf<List<String>?>(ids)
        compose.setContent {
            Text(request)
            RenewReview(model, take = { request.also { request = null } }, due = { ids -> ids.filter { it in due } },
                onStart = { started = it })
        }
    }
    @Composable private fun Text(@Suppress("UNUSED_PARAMETER") request: List<String>?) = Unit

    @Test fun aLateQuoteStillOpensTheReviewForExactlyThoseVtxos() {
        show()
        compose.waitUntil(5_000) { started != null }
        assertEquals(ids, started)
        // Recompositions after the request was consumed must not cancel the review while the quote is slow.
        Thread.sleep(600)
        compose.waitForIdle()
        assertNull(model.quote)
        gate.complete(Unit)
        compose.waitUntil(5_000) { model.quote != null }
        assertEquals(ids.sorted(), model.quote!!.ids)
        assertEquals(OnchainAction.refresh, model.quote!!.action)
        assertTrue("nothing is submitted until the user confirms", journal.isEmpty())
    }

    @Test fun confirmingSubmitsExactlyTheReviewedVtxos() {
        gate.complete(Unit)
        show()
        compose.waitUntil(5_000) { model.quote != null }
        // On the main thread, as a tap confirms.
        runBlocking(Dispatchers.Main) { model.confirm() }
        compose.waitUntil(5_000) { journal.isNotEmpty() }
        assertEquals(ids.sorted(), journal.values.single().quote.ids)
    }

    @Test fun aVtxoThatStoppedBeingDueSinceHomeCheckedIsLeftOut() {
        gate.complete(Unit)
        due = setOf("vtxo-a")
        show()
        compose.waitUntil(5_000) { model.quote != null }
        assertEquals(listOf("vtxo-a"), started)
        assertEquals(listOf("vtxo-a"), model.quote!!.ids)
    }

    @Test fun withNothingStillDueThereIsNoReview() {
        gate.complete(Unit)
        due = emptySet()
        show()
        compose.waitForIdle()
        Thread.sleep(500)
        compose.waitForIdle()
        assertNull(started)
        assertNull(model.quote)
        assertEquals("nothing was even quoted", 0, quoted)
    }

    @Test fun submittingChecksAgainThatEveryReviewedVtxoIsStillDue() {
        gate.complete(Unit)
        show()
        compose.waitUntil(5_000) { model.quote != null }
        due = setOf("vtxo-a") // vtxo-b expires while the review is open, or while submitting waits for the locks
        runBlocking(Dispatchers.Main) { model.confirm() }
        compose.waitUntil(5_000) { !model.busy }
        assertTrue("nothing was submitted", journal.isEmpty())
        assertEquals(OnchainFailure.changed.text, model.error)
    }

    @Test fun aDismissedReviewSubmitsNothing() {
        gate.complete(Unit)
        show()
        compose.waitUntil(5_000) { model.quote != null }
        compose.runOnIdle { model.invalidate() }
        compose.waitForIdle()
        Thread.sleep(300)
        compose.waitForIdle()
        assertTrue(journal.isEmpty())
    }
}
