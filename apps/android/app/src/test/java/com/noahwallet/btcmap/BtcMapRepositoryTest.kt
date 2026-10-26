package com.noahwallet.btcmap

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class BtcMapRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val raw = """{"id":1,"lat":33,"lon":-84,"icon":"cafe","name":"Cafe","updated_at":"2026-01-01","comments":1,"website":"https://example.com"}"""
    private val bundle = """{"schemaVersion":1,"generatedAt":"2026-01-01T00:00:00.000Z","source":"btcmap.org","places":[$raw]}"""
    private fun repo(file: File = File(folder.root, "cache"), write: (File, String) -> Unit = BtcMapRepository::atomicWrite,
        fetch: suspend (okhttp3.HttpUrl) -> String) = BtcMapRepository(file, { bundle }, { error("unused") }, fetch, write, 0)

    @Test fun concurrentSyncDeduplicatesAndRespectsFiveMinutes() = runBlocking {
        val urls = mutableListOf<okhttp3.HttpUrl>()
        val file = File(folder.root, "cache")
        val repository = repo(file) { urls += it; delay(20); "[]" }
        val now = 1_800_000_000_123L
        val (a, b) = awaitAll(async { repository.sync(now) }, async { repository.sync(now) })
        assertEquals(a, b); assertEquals(1, urls.size)
        repository.sync(now + 299_999); assertEquals(1, urls.size)
        repository.sync(now + 300_000); assertEquals(2, urls.size)
        assertEquals("2026-01-01T00:00:00.000Z", urls[0].queryParameter("updated_since"))
        assertEquals(a.generatedAt, urls[1].queryParameter("updated_since"))
        assertEquals("true", urls[0].queryParameter("include_deleted"))
        assertTrue(urls[0].queryParameter("fields")!!.endsWith(",deleted_at"))
        assertEquals("2027-01-15T08:00:00.123Z", a.generatedAt)
        assertEquals(repository.load(), repo(file) { error("unused") }.load())
    }

    @Test fun malformedPatchPreservesDiskAndCursor() = runBlocking {
        val file = folder.newFile(); file.writeText("corrupt cache")
        var requests = 0
        val repository = repo(file) { requests++; """[{"id":1}]""" }
        val current = repository.load()
        assertTrue(runCatching { repository.sync() }.isFailure)
        assertEquals(2, requests); assertEquals("corrupt cache", file.readText()); assertEquals(current, repository.load())
    }

    @Test fun failedWriteNeverPublishesMergedSnapshot() = runBlocking {
        val file = folder.newFile(); file.writeText(bundle)
        val repository = repo(file, { _, _ -> throw IOException("disk full") }) { """[{"id":1,"deleted_at":"2026-10-01"}]""" }
        assertTrue(runCatching { repository.sync() }.isFailure)
        assertEquals(bundle, file.readText()); assertEquals(1, repository.load().places.size)
    }

    @Test fun detailDeduplicatesExpiresAndSkipsUnneededComments() = runBlocking {
        // detail() fetches the place and its comments concurrently on Dispatchers.IO.
        val urls = java.util.concurrent.CopyOnWriteArrayList<okhttp3.HttpUrl>()
        val repository = repo { urls += it; delay(10); if (it.encodedPath.endsWith("/comments"))
            """[{"id":1,"text":"Paid with bitcoin","created_at":"2026-01-01"}]""" else raw }
        val place = repository.load().places.single()
        val (a, b) = awaitAll(async { repository.detail(place, 1000) }, async { repository.detail(place, 1000) })
        assertEquals(a, b); assertEquals(2, urls.size); assertEquals(1, a.comments.size)
        repository.detail(place, 900_999); assertEquals(2, urls.size)
        repository.detail(place, 901_000); assertEquals(4, urls.size)
        val noComments = repo { urls += it; raw }.detail(place.copy(comments = 0))
        assertTrue(noComments.comments.isEmpty()); assertEquals(5, urls.size)
    }

    @Test fun cancellationDoesNotRetryOrSave() = runBlocking {
        var requests = 0
        val file = File(folder.root, "cache")
        val repository = repo(file) { requests++; throw CancellationException() }
        assertTrue(runCatching { repository.sync() }.exceptionOrNull() is CancellationException)
        assertEquals(1, requests); assertFalse(file.exists())
    }

    @Test fun ordinaryFailureWaitsBeforeItsOnlyRetry() = runBlocking {
        val times = mutableListOf<Long>()
        val repository = BtcMapRepository(File(folder.root, "cache"), { bundle }, { "" }, {
            times += System.nanoTime()
            throw IOException("offline")
        }, retryDelay = 50)
        assertTrue(runCatching { repository.sync() }.isFailure)
        assertEquals(2, times.size)
        assertTrue(times[1] - times[0] >= 50_000_000L)
        assertEquals(BtcMapSnapshot.parse(bundle), repository.load())
    }

    @Test fun coordinatesAndWrongDetailIdentityAreRejected() = runBlocking {
        assertTrue(runCatching { BtcMapSnapshot.parse(bundle.replace("\"lat\":33", "\"lat\":91")) }.isFailure)
        val repository = repo { raw.replace("\"id\":1", "\"id\":2") }
        assertTrue(runCatching { repository.detail(repository.load().places.single().copy(comments = 0)) }.isFailure)
    }
}
