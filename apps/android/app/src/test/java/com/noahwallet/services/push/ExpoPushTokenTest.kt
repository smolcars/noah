package com.noahwallet.services.push

import com.noahwallet.services.server.ServerClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExpoPushTokenTest {
    @Test fun requestPreservesExpoIdentityAndResponseShape() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(ExpoPushToken.URL, request.url.toString())
            assertEquals(HttpMethod.Post, request.method)
            assertNull(request.headers[HttpHeaders.Authorization])
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals(setOf("type", "deviceId", "development", "appId", "deviceToken", "projectId"), body.keys)
            assertEquals("fcm", body["type"]!!.jsonPrimitive.content)
            assertEquals("aabb-uuid", body["deviceId"]!!.jsonPrimitive.content)
            assertEquals("com.noahwallet.regtest", body["appId"]!!.jsonPrimitive.content)
            assertEquals("fcm-test-token", body["deviceToken"]!!.jsonPrimitive.content)
            assertFalse(body["development"]!!.jsonPrimitive.boolean)
            assertEquals(ExpoPushToken.PROJECT_ID, body["projectId"]!!.jsonPrimitive.content)
            respond("""{"data":{"expoPushToken":"ExponentPushToken[test]"}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        ServerClient.make("https://exp.host", engine = engine).use { client ->
            assertEquals("ExponentPushToken[test]", ExpoPushToken(client).fetch(ExpoPushToken.request("AABB-UUID", "com.noahwallet.regtest", "fcm-test-token")))
        }
    }
    @Test fun rejectedAndEmptyResponsesFail() = runBlocking {
        for ((status, body) in listOf(HttpStatusCode.BadRequest to "{}", HttpStatusCode.OK to """{"data":{"expoPushToken":""}}""")) {
            ServerClient.make("https://exp.host", engine = MockEngine { respond(body, status) }).use {
                assertTrue(runCatching { ExpoPushToken(it).fetch(ExpoPushToken.request("uuid", "app", "token")) }.isFailure)
            }
        }
    }
}
