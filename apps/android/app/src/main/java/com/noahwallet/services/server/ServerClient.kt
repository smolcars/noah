package com.noahwallet.services.server

import com.noahwallet.api.generated.PublicOperations
import com.noahwallet.api.generated.client.*
import com.noahwallet.api.generated.models.*
import com.noahwallet.services.wallet.WalletConfiguration
import com.noahwallet.services.storage.StoredMnemonic
import io.github.niteshbalusu11.bark.derivePublicKeyFromMnemonic
import io.github.niteshbalusu11.bark.signMessageWithMnemonic
import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

class ServerFailure(message: String, val status: Int? = null, val code: String? = null,
                    val kind: Kind = Kind.request, val serverMessage: String? = null) : Exception(message) {
    enum class Kind { request, connectivity }

    companion object {
        fun message(failure: Exception, fallback: String): String =
            (failure as? ServerFailure)?.serverMessage?.takeIf { it.isNotBlank() } ?: fallback
    }
}

object ServerEndpoint {
    fun priceURLs(base: String): Pair<String, String> = base.trimEnd('/').let {
        "$it/v0/prices" to "$it/v0/historical-price"
    }

    fun baseURL(variant: String, regtestOverride: String? = null): String = when (variant) {
        "mainnet" -> "https://noahwallet.io"
        "signet" -> "https://signet.noahwallet.io"
        "regtest" -> regtestOverride ?: "http://10.0.2.2:3000"
        else -> error("""Unknown app variant""")
    }
}

object ServerClient {
    val json = Json { ignoreUnknownKeys = true }
    val retryCodes = setOf("AUTH_REQUIRED", "INVALID_TOKEN", "TOKEN_EXPIRED")

    fun isPublic(method: String, path: String): Boolean = PublicOperations.routes.any { route ->
        val (verb, template) = route.split(' ', limit = 2)
        verb == method && template.split('/').let { parts ->
            val actual = path.split('/')
            parts.size == actual.size && parts.zip(actual).all { (part, value) ->
                if (part.startsWith('{')) value.isNotEmpty() else part == value
            }
        }
    }

    fun make(baseURL: String, tokens: AuthTokenProvider? = null,
             engine: HttpClientEngine = OkHttp.create()): HttpClient {
        val origin = Url(baseURL)
        val client = HttpClient(engine) {
            // Never replay a bearer or signed login body to a redirect target.
            followRedirects = false
            install(ContentNegotiation) { json(ServerClient.json) }
            install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 30_000 }
            defaultRequest { url(baseURL) }
        }
        if (tokens != null) client.plugin(HttpSend).intercept { request ->
            check(request.url.protocol == origin.protocol && request.url.host == origin.host && request.url.port == origin.port) {
                "Server client cannot send credentials to another origin"
            }
            if (isPublic(request.method.value, request.url.encodedPath)) {
                request.headers.remove(HttpHeaders.Authorization)
                return@intercept execute(request)
            }
            val token = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { tokens.token() }
            request.headers[HttpHeaders.Authorization] = "Bearer $token"
            val call = execute(request)
            if (call.response.status != HttpStatusCode.Unauthorized) return@intercept call
            val code = runCatching { json.decodeFromString<ApiErrorResponse>(call.response.bodyAsText()).code }.getOrNull()
            if (code !in retryCodes) return@intercept call
            request.headers[HttpHeaders.Authorization] = "Bearer ${kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { tokens.token(forceRefresh = true, rejectedToken = token) }}"
            execute(request)
        }
        return client
    }

    /** The offline login key (`m/350/0`) the server puts in the token's `sub`. */
    fun loginKey(variant: String, mnemonic: String,
                 derive: (String) -> String = { derivePublicKeyFromMnemonic(it, WalletConfiguration.base(variant).network, 0u) }): String =
        derive(requireNotNull(StoredMnemonic.normalize(mnemonic)) { "Invalid wallet seed" })

    fun signChallenge(challenge: String, variant: String, mnemonic: String,
                      sign: (String) -> String = { signMessageWithMnemonic(challenge, it, WalletConfiguration.base(variant).network, 0u) }): String =
        sign(requireNotNull(StoredMnemonic.normalize(mnemonic)) { "Invalid wallet seed" })

    suspend fun login(client: HttpClient, variant: String, mnemonic: String): String {
        val k1 = V0Getk1Client(client).getK1().value().k1
        val key = loginKey(variant, mnemonic)
        val sig = signChallenge(k1, variant, mnemonic)
        return V0AuthLoginClient(client).authLogin(AuthLoginPayload(k1 = k1, key = key, sig = sig)).value().accessToken
    }
}

/** Keep generated errors (which can contain headers, bodies and secrets) behind this boundary. */
fun <T> NetworkResult<T>.value(): T = when (this) {
    is NetworkResult.Success -> data
    is NetworkResult.Failure -> {
        if (error is NetworkError.Network) throw ServerFailure("""Server connection failed""", kind = ServerFailure.Kind.connectivity)
        val http = error as? NetworkError.Http
        val body = http?.body?.let { runCatching { ServerClient.json.decodeFromString<ApiErrorResponse>(it) }.getOrNull() }
        throw ServerFailure(body?.message ?: "Server request failed", http?.statusCode, body?.code, serverMessage = body?.message)
    }
}
