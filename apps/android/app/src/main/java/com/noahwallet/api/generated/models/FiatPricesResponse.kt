package com.noahwallet.api.generated.models

import kotlin.Double
import kotlin.Long
import kotlin.String
import kotlin.collections.Map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class FiatPricesResponse(
  @SerialName("rates")
  public val rates: Map<String, Double?>,
  @SerialName("time")
  public val time: Long,
)
