package com.noahwallet.api.generated.models

import kotlin.String
import kotlin.collections.Map
import kotlinx.serialization.SerialName

public enum class NotificationDataLightningSendFinishedNotificationType(
  public val `value`: String,
) {
  @SerialName("lightning_send_finished")
  LIGHTNING_SEND_FINISHED("lightning_send_finished"),
  ;

  override fun toString(): String = value

  public companion object {
    private val mapping: Map<String, NotificationDataLightningSendFinishedNotificationType> =
        entries.associateBy(NotificationDataLightningSendFinishedNotificationType::value)

    public fun fromValue(`value`: String): NotificationDataLightningSendFinishedNotificationType? = mapping[value]
  }
}
