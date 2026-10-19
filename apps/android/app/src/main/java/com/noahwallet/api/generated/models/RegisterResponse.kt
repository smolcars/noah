package com.noahwallet.api.generated.models

import kotlin.Boolean
import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents the response for an user registration.
 */
@Serializable
public data class RegisterResponse(
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
   * Represents events that can occur during LNURL-auth.
   */
  @SerialName("event")
  public val event: RegisterResponseEvent? = null,
  /**
   * Whether the user's email is verified.
   */
  @SerialName("is_email_verified")
  public val isEmailVerified: Boolean,
  /**
   * The user's lightning address.
   */
  @SerialName("lightning_address")
  public val lightningAddress: String? = null,
  /**
   * The user's Nostr public key in canonical lowercase hex format.
   */
  @SerialName("nostr_pubkey")
  public val nostrPubkey: String? = null,
  /**
   * An optional reason for an error, if one occurred.
   */
  @SerialName("reason")
  public val reason: String? = null,
  /**
   * The status of the request, either "OK" or "ERROR".
   */
  @SerialName("status")
  public val status: String,
  @SerialName("user_status")
  public val userStatus: UserStatus,
)
