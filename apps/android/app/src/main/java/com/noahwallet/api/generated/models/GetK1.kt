package com.noahwallet.api.generated.models

import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents the response for a `k1` request, used in LNURL-auth.
 */
@Serializable
public data class GetK1(
  /**
   * A unique, single-use secret for the authentication process.
   */
  @SerialName("k1")
  public val k1: String,
  /**
   * The LNURL-auth tag, which is always "login".
   */
  @SerialName("tag")
  public val tag: String,
)
