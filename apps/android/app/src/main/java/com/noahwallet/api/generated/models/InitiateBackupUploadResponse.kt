package com.noahwallet.api.generated.models

import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class InitiateBackupUploadResponse(
  @SerialName("backup_id")
  public val backupId: String,
  @SerialName("checksum_sha256")
  public val checksumSha256: String,
  @SerialName("upload_url")
  public val uploadUrl: String,
)
