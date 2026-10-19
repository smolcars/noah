package com.noahwallet.api.generated.client

import com.noahwallet.api.generated.models.EmailVerificationResponse
import com.noahwallet.api.generated.models.VerifyEmailPayload
import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.`header`
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.ContentConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException

public class V0EmailVerifyClient(
  private val httpClient: HttpClient,
) {
  /**
   * Verifies the email verification code.
   * Requires a bearer JWT and a registered user (user-exists middleware). Email verification is not required.
   *
   * Parameters:
   * 	 @param verifyEmailPayload
   *
   * Returns:
   * 	[NetworkResult.Success] with [com.noahwallet.api.generated.models.EmailVerificationResponse] if the request was successful.
   * 	[NetworkResult.Failure] with a [NetworkError] if the request failed.
   */
  public suspend fun verifyEmail(verifyEmailPayload: VerifyEmailPayload, apiConfiguration: ApiConfiguration = ApiConfiguration()): NetworkResult<EmailVerificationResponse> {
    val basePath = apiConfiguration.basePath.trimEnd('/')
    val url = basePath + """/v0/email/verify"""

    return try {
      val response = httpClient.post(url) {
        `header`("Accept", "application/json")
        `header`("Content-Type", "application/json")
        setBody(verifyEmailPayload)
        headers {
          apiConfiguration.customHeaders.forEach { (name, value) ->
            remove(name)
            append(name, value)
          }
        }
      }

      if (response.status.isSuccess()) {
        NetworkResult.Success(response.body())
      } else {
        val errorBody = response.bodyAsText().ifBlank { null }
        NetworkResult.Failure(NetworkError.Http(statusCode = response.status.value, statusDescription = response.status.description, body = errorBody))
      }
    } catch (e: ResponseException) {
      val status = e.response.status
      val body = runCatching { e.response.bodyAsText() }.getOrNull()?.ifBlank { null }
      NetworkResult.Failure(NetworkError.Http(status.value, status.description, body))
    } catch (e: IOException) {
      NetworkResult.Failure(NetworkError.Network(e))
    } catch (e: ContentConvertException) {
      NetworkResult.Failure(NetworkError.Serialization(e))
    } catch (e: NoTransformationFoundException) {
      NetworkResult.Failure(NetworkError.Serialization(e))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      NetworkResult.Failure(NetworkError.Unknown(e))
    }
  }
}
