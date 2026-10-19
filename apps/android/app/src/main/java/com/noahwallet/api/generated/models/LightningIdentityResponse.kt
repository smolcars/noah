package com.noahwallet.api.generated.models

import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a configured hosted Lightning identity.
 */
@Serializable
public data class LightningIdentityResponse(
  /**
   * The hosted Lightning address and optional NIP-05 identifier.
   */
  @SerialName("lightning_address")
  public val lightningAddress: String,
  /**
   * The optional Nostr public key in canonical lowercase hex format.
   */
  @SerialName("nostr_pubkey")
  public val nostrPubkey: String? = null,
)
