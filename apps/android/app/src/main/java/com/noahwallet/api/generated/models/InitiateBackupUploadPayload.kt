package com.noahwallet.api.generated.models

import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class InitiateBackupUploadPayload(
  @SerialName("encrypted_sha256")
  public val encryptedSha256: String,
  /**
   * Unsigned Rust u64; values are below 2^63 in practice for native Int64/Long clients.
   */
  @SerialName("encrypted_size")
  public val encryptedSize: Long,
  @SerialName("format_version")
  public val formatVersion: Int,
)
