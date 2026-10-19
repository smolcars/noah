package com.noahwallet.api.generated.models

import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class UploadUrlResponse(
  @SerialName("s3_key")
  public val s3Key: String,
  @SerialName("upload_url")
  public val uploadUrl: String,
)
