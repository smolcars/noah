package com.noahwallet.services.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit

/** RN normalizeEsploraEndpoint and network validation; cases live in fixtures/esplora.json. */
object EsploraEndpoint {
    class Failure(message: String) : Exception(message)
    fun normalize(value: String): String {
        val trimmed = value.trim().filterNot { it == '\t' || it == '\n' || it == '\r' }.replace('\\', '/')
        if (trimmed.isEmpty()) throw Failure("Enter an Esplora API endpoint.")
        val input = if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) trimmed else "https://$trimmed"
        if (!Regex("^https?://[^/]", RegexOption.IGNORE_CASE).containsMatchIn(input) || Regex("%(?![0-9a-fA-F]{2})").containsMatchIn(input))
            throw Failure("Enter a valid Esplora API URL.")
        val authority = input.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        if ('@' in authority) throw Failure("The Esplora endpoint cannot include credentials.")
        val url = input.toHttpUrlOrNull() ?: throw Failure("Enter a valid Esplora API URL.")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) throw Failure("The Esplora endpoint cannot include credentials.")
        if (!url.query.isNullOrEmpty() || !url.fragment.isNullOrEmpty()) throw Failure("The Esplora endpoint cannot include a query string or fragment.")
        var path = url.encodedPath.trimEnd('/')
        listOf("/blocks/tip/height", "/blocks/tip/hash", "/block-height/0").firstOrNull { path.endsWith(it) }?.let { path = path.dropLast(it.length) }
        return url.newBuilder().host(ipv4Host(url.host)).encodedPath(path.ifEmpty { "/" }).build().toString().trimEnd('/')
    }
    /** WHATWG IPv4 numbers include shortened, hex and octal forms. */
    private fun ipv4Host(host: String): String {
        val parts = host.removeSuffix(".").split('.')
        fun number(part: String): Long? = when {
            part.startsWith("0x", ignoreCase = true) -> part.drop(2).ifEmpty { "0" }.toLongOrNull(16)
            part.length > 1 && part.startsWith('0') -> part.drop(1).toLongOrNull(8)
            else -> part.toLongOrNull()
        }?.takeIf { it >= 0 }
        if (number(parts.last()) == null && !Regex("[0-9]+").matches(parts.last())) return host
        val numbers = parts.mapNotNull(::number)
        if (numbers.size != parts.size || numbers.size !in 1..4 || numbers.dropLast(1).any { it > 255 } ||
            numbers.last() >= (1L shl (8 * (5 - numbers.size)))) throw Failure("Enter a valid Esplora API URL.")
        var address = numbers.last()
        numbers.dropLast(1).forEachIndexed { index, value -> address += value shl (8 * (3 - index)) }
        return listOf(24, 16, 8, 0).joinToString(".") { ((address shr it) and 255).toString() }
    }
    fun validateGenesis(value: String, variant: String) {
        if (variant == "regtest") throw Failure("Regtest wallets use Bitcoin Core instead of Esplora.")
        val hash = value.trim().lowercase()
        if (!Regex("[0-9a-f]{64}").matches(hash)) throw Failure("The endpoint returned an invalid block hash.")
        val expected = when (variant) {
            "mainnet" -> "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f"
            "signet" -> "00000008819873e925422c1ff0f99f7cc9bbb232af63a077a480a3633bee1ef6"
            else -> throw Failure("The Esplora endpoint is for a different Bitcoin network.")
        }
        if (hash != expected) throw Failure("The Esplora endpoint is for a different Bitcoin network.")
    }
    fun validateTip(value: String) {
        val height = value.trim()
        if (!Regex("[0-9]+").matches(height) || height.toUIntOrNull() == null) throw Failure("The endpoint returned an invalid block height.")
    }
    suspend fun validate(value: String, variant: String, fetch: suspend (String) -> String = ::fetch): String {
        val endpoint = normalize(value)
        if (variant == "regtest") throw Failure("Regtest wallets use Bitcoin Core instead of Esplora.")
        validateGenesis(fetch("$endpoint/block-height/0"), variant)
        validateTip(fetch("$endpoint/blocks/tip/height"))
        return endpoint
    }
    private val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw Failure("""HTTP failure""")
                val source = checkNotNull(response.body).source()
                source.request(1025)
                check(source.buffer.size < 1025)
                source.readUtf8()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { throw Failure("Unable to reach the Esplora endpoint. Check the URL and try again.") }
    }
}
