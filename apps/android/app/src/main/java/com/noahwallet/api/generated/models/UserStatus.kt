package com.noahwallet.api.generated.models

import kotlin.String
import kotlin.collections.Map
import kotlinx.serialization.SerialName

public enum class UserStatus(
  public val `value`: String,
) {
  @SerialName("active")
  ACTIVE("active"),
  @SerialName("inactive")
  INACTIVE("inactive"),
  @SerialName("deregistered")
  DEREGISTERED("deregistered"),
  ;

  override fun toString(): String = value

  public companion object {
    private val mapping: Map<String, UserStatus> = entries.associateBy(UserStatus::value)

    public fun fromValue(`value`: String): UserStatus? = mapping[value]
  }
}
