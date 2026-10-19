package com.noahwallet.api.generated.models

import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class AppVersionCheckPayload(
  @SerialName("client_version")
  public val clientVersion: String,
)
