package com.noahwallet.api.generated.models

import kotlin.Double
import kotlin.Long
import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class HistoricalFiatPriceResponse(
  @SerialName("currency")
  public val currency: String,
  @SerialName("rate")
  public val rate: Double,
  @SerialName("time")
  public val time: Long,
)
