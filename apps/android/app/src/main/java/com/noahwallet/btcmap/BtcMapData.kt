package com.noahwallet.btcmap

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.*

internal val mapJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)
    ?.takeIf { it.isString }?.content?.also(BtcMapLimits::checkString)?.takeIf { it.isNotEmpty() }
internal fun JsonObject.requiredString(key: String) = requireNotNull(string(key)) { """Invalid map data""" }
internal fun JsonElement.number(): Double = (this as JsonPrimitive).let {
    require(!it.isString); requireNotNull(it.doubleOrNull).also { n -> require(n.isFinite()) }
}
internal fun JsonElement.integer(): Long = (this as JsonPrimitive).let {
    require(!it.isString); requireNotNull(it.longOrNull)
}

enum class PlaceCategory(val label: String) {
    all("All"), food("Food & drink"), shop("Shops"), stay("Stay"), atm("ATM"), services("Services");
    companion object {
        fun classify(icon: String): PlaceCategory = when {
            icon in listOf("local_atm", "currency_exchange", "attach_money") -> atm
            listOf("restaurant", "cafe", "dining", "bar", "bakery", "icecream", "liquor", "pizza", "wine").any(icon::contains) -> food
            listOf("storefront", "grocery", "mall", "florist", "gift", "hardware").any(icon::contains) -> shop
            listOf("hotel", "chalet", "camp", "luggage", "bed").any(icon::contains) -> stay
            else -> services
        }
    }
}

@Serializable
data class BtcMapViewport(val center: List<Double>, val zoom: Double) {
    val isValid: Boolean get() = center.size == 2 && center[0] in -180.0..180.0 &&
        center[1] in -90.0..90.0 && zoom in 1.0..19.0
    companion object {
        val initial = BtcMapViewport(listOf(0.0, 20.0), 1.5)
        const val storageKey = "btc-map-viewport-v1"
        fun parse(text: String): BtcMapViewport? = runCatching {
            val raw = mapJson.parseToJsonElement(text).jsonObject
            BtcMapViewport(raw.getValue("center").jsonArray.map { it.number() }, raw.getValue("zoom").number())
                .also { require(it.isValid) }
        }.getOrNull()
    }
}

object BtcMapDistance {
    fun kilometers(from: List<Double>, to: List<Double>): Double {
        val radians = Math.PI / 180
        val a = (sin((to[1] - from[1]) * radians / 2).pow(2) + cos(from[1] * radians) *
            cos(to[1] * radians) * sin((to[0] - from[0]) * radians / 2).pow(2)).coerceIn(0.0, 1.0)
        return 6371 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
    fun format(km: Double, locale: Locale = Locale.getDefault()): String = if (km < 1)
        "${max(1, (km * 1000).roundToInt())} m (${max(1, (km * 1093.61).roundToInt())} yd)"
    else String.format(locale, "%.1f km (%.1f mi)", km, km * 0.621371)
}

@Serializable
data class BtcMapPlace(
    val id: Long, val lat: Double, val lon: Double, val icon: String, val name: String,
    @SerialName("updated_at") val updatedAt: String, val comments: Long? = null, @SerialName("boosted_until") val boostedUntil: String? = null,
    @SerialName("verified_at") val verifiedAt: String? = null,
) {
    val center: List<Double> get() = listOf(lon, lat)
    val category: PlaceCategory get() = PlaceCategory.classify(icon)
    companion object {
        fun parse(raw: JsonObject): BtcMapPlace = BtcMapPlace(
            raw.getValue("id").integer(), raw.getValue("lat").number(), raw.getValue("lon").number(),
            raw.requiredString("icon"), raw.requiredString("name"), raw.requiredString("updated_at"),
            raw["comments"]?.let { runCatching { it.integer() }.getOrNull() }?.takeIf { it >= 0 },
            raw.string("boosted_until"), raw.string("verified_at"),
        ).also { require(it.lat in -90.0..90.0 && it.lon in -180.0..180.0) }
    }
}

@Serializable
private data class BtcMapSnapshotInput(
    val schemaVersion: Int, val generatedAt: String, val source: String,
    @Serializable(with = BtcMapRecordsSerializer::class) val places: List<JsonObject>,
)

internal fun parseMapRecords(text: String): List<JsonObject> {
    BtcMapLimits.checkJson(text)
    return mapJson.decodeFromString(BtcMapRecordsSerializer, text)
}

@Serializable
data class BtcMapSnapshot(val schemaVersion: Int, val generatedAt: String, val source: String, val places: List<BtcMapPlace>) {
    fun merging(patches: List<BtcMapPatch>, at: String): BtcMapSnapshot {
        val merged = places.associateBy { it.id }.toMutableMap()
        patches.forEach { if (it.place == null) merged.remove(it.id) else merged[it.id] = it.place }
        return copy(generatedAt = at, places = merged.values.sortedBy { it.id })
    }
    companion object {
        fun parse(text: String): BtcMapSnapshot {
            BtcMapLimits.checkJson(text)
            val raw = mapJson.decodeFromString<BtcMapSnapshotInput>(text)
            require(raw.schemaVersion == 1 && raw.generatedAt.isNotEmpty() && raw.source.isNotEmpty())
            BtcMapLimits.checkString(raw.generatedAt)
            BtcMapLimits.checkString(raw.source)
            return BtcMapSnapshot(1, raw.generatedAt, raw.source, raw.places.map(BtcMapPlace::parse))
        }
    }
}

data class BtcMapPatch(val id: Long, val place: BtcMapPlace?) {
    companion object {
        fun parse(text: String) = parseMapRecords(text).map { raw ->
            BtcMapPatch(raw.getValue("id").integer(), if (raw.string("deleted_at") != null) null else BtcMapPlace.parse(raw))
        }
    }
}

data class BtcMapComment(val id: Long, val text: String, val createdAt: String)
data class BtcMapDetail(val place: BtcMapPlace, val fields: Map<String, String>, val comments: List<BtcMapComment>) {
    companion object {
        fun parse(place: String, comments: String): BtcMapDetail {
            BtcMapLimits.checkJson(place)
            val raw = mapJson.parseToJsonElement(place).jsonObject
            return BtcMapDetail(BtcMapPlace.parse(raw), raw.keys.mapNotNull { key -> raw.string(key)?.let { key to it } }.toMap(),
                parseMapRecords(comments).map { c ->
                    BtcMapComment(c.getValue("id").integer(), c.requiredString("text"), c.requiredString("created_at"))
                })
        }
    }
}

fun btcMapTimestamp(milliseconds: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    .apply { timeZone = TimeZone.getTimeZone("""UTC""") }.format(Date(milliseconds))
