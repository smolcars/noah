package com.noahwallet.api.generated.models

import kotlin.Long
import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class LightningClaimRequestNotification(
  /**
   * Unsigned Rust u64; values are below 2^63 in practice for native Int64/Long clients.
   */
  @SerialName("amount_sat")
  public val amountSat: Long,
  @SerialName("amount_usd")
  public val amountUsd: String? = null,
  @SerialName("payment_hash")
  public val paymentHash: String,
)
