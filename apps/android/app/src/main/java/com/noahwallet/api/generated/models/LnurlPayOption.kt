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
   * For `bark`, the Ark server's public key (compressed, lowercase hex), so a wallet can tell
   * whether it can pay this option before calling back. Noah's extension, proposed on
   * lnurl/luds#303.
   */
  @SerialName("operator")
  public val `operator`: String? = null,
  /**
   * The payment method: `lightning` or `bark`.
   */
  @SerialName("type")
  public val type: String,
)
