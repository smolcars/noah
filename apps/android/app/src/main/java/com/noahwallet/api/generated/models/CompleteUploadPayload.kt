package com.noahwallet.api.generated.models

import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class CompleteUploadPayload(
  /**
   * Unsigned Rust u64; values are below 2^63 in practice for native Int64/Long clients.
   */
  @SerialName("backup_size")
  public val backupSize: Long,
  @SerialName("backup_version")
  public val backupVersion: Int,
  @SerialName("s3_key")
  public val s3Key: String,
)
