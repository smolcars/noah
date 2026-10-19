package com.noahwallet.api.generated.models

import kotlin.String
import kotlin.collections.Map
import kotlinx.serialization.SerialName

public enum class NotificationDataBackupTriggerNotificationType(
  public val `value`: String,
) {
  @SerialName("backup_trigger")
  BACKUP_TRIGGER("backup_trigger"),
  ;

  override fun toString(): String = value

  public companion object {
    private val mapping: Map<String, NotificationDataBackupTriggerNotificationType> =
        entries.associateBy(NotificationDataBackupTriggerNotificationType::value)

    public fun fromValue(`value`: String): NotificationDataBackupTriggerNotificationType? = mapping[value]
  }
}
