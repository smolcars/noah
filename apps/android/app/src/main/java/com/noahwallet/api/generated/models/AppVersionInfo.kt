package com.noahwallet.api.generated.models

import kotlin.Boolean
import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class AppVersionInfo(
  @SerialName("minimum_required_version")
  public val minimumRequiredVersion: String,
  @SerialName("update_required")
  public val updateRequired: Boolean,
)
