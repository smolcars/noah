package com.noahwallet.api.generated.models

import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Silent push telling the device that a Lightning payment it sent has finished,
 * so it can check its own pending send and record the result. Carries only a
 * random id: no payment hash, amount, outcome or preimage. The id also keeps
 * two pushes from being merged into one job.
 */
@Serializable
public data class LightningSendFinishedNotification(
  @SerialName("notification_id")
  public val notificationId: String,
)
