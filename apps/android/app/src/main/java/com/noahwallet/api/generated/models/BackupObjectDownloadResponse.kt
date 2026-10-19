package com.noahwallet.api.generated.models

import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class BackupObjectDownloadResponse(
  @SerialName("backup")
  public val backup: BackupObjectInfo,
  @SerialName("download_url")
  public val downloadUrl: String,
)
