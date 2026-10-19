package com.noahwallet.api.generated.models

import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Defines the payload for registering a push notification token.
 */
@Serializable
public data class RegisterPushToken(
  /**
   * The Expo push token for the user's device.
   */
  @SerialName("push_token")
  public val pushToken: String,
)
