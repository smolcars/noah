package com.noahwallet.api.generated.models

import kotlin.String
import kotlin.collections.Map
import kotlinx.serialization.SerialName

public enum class NotificationDataRecurringPaymentDueNotificationType(
  public val `value`: String,
) {
  @SerialName("recurring_payment_due")
  RECURRING_PAYMENT_DUE("recurring_payment_due"),
  ;

  override fun toString(): String = value

  public companion object {
    private val mapping: Map<String, NotificationDataRecurringPaymentDueNotificationType> =
        entries.associateBy(NotificationDataRecurringPaymentDueNotificationType::value)

    public fun fromValue(`value`: String): NotificationDataRecurringPaymentDueNotificationType? = mapping[value]
  }
}
