package com.noahwallet.api.generated.models

import kotlin.String
import kotlin.collections.Map
import kotlinx.serialization.SerialName

/**
 * Represents events that can occur during LNURL-auth.
 */
public enum class RegisterResponseEvent(
  public val `value`: String,
) {
  @SerialName("REGISTERED")
  REGISTERED("REGISTERED"),
  ;

  override fun toString(): String = value

  public companion object {
    private val mapping: Map<String, RegisterResponseEvent> =
        entries.associateBy(RegisterResponseEvent::value)

    public fun fromValue(`value`: String): RegisterResponseEvent? = mapping[value]
  }
}
