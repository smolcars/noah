package com.noahwallet.api.generated.models

import kotlin.Boolean
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class BackupSettingsPayload(
  @SerialName("backup_enabled")
  public val backupEnabled: Boolean,
)
