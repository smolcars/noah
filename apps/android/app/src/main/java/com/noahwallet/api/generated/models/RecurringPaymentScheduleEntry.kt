package com.noahwallet.api.generated.models

import kotlin.Long
import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One active recurring payment schedule, as known to the server.
 */
@Serializable
public data class RecurringPaymentScheduleEntry(
  /**
   * Next due time as a unix timestamp in seconds.
   */
  @SerialName("next_run_at")
  public val nextRunAt: Long,
  /**
   * Opaque, client-generated id (`[A-Za-z0-9_-]{1,64}`).
   */
  @SerialName("schedule_id")
  public val scheduleId: String,
)
