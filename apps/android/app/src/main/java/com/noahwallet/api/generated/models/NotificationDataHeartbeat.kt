package com.noahwallet.api.generated.models

import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@SerialName("heartbeat")
@Serializable
public data class NotificationDataHeartbeat(
  @SerialName("notification_id")
  public val notificationId: String,
) : NotificationData
