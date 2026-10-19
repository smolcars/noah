package com.noahwallet.api.generated.models

import kotlin.Long
import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class HistoricalFiatPricePayload(
  @SerialName("currency")
  public val currency: String,
  @SerialName("timestamp")
  public val timestamp: Long,
)
