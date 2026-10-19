package com.noahwallet.api.generated.models

import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Defines the payload for updating a user's profile.
 */
@Serializable
public data class UpdateProfilePayload(
  /**
   * The user's optional display name. Null or empty clears the name.
   */
  @SerialName("display_name")
  public val displayName: String? = null,
)
