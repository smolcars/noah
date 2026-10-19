package com.noahwallet.fixtures

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Serializable
data class FixtureCase(val name: String, val input: JsonElement, val expected: JsonElement)

@Serializable
private data class FixtureFile(val cases: List<FixtureCase>)

object SharedFixtures {
    fun load(domain: String): List<FixtureCase> {
        val stream = requireNotNull(javaClass.getResourceAsStream("/$domain.json")) {
            "Missing shared fixture: $domain.json"
        }
        return stream.bufferedReader(Charsets.UTF_8).use {
            Json.decodeFromString<FixtureFile>(it.readText()).cases
        }
    }
}
