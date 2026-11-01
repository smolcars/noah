package com.noahwallet.api.generated.models

import kotlin.String
import kotlin.collections.Map
import kotlinx.serialization.SerialName

/**
 * The service that delivers to a raw device token.
 */
public enum class RegisterPushTokenProvider(
  public val `value`: String,
) {
  @SerialName("apns")
  APNS("apns"),
  @SerialName("fcm")
  FCM("fcm"),
  ;

  override fun toString(): String = value

  public companion object {
    private val mapping: Map<String, RegisterPushTokenProvider> =
        entries.associateBy(RegisterPushTokenProvider::value)

    public fun fromValue(`value`: String): RegisterPushTokenProvider? = mapping[value]
  }
}
