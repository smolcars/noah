package com.noahwallet.api.generated.models

import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The callback response for a `paymentOption` paid without an invoice (lnurl/luds#303).
 */
@Serializable
public data class LnurlpDestinationResponse(
  /**
   * Where to pay: for `second`, the user's Ark address.
   */
  @SerialName("paymentDestination")
  public val paymentDestination: String,
  /**
   * The `paymentOption` the wallet chose.
   */
  @SerialName("paymentOption")
  public val paymentOption: String,
) : LnurlpResponse
