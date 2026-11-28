package com.noahwallet.api.generated.models

import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One rail in `paymentOptions` (lnurl/luds#303).
 */
@Serializable
public data class LnurlPayOption(
  /**
   * The value a wallet sends to the callback as `paymentOption`.
   */
  @SerialName("id")
  public val id: String,
  /**
   * The payment method: `lightning` or `bark`.
   */
  @SerialName("type")
  public val type: String,
)
