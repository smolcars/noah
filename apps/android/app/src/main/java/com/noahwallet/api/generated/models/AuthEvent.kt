package com.noahwallet.api.generated.models

import kotlin.String
import kotlin.collections.Map
import kotlinx.serialization.SerialName

/**
 * Represents events that can occur during LNURL-auth.
 */
public enum class AuthEvent(
  public val `value`: String,
) {
  @SerialName("REGISTERED")
  REGISTERED("REGISTERED"),
  ;

  override fun toString(): String = value

  public companion object {
    private val mapping: Map<String, AuthEvent> = entries.associateBy(AuthEvent::value)

    public fun fromValue(`value`: String): AuthEvent? = mapping[value]
  }
}
