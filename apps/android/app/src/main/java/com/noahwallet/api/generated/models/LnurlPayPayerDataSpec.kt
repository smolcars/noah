package com.noahwallet.api.generated.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The LUD-18 fields Noah accepts; neither is mandatory.
 */
@Serializable
public data class LnurlPayPayerDataSpec(
  @SerialName("identifier")
  public val identifier: LnurlPayPayerDataFieldSpec,
  @SerialName("name")
  public val name: LnurlPayPayerDataFieldSpec,
)
