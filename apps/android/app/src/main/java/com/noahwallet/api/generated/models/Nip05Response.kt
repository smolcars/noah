package com.noahwallet.api.generated.models

import kotlin.String
import kotlin.collections.Map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class Nip05Response(
  @SerialName("names")
  public val names: Map<String, String?>,
)
