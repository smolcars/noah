package com.noahwallet.api.generated.models

import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Defines the payload for requesting an email verification code.
 */
@Serializable
public data class SendEmailVerificationPayload(
  @SerialName("email")
  public val email: String,
)
