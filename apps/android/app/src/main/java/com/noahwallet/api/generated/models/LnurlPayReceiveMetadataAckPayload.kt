package com.noahwallet.api.generated.models

import kotlin.String
import kotlin.collections.List
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class LnurlPayReceiveMetadataAckPayload(
  /**
   * Identifiers from the list response, at most 100.
   */
  @SerialName("ids")
  public val ids: List<String>,
)
