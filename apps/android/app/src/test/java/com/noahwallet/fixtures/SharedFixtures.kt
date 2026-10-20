package com.noahwallet.fixtures

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Serializable
data class FixtureCase(val name: String, val input: JsonElement, val expected: JsonElement)

@Serializable
private data class FixtureFile(val cases: List<FixtureCase>)

object SharedFixtures {
    fun document(domain: String): JsonElement = requireNotNull(javaClass.getResourceAsStream("/$domain.json"))
        .bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()) }

    fun load(domain: String): List<FixtureCase> {
        val stream = requireNotNull(javaClass.getResourceAsStream("/$domain.json")) {
            "Missing shared fixture: $domain.json"
        }
        return stream.bufferedReader(Charsets.UTF_8).use {
            Json { ignoreUnknownKeys = true }.decodeFromString<FixtureFile>(it.readText()).cases
        }
    }
}
