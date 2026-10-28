package com.noahwallet.btcmap

import com.noahwallet.fixtures.SharedFixtures
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.Locale

@RunWith(Parameterized::class)
class BtcMapFixtureTest(private val name: String, private val domain: String, private val input: JsonObject, private val expected: JsonElement) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases() = listOf("btc-map", "city-search", "btc-map-review", "btc-map-limits").flatMap { domain ->
            SharedFixtures.load(domain).map { arrayOf(it.name, domain, it.input.jsonObject, it.expected) }
        }
        fun asset(name: String) = requireNotNull(BtcMapFixtureTest::class.java.getResourceAsStream("/$name"))
            .bufferedReader().use { it.readText() }
        val cityText by lazy { asset("cities.geonames") }
        val cities by lazy { CitySearch.parse(cityText) }
    }
    private inline fun <reified T> value(v: T): JsonElement = mapJson.encodeToJsonElement(v)
    private inline fun <reified T> read(key: String): T = mapJson.decodeFromJsonElement(input.getValue(key))
    @Test fun matchesSharedReactNativeFixture() {
        val actual = (if (domain == "btc-map-limits") limits() else operation()) ?: return
        assertEquals(name, canonical(expected), canonical(actual))
    }

    private fun limits(): JsonElement {
        val text = read<String>("text")
        val result = try {
            when (read<String>("operation")) {
                "snapshot" -> BtcMapSnapshot.parse(text)
                "patch" -> BtcMapPatch.parse(text)
                "detail" -> BtcMapDetail.parse(text, "[]")
                "comments" -> BtcMapDetail.parse("""{"id":1,"lat":1,"lon":2,"icon":"cafe","name":"Cafe","updated_at":"today"}""", text)
                else -> error("Unknown limits operation")
            }
            "accepted"
        } catch (_: BtcMapResourceLimit) { "resourceLimit" }
        return value(result)
    }

    /** The operation's result, or null when it asserts by itself. */
    private fun operation(): JsonElement? = when (read<String>("operation")) {
        "web" -> value(BtcMapLinks.web(read("text")))
        "phone" -> value(BtcMapLinks.phone(read("text")))
        "bounds" -> value(read<BtcMapBounds>("bounds").contains(read("lon"), read("lat")))
        "syncTimestamp" -> value(btcMapTimestamp(read("epochMilliseconds")))
        "category" -> value(PlaceCategory.classify(read("icon")).name)
        "distance" -> {
            assertEquals(expected.jsonPrimitive.double, BtcMapDistance.kilometers(read("from"), read("to")), 1e-9)
            null
        }
        "format" -> value(BtcMapDistance.format(read("km"), Locale.US))
        "viewport" -> value(BtcMapViewport.parse(input.getValue("value").toString()))
        "normalize" -> value(CitySearch.normalize(read("value")))
        "parse" -> if (domain == "btc-map") value(runCatching { BtcMapSnapshot.parse(input.getValue("value").toString()) }.getOrNull())
        else value(runCatching { CitySearch.parse(input.getValue("value").toString()) }.isSuccess)
        "merge" -> value(BtcMapSnapshot.parse(input.getValue("snapshot").toString())
            .merging(BtcMapPatch.parse(input.getValue("patches").toString()), read("generatedAt")))
        "search" -> search()
        "bundle" -> if (domain == "btc-map") placesBundle() else citiesBundle()
        else -> error("Unknown operation")
    }

    private fun search() = JsonArray(CitySearch.search(cities, read("query"), read("limit"), input["bias"]?.let { read("bias") }).map { city ->
        buildJsonObject {
            put("id", city.id); put("name", city.name); put("countryCode", city.countryCode)
            put("displayLabel", city.displayLabel); put("secondaryLabel", city.secondaryLabel); put("center", value(city.center))
        }
    })

    private fun placesBundle(): JsonElement {
        val snapshot = BtcMapSnapshot.parse(asset("places.btcmap")); val ids = snapshot.places.map { it.id }
        return buildJsonObject {
            put("count", ids.size); put("unique", ids.toSet().size == ids.size); put("sorted", ids == ids.sorted())
            put("bounded", snapshot.places.all { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 })
            put("schemaVersion", snapshot.schemaVersion); put("source", snapshot.source)
        }
    }

    private fun citiesBundle(): JsonElement {
        val ids = cities.map { it.id }
        return buildJsonObject {
            put("count", ids.size); put("unique", ids.toSet().size == ids.size); put("sorted", ids == ids.sorted())
            put("bounded", cities.all { it.center[0] in -180.0..180.0 && it.center[1] in -90.0..90.0 })
            put("schemaVersion", 1); put("license", "CC BY 4.0"); put("under5MiB", cityText.toByteArray().size < 5 * 1024 * 1024)
            put("hasAtlanta", cities.any { it.name == "Atlanta" && it.countryCode == "US" && it.admin1Code == "GA" })
        }
    }

    private fun canonical(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(element.map(::canonical))
        is JsonPrimitive -> if (!element.isString && element.doubleOrNull != null) JsonPrimitive(element.double) else element
    }
}
