package com.noahwallet.api.generated.models

import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Defines the payload for atomically configuring a hosted Lightning identity.
 */
@Serializable
public data class UpdateLightningIdentityPayload(
  /**
   * An optional Nostr public key encoded as npub. Null disables NIP-05.
   */
  @SerialName("nostr_pubkey")
  public val nostrPubkey: String? = null,
  /**
   * The local part of the user's hosted Lightning address and optional NIP-05 identifier.
   */
  @SerialName("username")
  public val username: String,
)
