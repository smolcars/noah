package com.noahwallet.services.server

import com.noahwallet.api.generated.client.NetworkError
import com.noahwallet.api.generated.client.NetworkResult
import org.junit.Assert.*
import org.junit.Test

class ServerFailureTest {
    private fun failure(body: String?): Exception = try {
        NetworkResult.Failure(NetworkError.Http(429, "internal detail", body)).value()
    } catch (failure: Exception) { failure }

    @Test fun actionableStructuredMessagesSurviveButInternalDetailsDoNot() {
        for (message in listOf("Username is already taken", "Too many verification emails. Please try again later.")) {
            val body = """{"status":"ERROR","code":"LIMIT","message":"$message","reason":"private diagnostic"}"""
            assertEquals(message, ServerFailure.message(failure(body), "Try again"))
        }
        for (body in listOf(null, "raw secret", "<html>internal failure</html>", """{"message":"incomplete"}""",
            """{"status":"ERROR","code":"LIMIT","message":" ","reason":"private diagnostic"}""")) {
            assertEquals("Try again", ServerFailure.message(failure(body), "Try again"))
        }
        assertEquals("Try again", ServerFailure.message(IllegalStateException("secret"), "Try again"))
        assertEquals("Try again", ServerFailure.message(ServerFailure("internal detail"), "Try again"))
        assertEquals("Try again", ServerFailure.message(ServerFailure("offline", kind = ServerFailure.Kind.connectivity), "Try again"))
    }
}
