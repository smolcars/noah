package com.noahwallet.api.generated.models

import kotlin.Boolean
import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents the response for email verification requests.
 */
@Serializable
public data class EmailVerificationResponse(
  @SerialName("email")
  public val email: String? = null,
  @SerialName("message")
  public val message: String? = null,
  @SerialName("success")
  public val success: Boolean,
)
