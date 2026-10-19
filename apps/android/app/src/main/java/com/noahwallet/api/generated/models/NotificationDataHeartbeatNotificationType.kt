package com.noahwallet.api.generated.models

import kotlin.String
import kotlin.collections.Map
import kotlinx.serialization.SerialName

public enum class NotificationDataHeartbeatNotificationType(
  public val `value`: String,
) {
  @SerialName("heartbeat")
  HEARTBEAT("heartbeat"),
  ;

  override fun toString(): String = value

  public companion object {
    private val mapping: Map<String, NotificationDataHeartbeatNotificationType> =
        entries.associateBy(NotificationDataHeartbeatNotificationType::value)

    public fun fromValue(`value`: String): NotificationDataHeartbeatNotificationType? = mapping[value]
  }
}
