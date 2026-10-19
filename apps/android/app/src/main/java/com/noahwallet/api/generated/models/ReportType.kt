package com.noahwallet.api.generated.models

import kotlin.String
import kotlin.collections.Map
import kotlinx.serialization.SerialName

public enum class ReportType(
  public val `value`: String,
) {
  @SerialName("maintenance")
  MAINTENANCE("maintenance"),
  @SerialName("backup")
  BACKUP("backup"),
  ;

  override fun toString(): String = value

  public companion object {
    private val mapping: Map<String, ReportType> = entries.associateBy(ReportType::value)

    public fun fromValue(`value`: String): ReportType? = mapping[value]
  }
}
