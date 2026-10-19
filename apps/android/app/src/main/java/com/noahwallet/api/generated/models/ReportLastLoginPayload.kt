package com.noahwallet.api.generated.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class ReportLastLoginPayload(
  /**
   * Defines device information reported by the client.
   */
  @SerialName("device_info")
  public val deviceInfo: ReportLastLoginPayloadDeviceInfo? = null,
)
