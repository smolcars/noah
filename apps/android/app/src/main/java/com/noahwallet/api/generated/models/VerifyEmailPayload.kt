package com.noahwallet.api.generated.models

import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Defines the payload for verifying an email with a code.
 */
@Serializable
public data class VerifyEmailPayload(
  @SerialName("code")
  public val code: String,
)
