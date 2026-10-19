package com.noahwallet.api.generated.models

import kotlin.Long
import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Defines the payload for granting mailbox authorization to the server.
 */
@Serializable
public data class AuthorizeMailboxPayload(
  /**
   * Hex-encoded mailbox authorization.
   */
  @SerialName("encoded")
  public val encoded: String,
  /**
   * Authorization expiry as a Unix timestamp in seconds.
   */
  @SerialName("expiry")
  public val expiry: Long,
  /**
   * Ark mailbox identifier scoped to the loaded wallet.
   */
  @SerialName("mailbox_id")
  public val mailboxId: String,
)
