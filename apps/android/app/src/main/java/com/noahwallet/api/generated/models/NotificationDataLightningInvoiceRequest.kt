package com.noahwallet.api.generated.models

import kotlin.Long
import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@SerialName("lightning_invoice_request")
@Serializable
public data class NotificationDataLightningInvoiceRequest(
  /**
   * Unsigned Rust u64; values are below 2^63 in practice for native Int64/Long clients.
   */
  @SerialName("amount")
  public val amount: Long,
  @SerialName("transaction_id")
  public val transactionId: String,
) : NotificationData
