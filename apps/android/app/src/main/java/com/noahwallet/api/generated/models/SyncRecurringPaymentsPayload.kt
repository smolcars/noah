package com.noahwallet.api.generated.models

import kotlin.collections.List
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Replaces the full set of active recurring payment schedules for the user.
 */
@Serializable
public data class SyncRecurringPaymentsPayload(
  @SerialName("schedules")
  public val schedules: List<RecurringPaymentScheduleEntry>,
)
