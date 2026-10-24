package com.noahwallet.btcmap

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Owns public map data only; the cache filename and format match RN/iOS. */
class BtcMapRepository(
    private val cache: File,
    private val bundled: () -> String,
    private val citiesAsset: () -> String,
    private val fetch: suspend (HttpUrl) -> String = ::download,
    private val write: (File, String) -> Unit = ::atomicWrite,
    private val retryDelay: Long = 1000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val snapshotLock = Mutex()
    private val syncLock = Mutex()
    private val cityLock = Mutex()
    private val detailLock = Mutex()
    private var snapshot: BtcMapSnapshot? = null
    private var cities: List<CitySearchEntry>? = null
    private var lastSync: Long? = null
    private var oversizedSync: Long? = null
    private val oversizedDetails = mutableMapOf<Long, Long>()
    private val details = mutableMapOf<Long, Pair<Long, BtcMapDetail>>()

    suspend fun load(): BtcMapSnapshot = withContext(Dispatchers.IO) {
        snapshotLock.withLock {
            snapshot ?: (runCatching { BtcMapSnapshot.parse(cache.inputStream().use { BtcMapLimits.read(it) }) }.getOrNull()
                ?: BtcMapSnapshot.parse(bundled())).also { snapshot = it }
        }
    }

    suspend fun sync(now: Long = clock()): BtcMapSnapshot = withContext(Dispatchers.IO) {
        syncLock.withLock {
            if (oversizedSync?.let { now - it in 0 until BtcMapLimits.cooldown } == true) throw BtcMapResourceLimit()
            val current = load()
            if (lastSync?.let { now - it in 0 until 300_000 } == true) return@withLock current
            val result = try { retry {
                val patches = BtcMapPatch.parse(fetch(syncURL(current.generatedAt)))
                val merged = current.merging(patches, btcMapTimestamp(now))
                BtcMapLimits.checkRecords(merged.places.size)
                BtcMapLimits.encodedSize(merged)
                val encoded = mapJson.encodeToString(merged)
                currentCoroutineContext().ensureActive()
                write(cache, encoded)
                merged
            } } catch (limit: BtcMapResourceLimit) { oversizedSync = now; throw limit }
            snapshotLock.withLock { snapshot = result }
            oversizedSync = null
            lastSync = now
            result
        }
    }

    suspend fun searchCities(query: String, bias: List<Double>?): List<CitySearchEntry> = withContext(Dispatchers.Default) {
        val values = cityLock.withLock {
            cities ?: withContext(Dispatchers.IO) { CitySearch.parse(citiesAsset()) }.also { cities = it }
        }
        CitySearch.search(values, query, bias = bias)
    }

    suspend fun detail(place: BtcMapPlace, now: Long = clock()): BtcMapDetail = withContext(Dispatchers.IO) {
        detailLock.withLock {
            if (oversizedDetails[place.id]?.let { now - it in 0 until BtcMapLimits.cooldown } == true) throw BtcMapResourceLimit()
            details[place.id]?.takeIf { now - it.first in 0 until 900_000 }?.let { return@withLock it.second }
            val result = try { retry {
                coroutineScope {
                    val data = async { fetch(url("places/${place.id}").newBuilder().addQueryParameter("fields", detailFields).build()) }
                    val comments = async { if ((place.comments ?: 0) > 0) fetch(url("places/${place.id}/comments")) else "[]" }
                    BtcMapDetail.parse(data.await(), comments.await()).also { require(it.place.id == place.id) }
                }
            } } catch (limit: BtcMapResourceLimit) { oversizedDetails[place.id] = now; throw limit }
            oversizedDetails.remove(place.id)
            details[place.id] = now to result
            result
        }
    }

    private suspend fun <T> retry(block: suspend () -> T): T = try { block() }
    catch (cancel: CancellationException) { throw cancel }
    catch (limit: BtcMapResourceLimit) { throw limit }
    catch (_: Exception) { delay(retryDelay); block() }

    companion object {
        private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        const val placeFields = "id,lat,lon,icon,name,comments,boosted_until,verified_at,updated_at"
        const val detailFields = "$placeFields,address,opening_hours,created_at,osm_id,osm_url,phone,website,twitter,facebook,instagram,telegram,email,required_app_url,description,image,payment_provider,osm:amenity,osm:shop,osm:tourism,osm:cuisine,osm:payment:lightning,osm:payment:lightning_contactless,osm:payment:onchain"
        private fun url(path: String) = "https://api.btcmap.org/v4/$path".toHttpUrl()
        fun syncURL(since: String) = url("places").newBuilder().addQueryParameter("fields", "$placeFields,deleted_at")
            .addQueryParameter("updated_since", since).addQueryParameter("include_deleted", "true").build()

        fun create(context: Context): BtcMapRepository {
            val app = context.applicationContext
            return BtcMapRepository(File(app.filesDir, "btcmap-places-v1.btcmap"),
                { app.assets.open("places.btcmap").use { BtcMapLimits.read(it) } },
                { app.assets.open("cities.geonames").bufferedReader().use { it.readText() } })
        }

        private suspend fun download(url: HttpUrl): String = suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(url).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("""Map request failed"""))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use {
                        if (!it.isSuccessful) throw IOException("""Map request failed""")
                        it.body.byteStream().use { stream -> BtcMapLimits.read(stream, it.body.contentLength()) }
                    } }
                    if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
                }
            })
        }

        internal fun atomicWrite(file: File, value: String) {
            val temp = File.createTempFile("btcmap-", ".tmp", file.parentFile)
            try {
                FileOutputStream(temp).use { it.write(value.toByteArray(Charsets.UTF_8)); it.fd.sync() }
                if (!temp.renameTo(file)) throw IOException("""Map cache could not be saved""")
            } finally { temp.delete() }
        }
    }
}
