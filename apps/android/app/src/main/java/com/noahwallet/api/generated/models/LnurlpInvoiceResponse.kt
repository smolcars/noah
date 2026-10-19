package com.noahwallet.api.generated.models

import kotlin.String
import kotlin.collections.List
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents the second response in the LNURL-pay protocol.
 *
 * This response contains the BOLT11 invoice that the wallet will use to pay.
 */
@Serializable
public data class LnurlpInvoiceResponse(
  @SerialName("ark")
  public val ark: String? = null,
  /**
   * The BOLT11 payment request (invoice).
   */
  @SerialName("pr")
  public val pr: String,
  /**
   * A list of routes for the payment, typically empty.
   */
  @SerialName("routes")
  public val routes: List<String>,
) : LnurlpResponse
