package com.noahwallet.api.generated.models

import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class SubmitSupportTicketResponse(
  @SerialName("ticket_id")
  public val ticketId: String,
  @SerialName("ticket_number")
  public val ticketNumber: String? = null,
)
