package com.noahwallet.api.generated.models

import kotlin.String
import kotlin.collections.Map
import kotlinx.serialization.SerialName

public enum class ReportStatus(
  public val `value`: String,
) {
  @SerialName("pending")
  PENDING("pending"),
  @SerialName("success")
  SUCCESS("success"),
  @SerialName("failure")
  FAILURE("failure"),
  @SerialName("timeout")
  TIMEOUT("timeout"),
  ;

  override fun toString(): String = value

  public companion object {
    private val mapping: Map<String, ReportStatus> = entries.associateBy(ReportStatus::value)

    public fun fromValue(`value`: String): ReportStatus? = mapping[value]
  }
}
