package com.noahwallet.api.generated.models

import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class BackupObjectInfo(
  @SerialName("backup_id")
  public val backupId: String,
  @SerialName("created_at")
  public val createdAt: String,
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
