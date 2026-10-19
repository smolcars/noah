package com.noahwallet.api.generated.models

import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents the response for a user's information.
 */
@Serializable
public data class UserInfoResponse(
  /**
   * The user's optional display name.
   */
  @SerialName("display_name")
  public val displayName: String? = null,
  /**
   * The user's optional emergency email address.
   */
  @SerialName("email")
  public val email: String? = null,
  /**
   * The user's lightning address.
   */
  @SerialName("lightning_address")
  public val lightningAddress: String,
  /**
   * The user's Nostr public key in canonical lowercase hex format.
   */
  @SerialName("nostr_pubkey")
  public val nostrPubkey: String? = null,
  @SerialName("user_status")
  public val userStatus: UserStatus,
)
