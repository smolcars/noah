package com.noahwallet.api.generated.models

import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * LUD-18 identity a payer sent with an LNURL-pay callback. Payer-provided and unverified.
 */
@Serializable
public data class LnurlPayReceiveMetadataPayerData(
  /**
   * The payer's Lightning address, normalized to lowercase.
   */
  @SerialName("identifier")
  public val identifier: String? = null,
  /**
   * The payer's name.
   */
  @SerialName("name")
  public val name: String? = null,
)
