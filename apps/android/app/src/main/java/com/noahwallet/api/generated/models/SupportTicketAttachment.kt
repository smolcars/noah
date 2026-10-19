package com.noahwallet.api.generated.models

import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class SupportTicketAttachment(
  @SerialName("base64_data")
  public val base64Data: String,
  @SerialName("content_type")
  public val contentType: String,
  @SerialName("filename")
  public val filename: String,
)
