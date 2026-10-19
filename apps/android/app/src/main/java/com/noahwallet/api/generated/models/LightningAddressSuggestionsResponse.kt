package com.noahwallet.api.generated.models

import kotlin.String
import kotlin.collections.List
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents autocomplete suggestions for lightning addresses.
 */
@Serializable
public data class LightningAddressSuggestionsResponse(
  /**
   * Ordered suggestion list.
   */
  @SerialName("suggestions")
  public val suggestions: List<String>,
)
