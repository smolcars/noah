package com.noahwallet.api.generated.models

import kotlin.Long
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Silent push asking the device to execute its locally stored recurring
 * payments that are due. Carries no amounts or recipients: the device checks
 * its own schedules and only pays within the policy the user approved.
 */
@SerialName("recurring_payment_due")
@Serializable
public data class NotificationDataRecurringPaymentDue(
  @SerialName("due_count")
  public val dueCount: Long,
) : NotificationData
