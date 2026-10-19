package com.noahwallet.api.generated.models

import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Defines the payload for submitting a BOLT11 invoice.
 */
@Serializable
public data class SubmitInvoicePayload(
  /**
   * The BOLT11 invoice to be paid.
   */
  @SerialName("invoice")
  public val invoice: String,
  /**
   * The unique identifier for the payment transaction.
   */
  @SerialName("transaction_id")
  public val transactionId: String,
)
