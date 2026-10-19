package com.noahwallet.services.wallet

import io.github.niteshbalusu11.bark.Movement

sealed interface ReceiveCompletion {
    data class Received(val sats: Long) : ReceiveCompletion
    data class LightningFailed(val canceled: Boolean) : ReceiveCompletion

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
                    if (total > 0uL) Received(total.toLong()) else request.amountSat?.let(::Received)
                }
                "bark.lightning_receive" -> {
                    if (request.paymentHash == null || movement.paymentHash != request.paymentHash) return null
                    when (movement.status) {
                        "failed", "canceled" -> LightningFailed(movement.status == "canceled")
                        "successful" -> request.amountSat?.let(::Received)
                        else -> null
                    }
                }
                else -> null
            }
        }
    }
}
