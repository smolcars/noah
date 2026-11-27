package com.noahwallet.api.generated.models

import kotlin.Long
import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Payer data and comment for one settled LNURL-pay receive.
 */
@Serializable
public data class LnurlPayReceiveMetadata(
  /**
   * The amount the recipient received. Unsigned Rust u64; values are below 2^63 in practice for native Int64/Long clients.
   */
  @SerialName("amount_sat")
  public val amountSat: Long,
  /**
   * The payer's LUD-12 comment.
   */
  @SerialName("comment")
  public val comment: String? = null,
  /**
   * Identifier to acknowledge once the metadata is stored with the movement.
   */
  @SerialName("id")
  public val id: String,
  /**
   * LUD-18 identity a payer sent with an LNURL-pay callback. Payer-provided and unverified.
   */
  @SerialName("payer_data")
  public val payerData: LnurlPayReceiveMetadataPayerData? = null,
  /**
   * The wallet movement a receive's metadata belongs to.
   */
  @SerialName("target")
  public val target: JsonElement,
)
