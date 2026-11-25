package com.noahwallet.services.wallet

import io.github.niteshbalusu11.bark.Movement

sealed interface ReceiveCompletion {
    /**
     * [rail]: which rail it arrived on, for Receive's arrival motion. [movementId]: bark's movement, so Receive can tell its
     * history row from a payment through your Lightning address.
     */
    data class Received(val sats: Long, val rail: Rail, val movementId: UInt? = null) : ReceiveCompletion
    data class LightningFailed(val canceled: Boolean) : ReceiveCompletion
    enum class Rail { ark, lightning }

    companion object {
        fun matching(movement: Movement, request: ReceiveRequest): ReceiveCompletion? {
            if (!movement.subsystemKind.equals("receive", ignoreCase = true)) return null
            return when (movement.subsystemName.lowercase(java.util.Locale.ROOT)) {
                "bark.arkoor" -> arkoor(movement, request)
                "bark.lightning_receive" -> lightning(movement, request)
                else -> null
            }
        }

        private fun arkoor(movement: Movement, request: ReceiveRequest): ReceiveCompletion? {
            if (movement.status != "successful") return null
            val destinations = movement.receivedOnAddresses.filter { it.paymentMethod.value == request.arkAddress }
            if (destinations.isEmpty()) return null
            var total = 0uL
            for (destination in destinations) {
                if (destination.amountSats > Long.MAX_VALUE.toULong() - total) return null
                total += destination.amountSats
            }
            return if (total > 0uL) Received(total.toLong(), Rail.ark, movement.id) else request.amountSat?.let { Received(it, Rail.ark, movement.id) }
        }

        private fun lightning(movement: Movement, request: ReceiveRequest): ReceiveCompletion? {
            if (request.paymentHash == null || movement.paymentHash != request.paymentHash) return null
            return when (movement.status) {
                "failed", "canceled" -> LightningFailed(movement.status == "canceled")
                "successful" -> request.amountSat?.let { Received(it, Rail.lightning, movement.id) }
                else -> null
            }
        }
    }
}
