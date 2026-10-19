package com.noahwallet.api.generated.models

import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Defines device information reported by the client.
 */
@Serializable
public data class DeviceInfo(
  @SerialName("app_build")
  public val appBuild: String? = null,
  @SerialName("app_version")
  public val appVersion: String? = null,
  @SerialName("device_manufacturer")
  public val deviceManufacturer: String? = null,
  @SerialName("device_model")
  public val deviceModel: String? = null,
  @SerialName("os_name")
  public val osName: String? = null,
  @SerialName("os_version")
  public val osVersion: String? = null,
)
