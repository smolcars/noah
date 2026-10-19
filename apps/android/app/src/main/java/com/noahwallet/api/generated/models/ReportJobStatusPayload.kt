package com.noahwallet.api.generated.models

import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class ReportJobStatusPayload(
  @SerialName("error_message")
  public val errorMessage: String? = null,
  @SerialName("notification_k1")
  public val notificationK1: String,
  @SerialName("report_type")
  public val reportType: ReportType,
  @SerialName("status")
  public val status: ReportStatus,
)
