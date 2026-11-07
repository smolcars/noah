package com.noahwallet.services.wallet

import com.noahwallet.services.backup.RestoreTransaction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A restored backup's spendable VTXOs are checked with the server once, after a sync; a VTXO left unanswered keeps the
 * check for the next pass, up to [RestoredVtxoCheck.maxPasses].
 */
class RestoredVtxoCheckTest {
    private class Unanswered : Exception()

    private fun steps(calls: MutableList<String>, ids: List<String> = listOf("a", "b"), failing: Set<String> = emptySet()) =
        RestoredVtxoCheck.Steps(sync = { calls += "sync" }, spendableIds = { ids }, adopt = { id ->
            calls += id
            if (id in failing) throw Unanswered()
        })

    private inline fun marker(text: String? = "", body: (File) -> Unit) {
        val root = Files.createTempDirectory("vtxo-check").toFile()
        try {
            val marker = File(root, RestoreTransaction.vtxoCheckName)
            text?.let { marker.writeText(it) }
            body(marker)
        } finally { root.deleteRecursively() }
    }

    @Test fun withoutARestoreNothingRuns() = runBlocking {
        marker(text = null) { marker ->
            val calls = mutableListOf<String>()
            assertEquals(0, RestoredVtxoCheck.run(marker, steps(calls)))
            assertTrue(calls.isEmpty())
        }
    }

    @Test fun syncsThenChecksEachSpendableVtxoOnce() = runBlocking {
        marker { marker ->
            val calls = mutableListOf<String>()
            assertEquals(0, RestoredVtxoCheck.run(marker, steps(calls)))
            assertEquals(listOf("sync", "a", "b"), calls)
            assertFalse(marker.exists())
            // Done: the next pass does nothing.
            assertEquals(0, RestoredVtxoCheck.run(marker, steps(calls)))
            assertEquals(listOf("sync", "a", "b"), calls)
        }
    }

    @Test fun anUnansweredVtxoDoesNotStopTheOthersAndIsCheckedAgain() = runBlocking {
        marker { marker ->
            val calls = mutableListOf<String>()
            assertEquals(1, RestoredVtxoCheck.run(marker, steps(calls, failing = setOf("a"))))
            assertEquals(listOf("sync", "a", "b"), calls)
            assertEquals("1", marker.readText())
            calls.clear()
            assertEquals(0, RestoredVtxoCheck.run(marker, steps(calls)))
            assertEquals(listOf("sync", "a", "b"), calls)
            assertFalse(marker.exists())
        }
    }

    @Test fun givesUpAfterTheLastPass() = runBlocking {
        marker { marker ->
            val calls = mutableListOf<String>()
            for (pass in 1 until RestoredVtxoCheck.maxPasses) {
                assertEquals(1, RestoredVtxoCheck.run(marker, steps(calls, failing = setOf("a"))))
                assertEquals(pass.toString(), marker.readText())
            }
            assertEquals(1, RestoredVtxoCheck.run(marker, steps(calls, failing = setOf("a"))))
            assertFalse(marker.exists())
        }
    }

    @Test fun aFailedSyncKeepsTheCheckWithoutCountingAPass() = runBlocking {
        marker { marker ->
            val calls = mutableListOf<String>()
            val offline = RestoredVtxoCheck.Steps(sync = { throw Unanswered() }, spendableIds = { listOf("a") }, adopt = { calls += it })
            assertThrows(Unanswered::class.java) { runBlocking { RestoredVtxoCheck.run(marker, offline) } }
            assertTrue(calls.isEmpty())
            assertEquals("", marker.readText())
        }
    }
}
