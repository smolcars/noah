package com.noahwallet.api.generated.models

import kotlin.Boolean
import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Defines the payload for registering a push notification token.
 */
@Serializable
public data class RegisterPushToken(
  /**
   * Whether an APNs token belongs to the sandbox environment. Defaults to false.
   */
  @SerialName("apns_sandbox")
  public val apnsSandbox: Boolean? = null,
  /**
   * The service that delivers to a raw device token.
   */
  @SerialName("provider")
  public val provider: RegisterPushTokenProvider? = null,
  /**
   * A raw APNs or FCM device token when `provider` is set. Otherwise an Expo push
   * token or a UnifiedPush endpoint.
   */
  @SerialName("push_token")
  public val pushToken: String,
)
