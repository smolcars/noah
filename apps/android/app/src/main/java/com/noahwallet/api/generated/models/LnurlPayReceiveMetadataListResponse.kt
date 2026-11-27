package com.noahwallet.api.generated.models

import kotlin.collections.List
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class LnurlPayReceiveMetadataListResponse(
  @SerialName("items")
  public val items: List<LnurlPayReceiveMetadata>,
)
