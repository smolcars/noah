package com.noahwallet.api.generated.models

import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents the first response in the LNURL-pay protocol.
 *
 * This response provides the necessary details for a wallet to make a payment,
 * such as the callback URL, sendable amounts, and metadata.
 */
@Serializable
public data class LnurlpDefaultResponse(
  /**
   * The user's Ark address when requested for a compatible Ark server.
   */
  @SerialName("ark")
  public val ark: String? = null,
  /**
   * The URL where the wallet should send the second request.
   */
  @SerialName("callback")
  public val callback: String,
  /**
   * The maximum length of a comment that can be included with the payment.
   */
  @SerialName("commentAllowed")
  public val commentAllowed: Int,
  /**
   * The maximum amount that can be sent in a single payment, in millisatoshis. Unsigned Rust u64; values are below 2^63 in practice for native Int64/Long clients.
   */
  @SerialName("maxSendable")
  public val maxSendable: Long,
  /**
   * A JSON string containing metadata about the payment.
   */
  @SerialName("metadata")
  public val metadata: String,
  /**
   * The minimum amount that can be sent in a single payment, in millisatoshis. Unsigned Rust u64; values are below 2^63 in practice for native Int64/Long clients.
   */
  @SerialName("minSendable")
  public val minSendable: Long,
  /**
   * The LNURL-pay tag, which is always "payRequest".
   */
  @SerialName("tag")
  public val tag: String,
) : LnurlpResponse
