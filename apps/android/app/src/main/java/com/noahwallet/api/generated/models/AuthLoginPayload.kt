package com.noahwallet.api.generated.models

import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class AuthLoginPayload(
  @SerialName("k1")
  public val k1: String,
  @SerialName("key")
  public val key: String,
  @SerialName("sig")
  public val sig: String,
)
