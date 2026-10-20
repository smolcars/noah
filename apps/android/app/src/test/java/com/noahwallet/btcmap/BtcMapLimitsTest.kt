package com.noahwallet.btcmap

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile

class BtcMapLimitsTest {
    @get:Rule val folder = TemporaryFolder()
    private val place = """{"id":1,"lat":1,"lon":2,"icon":"cafe","name":"Cafe","updated_at":"2026-01-01"}"""
    private fun snapshot(places: String = place) = """{"schemaVersion":1,"generatedAt":"2026-01-01","source":"btcmap.org","places":[$places]}"""
    private fun limit(block: () -> Unit) { assertThrows(BtcMapResourceLimit::class.java, block) }

    @Test fun streamStopsOneByteBeyondLimitAndAcceptsExactLength() {
        val stream = ByteArrayInputStream(ByteArray(100))
        limit { BtcMapLimits.read(stream, limit = 16) }
        assertEquals(83, stream.available())
        assertEquals(16, BtcMapLimits.read(ByteArrayInputStream(ByteArray(16)), limit = 16).length)
    }

    @Test fun declaredOversizeIsRejectedBeforeReadingAndUnderreportedLengthStillStops() {
        val stream = ByteArrayInputStream(ByteArray(100))
        limit { BtcMapLimits.read(stream, contentLength = 17, limit = 16) }
        assertEquals(100, stream.available())
        limit { BtcMapLimits.read(stream, contentLength = 1, limit = 16) }
        assertEquals(83, stream.available())
    }

    @Test fun byteAndUTF8StringLimitsAcceptBoundaryAndRejectOverflow() {
        BtcMapLimits.checkBytes(" ".repeat(BtcMapLimits.bytes))
        limit { BtcMapLimits.checkBytes(" ".repeat(BtcMapLimits.bytes + 1)) }
        BtcMapLimits.checkString("é".repeat(BtcMapLimits.stringBytes / 2))
        limit { BtcMapLimits.checkString("é".repeat(BtcMapLimits.stringBytes / 2) + "a") }
    }

    @Test fun snapshotPatchAndCommentListsEnforceRecordLimitWhileDecoding() {
        val entries = List(BtcMapLimits.records) { "{}" }.joinToString(",")
        assertEquals(BtcMapLimits.records, parseMapRecords("[$entries]").size)
        limit { BtcMapSnapshot.parse(snapshot("$entries,{}")) }
        limit { BtcMapPatch.parse("[$entries,{}]") }
        limit { BtcMapDetail.parse(place, "[$entries,{}]") }
    }

    @Test fun allMapPayloadParsersRejectOversizeBytes() {
        val text = " ".repeat(BtcMapLimits.bytes + 1)
        limit { BtcMapSnapshot.parse(text) }
        limit { BtcMapPatch.parse(text) }
        limit { BtcMapDetail.parse(text, "[]") }
        limit { BtcMapDetail.parse(place, text) }
    }

    @Test fun fieldsAndMetadataEnforceStringLimit() {
        val long = "x".repeat(BtcMapLimits.stringBytes + 1)
        val oversized = place.replace("Cafe", long)
        limit { BtcMapSnapshot.parse(snapshot(oversized)) }
        limit { BtcMapSnapshot.parse(snapshot().replace("btcmap.org", long)) }
        limit { BtcMapSnapshot.parse(snapshot().replace("\"generatedAt\":\"2026-01-01\"", "\"generatedAt\":\"$long\"")) }
        limit { BtcMapPatch.parse("[$oversized]") }
        limit { BtcMapPatch.parse("""[{"id":1,"deleted_at":"$long"}]""") }
        limit { BtcMapDetail.parse(oversized, "[]") }
        limit { BtcMapDetail.parse(place.dropLast(1) + """, "description":"$long"}""", "[]") }
        for (key in listOf("text", "created_at")) {
            limit { BtcMapDetail.parse(place, """[{"id":1,"text":"ok","created_at":"today","$key":"$long"}]""") }
        }
    }

    @Test fun oversizedCacheFallsBackWithoutReplacingFile() = runBlocking {
        val cache = folder.newFile()
        RandomAccessFile(cache, "rw").use { it.setLength(BtcMapLimits.bytes.toLong() + 1) }
        val repository = BtcMapRepository(cache, { snapshot() }, { "" }, { "[]" })
        assertEquals(1, repository.load().places.size)
        assertEquals(BtcMapLimits.bytes.toLong() + 1, cache.length())
    }

