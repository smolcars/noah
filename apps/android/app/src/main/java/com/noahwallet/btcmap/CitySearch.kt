package com.noahwallet.btcmap

import kotlinx.serialization.json.*
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

data class CitySearchEntry(
    val id: Long, val name: String, val center: List<Double>, val countryCode: String,
    val countryName: String, val admin1Code: String, val regionName: String?, val population: Long,
    val normalizedNames: List<String>, val searchTokens: List<String>, val searchText: String,
) {
    val secondaryLabel: String get() = listOfNotNull(regionName, countryName).joinToString(", ")
    val displayLabel: String get() = "$name, ${if (countryCode == "US") admin1Code.ifEmpty { countryName } else regionName ?: countryName}"
}

object CitySearch {
    private val combiningMarks = Regex("[\\u0300-\\u036f]")
    private val nonLetters = Regex("[^\\p{L}\\p{N}]+")
    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKD)
        .replace(combiningMarks, "").lowercase(Locale.ROOT)
        .replace(nonLetters, " ").trim()

    fun parse(text: String): List<CitySearchEntry> {
        val raw = mapJson.parseToJsonElement(text).jsonObject
        require(raw.getValue("schemaVersion").integer() == 1L && raw.requiredString("license") == "CC BY 4.0")
        raw.requiredString("generatedAt")
        fun strings(key: String) = raw.getValue(key).jsonObject.let { obj -> obj.keys.associateWith {
            require(it.isNotEmpty()); obj.requiredString(it)
        } }
        val countries = strings("countries")
        val regions = strings("admin1")
        return raw.getValue("cities").jsonArray.map {
            val c = it.jsonArray
            require(c.size == 8)
            fun str(i: Int) = c[i].jsonPrimitive.also { require(it.isString) }.content
            val name = str(1).also { require(it.isNotEmpty()) }
            val ascii = str(2).ifEmpty { name }
            val lat = c[3].number(); val lon = c[4].number()
            val countryCode = str(5); val admin = str(6); val population = c[7].integer()
            require(lat in -90.0..90.0 && lon in -180.0..180.0 && population >= 0)
            val country = requireNotNull(countries[countryCode])
            val region = regions["$countryCode.$admin"]
            val searchable = normalize(listOf(name, ascii, region ?: "", admin, country, countryCode).joinToString(" "))
            CitySearchEntry(c[0].integer(), name, listOf(lon, lat), countryCode, country, admin, region, population,
                listOf(normalize(name), normalize(ascii)), searchable.split(" "), searchable)
        }
    }

    fun search(cities: List<CitySearchEntry>, query: String, limit: Int = 3, bias: List<Double>? = null): List<CitySearchEntry> {
        val normalized = normalize(query)
        if (normalized.length < 2 || limit <= 0) return emptyList()
        val tokens = normalized.split(" ")
        val collator = Collator.getInstance()
        return cities.mapNotNull { city ->
            val score = when {
                normalized in city.normalizedNames -> 0
                city.normalizedNames.any { it.startsWith(normalized) } -> 1
                tokens.all { token -> city.searchTokens.any { it.startsWith(token) } } -> 2
                city.searchText.contains(normalized) -> 3
                else -> return@mapNotNull null
            }
            Triple(city, score, bias?.let { BtcMapDistance.kilometers(it, city.center) } ?: 0.0)
        }.sortedWith(compareBy<Triple<CitySearchEntry, Int, Double>> { it.second }.thenBy { it.third }
            .thenByDescending { it.first.population }.thenComparator { a, b -> collator.compare(a.first.name, b.first.name) })
            .take(limit).map { it.first }
    }
}
