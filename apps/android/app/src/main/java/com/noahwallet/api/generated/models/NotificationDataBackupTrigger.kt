package com.noahwallet.api.generated.models

import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@SerialName("backup_trigger")
@Serializable
public data class NotificationDataBackupTrigger(
  @SerialName("notification_k1")
  public val notificationK1: String,
) : NotificationData
