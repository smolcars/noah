package com.noahwallet.api.generated.models

import kotlin.Int
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class GetDownloadUrlPayload(
  @SerialName("backup_version")
  public val backupVersion: Int? = null,
)