    @Test fun oversizedSyncPreservesCacheAndCursorWithoutRetryDuringCooldown() = runBlocking {
        val cache = folder.newFile().apply { writeText(snapshot()) }
        val cursors = mutableListOf<String?>()
        var reject = true
        val repository = BtcMapRepository(cache, { snapshot() }, { "" }, {
            cursors += it.queryParameter("updated_since")
            if (reject) throw BtcMapResourceLimit() else "[]"
        }, retryDelay = 0)
        val initial = repository.load()
        for (now in listOf(1000L, 1001L, 300999L)) limit { runBlocking { repository.sync(now) } }
        assertEquals(1, cursors.size)
        assertEquals(snapshot(), cache.readText()); assertEquals(initial, repository.load())
        reject = false
        repository.sync(301000)
        assertEquals(listOf(initial.generatedAt, initial.generatedAt), cursors)
    }

    @Test fun oversizedDetailHasPerMerchantCooldownAndDoesNotRetry() = runBlocking {
        var requests = 0
        val repository = BtcMapRepository(File(folder.root, "cache"), { snapshot() }, { "" }, {
            requests++; throw BtcMapResourceLimit()
        }, retryDelay = 0)
        val merchant = repository.load().places.single()
        for (now in listOf(1000L, 1001L, 300999L)) limit { runBlocking { repository.detail(merchant, now) } }
        assertEquals(1, requests)
        limit { runBlocking { repository.detail(merchant.copy(id = 2), 1001) } }
        assertEquals(2, requests)
        limit { runBlocking { repository.detail(merchant, 301000) } }
        assertEquals(3, requests)
    }

    @Test fun clockRollbackExpiresSyncAndPerMerchantOversizeCooldowns() = runBlocking {
        var now = 1000L
        var requests = 0
        var reject = true
        val repository = BtcMapRepository(File(folder.root, "cache"), { snapshot() }, { "" }, {
            requests++
            if (reject) throw BtcMapResourceLimit()
            if (it.encodedPath.endsWith("/places")) "[]" else place
        }, retryDelay = 0, clock = { now })
        val merchant = repository.load().places.single()
        limit { runBlocking { repository.sync() } }
        limit { runBlocking { repository.detail(merchant) } }
        now = 999
        reject = false
        repository.sync()
        repository.detail(merchant)
        assertEquals(4, requests)
        now = 1001
        repository.sync()
        repository.detail(merchant)
        assertEquals(4, requests)
        // Successful caches must also expire after a backward clock jump.
        now = 998
        repository.sync()
        repository.detail(merchant)
        assertEquals(6, requests)
    }

    @Test fun mergedRecordLimitIsCheckedBeforeWritingOrAdvancingCursor() = runBlocking {
        val places = (1..BtcMapLimits.records).joinToString(",") { place.replace("\"id\":1", "\"id\":$it") }
        val original = snapshot(places)
        val cache = folder.newFile().apply { writeText(original) }
        var requests = 0
        val repository = BtcMapRepository(cache, { error("cache should load") }, { "" }, {
            requests++; "[${place.replace("\"id\":1", "\"id\":100001")}]"
        }, retryDelay = 0)
        val initial = repository.load()
        limit { runBlocking { repository.sync(1000) } }
        assertEquals(1, requests); assertEquals(initial, repository.load()); assertEquals(original, cache.readText())
    }

    @Test fun encodedSizeMatchesFullEncodingIncludingUTF8EscapesAndMetadata() {
        val original = BtcMapSnapshot.parse(snapshot(place.replace("Cafe", "é🚀\\\"\\n")))
            .copy(source = "é\\\"\n", generatedAt = "é\n")
        for (count in listOf(0, 1, 3)) {
            val value = original.copy(places = List(count) { original.places.single() })
            assertEquals(mapJson.encodeToString(value).toByteArray(Charsets.UTF_8).size, BtcMapLimits.encodedSize(value))
        }
    }

    @Test fun mergedByteLimitIsCheckedBeforeWritingOrAdvancingCursor() = runBlocking {
        val large = place.replace("Cafe", "x".repeat(BtcMapLimits.stringBytes))
        fun entries(range: IntRange) = range.joinToString(",") { large.replace("\"id\":1", "\"id\":$it") }
        val original = snapshot(entries(1..2000))
        val cache = folder.newFile().apply { writeText(original) }
        var requests = 0
        val repository = BtcMapRepository(cache, { error("cache should load") }, { "" }, {
            requests++; "[${entries(2001..2100)}]"
        }, retryDelay = 0)
        val initial = repository.load()
        limit { runBlocking { repository.sync(1000) } }
        assertEquals(1, requests); assertEquals(initial, repository.load()); assertEquals(original, cache.readText())
    }
}
