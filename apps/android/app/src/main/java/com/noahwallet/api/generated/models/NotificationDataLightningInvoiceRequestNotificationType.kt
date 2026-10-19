package com.noahwallet.api.generated.models

import kotlin.String
import kotlin.collections.Map
import kotlinx.serialization.SerialName

public enum class NotificationDataLightningInvoiceRequestNotificationType(
  public val `value`: String,
) {
  @SerialName("lightning_invoice_request")
  LIGHTNING_INVOICE_REQUEST("lightning_invoice_request"),
  ;

  override fun toString(): String = value

  public companion object {
    private val mapping: Map<String, NotificationDataLightningInvoiceRequestNotificationType> =
        entries.associateBy(NotificationDataLightningInvoiceRequestNotificationType::value)

    public fun fromValue(`value`: String): NotificationDataLightningInvoiceRequestNotificationType? = mapping[value]
  }
}
