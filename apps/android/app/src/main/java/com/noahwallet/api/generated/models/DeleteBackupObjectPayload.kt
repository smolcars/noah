package com.noahwallet.api.generated.models

import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class DeleteBackupObjectPayload(
  @SerialName("backup_id")
  public val backupId: String,
)
