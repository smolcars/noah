package com.noahwallet.api.generated.models

import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a structured API error response.
 */
@Serializable
public data class ApiErrorResponse(
  /**
   * Stable machine-readable error code.
   */
  @SerialName("code")
  public val code: String,
  /**
   * User-facing error message.
   */
  @SerialName("message")
  public val message: String,
  /**
   * Safe error reason for compatibility.
   */
  @SerialName("reason")
  public val reason: String,
  /**
   * Always "ERROR" for error responses.
   */
  @SerialName("status")
  public val status: String,
)
