package com.noahwallet.api.generated.models

import kotlin.Long
import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Defines the query parameters for an LNURL-pay request.
 */
@Serializable
public data class LnurlpRequestQuery(
  /**
   * The amount of the payment in millisatoshis. Unsigned Rust u64; values are below 2^63 in practice for native Int64/Long clients.
   */
  @SerialName("amount")
  public val amount: Long? = null,
  /**
   * The payer's Ark server pubkey, used to negotiate a compatible Ark address.
   */
  @SerialName("ark")
  public val ark: String? = null,
)
