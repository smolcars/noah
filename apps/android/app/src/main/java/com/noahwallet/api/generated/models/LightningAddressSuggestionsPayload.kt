package com.noahwallet.api.generated.models

import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Defines the payload for querying lightning address suggestions.
 */
@Serializable
public data class LightningAddressSuggestionsPayload(
  /**
   * Partial lightning address typed in the send screen.
   */
  @SerialName("query")
  public val query: String,
)
