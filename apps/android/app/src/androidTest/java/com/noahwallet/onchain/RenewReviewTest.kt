package com.noahwallet.onchain

import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Home's Renew opens today's refresh review for exactly the expiring VTXOs, even when the quote is slow, and
 * reports "Renewed" only when the journal holds that reviewed quote's receipt.
 */
class RenewReviewTest {
    @get:Rule val compose = createComposeRule()

    private val ids = listOf("vtxo-b", "vtxo-a")
    private val gate = CompletableDeferred<Unit>()
    private val journal = mutableMapOf<String, OnchainOperationJournal.Attempt>()
    private val model = OnchainModel(OnchainModel.Dependencies(
        ready = { true },
        overview = { OnchainOverview("wallet", emptyList(), emptyList(), 0, 0, 0, null) },
        quote = { action, ids, _, _ -> gate.await(); OnchainQuote(action, "wallet", ids.sorted(), fee = 12, chainFee = 0) },
        execute = { quote -> journal[quote.id] = OnchainOperationJournal.Attempt(quote, OnchainReceipt("Refresh submitted to delegated round 7.")); "Refresh submitted to delegated round 7." },
        attempts = { emptyList() }, acknowledge = {}, check = { false }, override = {}, detail = { null }, refreshReceipts = {},
    ))
    private var renewed: List<String>? = null
    private var started: List<String>? = null

    /** Hosts the request the way VtxosScreen does, with the request read during composition (so clearing it recomposes). */
    private fun show() {
        var request by mutableStateOf<List<String>?>(ids)
        compose.setContent {
            Text(request)
            RenewReview(model, take = { request.also { request = null } }, attempt = { journal[it] },
                onStart = { started = it }, renewed = { renewed = it })
        }
    }
    @Composable private fun Text(@Suppress("UNUSED_PARAMETER") request: List<String>?) {}

    @Test fun aLateQuoteStillOpensTheReviewForExactlyThoseVtxos() {
        show()
        compose.waitForIdle()
        assertEquals(ids, started)
        // Recompositions after the request was consumed must not cancel the review while the quote is slow.
        Thread.sleep(600)
        compose.waitForIdle()
        assertNull(model.quote)
        gate.complete(Unit)
        compose.waitUntil(5_000) { model.quote != null }
        assertEquals(ids.sorted(), model.quote!!.ids)
        assertEquals(OnchainAction.refresh, model.quote!!.action)
        assertNull("nothing is renewed until the user confirms", renewed)
    }

    @Test fun onlyAConfirmedReviewWithAReceiptReportsRenewed() {
        gate.complete(Unit)
        show()
        compose.waitUntil(5_000) { model.quote != null }
        runBlocking { model.confirm() }
        compose.waitUntil(5_000) { renewed != null }
        assertEquals(ids.sorted(), renewed)
    }

    @Test fun aDismissedReviewReportsNothing() {
        gate.complete(Unit)
        show()
        compose.waitUntil(5_000) { model.quote != null }
        compose.runOnIdle { model.invalidate() }
        compose.waitForIdle()
        Thread.sleep(300)
        compose.waitForIdle()
        assertNull(renewed)
        assertTrue(journal.isEmpty())
    }
}
