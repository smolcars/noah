package com.noahwallet.api.generated.models

import kotlin.String
import kotlin.collections.Map
import kotlinx.serialization.SerialName

public enum class NotificationDataLightningClaimRequestNotificationType(
  public val `value`: String,
) {
  @SerialName("lightning_claim_request")
  LIGHTNING_CLAIM_REQUEST("lightning_claim_request"),
  ;

  override fun toString(): String = value

  public companion object {
    private val mapping: Map<String, NotificationDataLightningClaimRequestNotificationType> =
        entries.associateBy(NotificationDataLightningClaimRequestNotificationType::value)

    public fun fromValue(`value`: String): NotificationDataLightningClaimRequestNotificationType? = mapping[value]
  }
}
