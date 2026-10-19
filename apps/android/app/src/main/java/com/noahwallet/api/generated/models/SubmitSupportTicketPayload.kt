package com.noahwallet.api.generated.models

import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class SubmitSupportTicketPayload(
  @SerialName("attachment")
  public val attachment: SubmitSupportTicketPayloadAttachment? = null,
  @SerialName("body")
  public val body: String,
  /**
   * Defines device information reported by the client.
   */
  @SerialName("device_info")
  public val deviceInfo: SubmitSupportTicketPayloadDeviceInfo? = null,
  @SerialName("email")
  public val email: String? = null,
  @SerialName("name")
  public val name: String,
  @SerialName("subject")
  public val subject: String,
)
