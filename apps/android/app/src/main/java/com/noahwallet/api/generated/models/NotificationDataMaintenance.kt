package com.noahwallet.api.generated.models

import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@SerialName("maintenance")
@Serializable
public data class NotificationDataMaintenance(
  @SerialName("notification_k1")
  public val notificationK1: String,
) : NotificationData
