package com.noahwallet.services.wallet

import com.noahwallet.onchain.BoardInput
import com.noahwallet.onchain.BoardPrepared
import com.noahwallet.onchain.BoardPreparations
import com.noahwallet.onchain.OnchainAction
import com.noahwallet.onchain.OnchainQuote
import com.noahwallet.send.SendPrepared
import com.noahwallet.send.SendPreparations
import com.noahwallet.send.chainPayment
import com.noahwallet.send.chainQuote
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * Reviews time out on [ReviewClock]: moving the device clock back or forward during a review never lengthens it.
 * A review read back from storage has no review-clock reading and keeps its wall-clock cap.
 */
class ReviewClockTest {
    private val opened = 1_000_000L
    private val wall = 1_760_000_000_000L
    /** How far the device clock moved after the review opened. */
    private val moves = listOf(-86_400_000L, -300_000L, 300_000L, 86_400_000L)
    private val board = OnchainQuote(OnchainAction.board, "wallet", amount = 30_000, fee = 330, chainFee = 199,
        createdAt = wall, reviewedAt = opened, preparedBoard = BoardPrepared("id", "tx", 199, listOf(BoardInput("input", 0u))))
    private val exit = OnchainQuote(OnchainAction.startExit, "wallet", listOf("a"), createdAt = wall, reviewedAt = opened)
    private val send = checkNotNull(chainQuote.prepared).copy(createdAt = wall, reviewedAt = opened)

    @Test fun sendBoardAndExitReviewsExpireOnTheReviewClock() {
        for (move in moves) {
            assertTrue(send.isCurrent(opened + 119_999, wall + 119_999 + move)); assertFalse(send.isCurrent(opened + 120_000, wall + 120_000 + move))
            assertTrue(board.current(opened + 119_999, wall + 119_999 + move)); assertFalse(board.current(opened + 120_000, wall + 120_000 + move))
            assertTrue(exit.current(opened + 29_999, wall + 29_999 + move)); assertFalse(exit.current(opened + 30_000, wall + 30_000 + move))
        }
    }

    @Test fun preparedSendAndBoardReviewsCantBeClaimedAfterTheirDeadlineWhenTheClockWasSetBack() = runBlocking {
        // Opened two minutes ago on the review clock; the wall clock, set back, says just now.
        val sends = SendPreparations({}, {})
        val quote = chainQuote.copy(prepared = send.copy(createdAt = System.currentTimeMillis(), reviewedAt = ReviewClock.now() - 120_000))
        sends.retain("process-local"); sends.bind("process-local", chainPayment, quote)
        assertTrue(runCatching { sends.claim(chainPayment, quote) }.isFailure)
        sends.discardAll()
        val boards = BoardPreparations({}, { error("must not submit") }, {})
        val stale = board.copy(createdAt = System.currentTimeMillis(), reviewedAt = ReviewClock.now() - 120_000)
        boards.retain("id"); boards.bind(stale)
        assertTrue(runCatching { boards.claim(stale) }.isFailure)
        boards.discardAll()
    }

    @Test fun aReviewReadBackFromStorageHasNoReviewClockReadingAndKeepsItsWallClockCap() {
        val json = Json.encodeToString(OnchainQuote.serializer(), board.copy(preparedBoard = null, action = OnchainAction.startExit))
        assertFalse(json, json.contains("reviewedAt"))
        val decoded = Json.decodeFromString(OnchainQuote.serializer(), json)
        assertNull(decoded.reviewedAt); assertEquals(wall, decoded.createdAt)
        assertTrue(decoded.current(opened, wall + 29_999)); assertFalse(decoded.current(opened, wall + 30_000)); assertFalse(decoded.current(opened, wall - 1))
        val prepared = Json.encodeToString(SendPrepared.serializer(), send)
        assertFalse(prepared, prepared.contains("reviewedAt"))
        assertNull(Json.decodeFromString(SendPrepared.serializer(), prepared).reviewedAt)
    }
}
