package com.noahwallet.api.generated.models

import kotlin.String
import kotlin.collections.Map
import kotlinx.serialization.SerialName

public enum class NotificationDataMaintenanceNotificationType(
  public val `value`: String,
) {
  @SerialName("maintenance")
  MAINTENANCE("maintenance"),
  ;

  override fun toString(): String = value

  public companion object {
    private val mapping: Map<String, NotificationDataMaintenanceNotificationType> =
        entries.associateBy(NotificationDataMaintenanceNotificationType::value)

    public fun fromValue(`value`: String): NotificationDataMaintenanceNotificationType? = mapping[value]
  }
}
