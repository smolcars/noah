package com.noahwallet.services.push

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class ExpoPushToken(private val client: HttpClient) {
    @Serializable
    data class Request(
        val deviceId: String,
        val appId: String,
        val deviceToken: String,
        val type: String = "fcm",
        val development: Boolean = false,
        val projectId: String = PROJECT_ID,
    )
    @Serializable private data class Response(val data: Payload)
    @Serializable private data class Payload(val expoPushToken: String)

    suspend fun fetch(request: Request): String {
        val response = client.post(URL) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(request))
        }
        check(response.status.value in 200..299) { "Expo token exchange failed" }
        return json.decodeFromString<Response>(response.bodyAsText()).data.expoPushToken.also {
            check(it.isNotBlank()) { "Expo returned an empty token" }
        }
    }

    companion object {
        const val PROJECT_ID = "6e79dffb-dcd4-4f3d-b596-638b16377eb0"
        const val URL = "https://exp.host/--/api/v2/push/getExpoPushToken"
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        fun request(installationId: String, appId: String, deviceToken: String) =
            Request(installationId.lowercase(), appId, deviceToken)
    }
}
