package com.noahwallet.btcmap

import kotlinx.serialization.Serializable

@Serializable
data class BtcMapBounds(val west: Double, val south: Double, val east: Double, val north: Double) {
    fun contains(lon: Double, lat: Double) = lat in south..north &&
        if (west <= east) lon in west..east else lon >= west || lon <= east

    companion object {
        fun fromCamera(west: Double, south: Double, east: Double, north: Double): BtcMapBounds {
            // MapLibre represents a dateline crossing as e.g. 170...190 instead of 170...-170.
            fun wrap(lon: Double) = ((lon + 180) % 360 + 360) % 360 - 180
            return if (east - west >= 360) BtcMapBounds(-180.0, south, 180.0, north)
            else BtcMapBounds(wrap(west), south, wrap(east), north)
        }
    }
}
