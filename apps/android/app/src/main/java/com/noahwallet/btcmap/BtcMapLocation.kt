package com.noahwallet.btcmap

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object BtcMapLocation {
    fun hasPermission(context: Context) = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    // No fused/GMS dependency and no background subscription. The caller owns permission UX.
    @Suppress("MissingPermission")
    suspend fun locate(context: Context): List<Double> = withTimeout(20_000) {
        check(hasPermission(context))
        val manager = context.getSystemService(LocationManager::class.java)
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val providers = listOfNotNull(LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER.takeIf { fine }).filter { manager.isProviderEnabled(it) }
        check(providers.isNotEmpty())
        suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (!continuation.isActive || location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return
                    manager.removeUpdates(this)
                    continuation.resume(listOf(location.longitude, location.latitude))
                }
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
                @Deprecated("""Legacy Android callback""")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            continuation.invokeOnCancellation { manager.removeUpdates(listener) }
            try { providers.forEach { manager.requestLocationUpdates(it, 0L, 0f, listener, Looper.getMainLooper()) } }
            catch (_: Exception) {
                manager.removeUpdates(listener)
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Location unavailable"))
            }
        }
    }
}
