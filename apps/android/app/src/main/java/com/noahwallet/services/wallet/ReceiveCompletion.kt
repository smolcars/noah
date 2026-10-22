package com.noahwallet.services.wallet

import io.github.niteshbalusu11.bark.Movement

sealed interface ReceiveCompletion {
    /** [rail]: which rail it arrived on, for Receive's arrival motion. */
    data class Received(val sats: Long, val rail: Rail) : ReceiveCompletion
    data class LightningFailed(val canceled: Boolean) : ReceiveCompletion
    enum class Rail { ark, lightning }

    companion object {
        fun matching(movement: Movement, request: ReceiveRequest): ReceiveCompletion? {
            if (!movement.subsystemKind.equals("receive", ignoreCase = true)) return null
            return when (movement.subsystemName.lowercase(java.util.Locale.ROOT)) {
                "bark.arkoor" -> {
                    if (movement.status != "successful") return null
                    val destinations = movement.receivedOnAddresses.filter { it.paymentMethod.value == request.arkAddress }
                    if (destinations.isEmpty()) return null
                    var total = 0uL
                    for (destination in destinations) {
                        if (destination.amountSats > Long.MAX_VALUE.toULong() - total) return null
                        total += destination.amountSats
                    }
                    if (total > 0uL) Received(total.toLong(), Rail.ark) else request.amountSat?.let { Received(it, Rail.ark) }
                }
                "bark.lightning_receive" -> {
                    if (request.paymentHash == null || movement.paymentHash != request.paymentHash) return null
                    when (movement.status) {
                        "failed", "canceled" -> LightningFailed(movement.status == "canceled")
                        "successful" -> request.amountSat?.let { Received(it, Rail.lightning) }
                        else -> null
                    }
                }
                else -> null
            }
        }
    }
}
