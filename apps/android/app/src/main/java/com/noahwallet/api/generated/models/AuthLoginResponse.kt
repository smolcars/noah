package com.noahwallet.api.generated.models

import kotlin.Long
import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class AuthLoginResponse(
  @SerialName("access_token")
  public val accessToken: String,
  @SerialName("expires_at")
  public val expiresAt: String,
  /**
   * Unsigned Rust u64; values are below 2^63 in practice for native Int64/Long clients.
   */
  @SerialName("expires_in_seconds")
  public val expiresInSeconds: Long,
  @SerialName("token_type")
  public val tokenType: String,
)
