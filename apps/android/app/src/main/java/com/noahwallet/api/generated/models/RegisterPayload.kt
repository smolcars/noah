package com.noahwallet.api.generated.models

import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Defines the payload for a user registration request.
 */
@Serializable
public data class RegisterPayload(
  /**
   * Optional Ark address
   */
  @SerialName("ark_address")
  public val arkAddress: String? = null,
  /**
   * Defines device information reported by the client.
   */
  @SerialName("device_info")
  public val deviceInfo: RegisterPayloadDeviceInfo? = null,
  /**
   * User chosen lightning address
   */
  @SerialName("ln_address")
  public val lnAddress: String? = null,
)
