package com.noahwallet.services.server

import android.os.Build
import com.noahwallet.BuildConfig
import com.noahwallet.api.generated.models.DeviceInfo as Payload
import com.noahwallet.api.generated.models.RegisterPayloadDeviceInfo
import com.noahwallet.api.generated.models.ReportLastLoginPayloadDeviceInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

object DeviceInfo {
    const val refreshIntervalMillis = 30L * 24 * 60 * 60 * 1000
    // Same Android sources as expo-device and expo-application.
    fun current() = Payload(appBuild = BuildConfig.VERSION_CODE.toString(), appVersion = BuildConfig.VERSION_NAME,
        deviceManufacturer = Build.MANUFACTURER, deviceModel = Build.MODEL,
        osName = Build.VERSION.BASE_OS.takeIf { it.isNotEmpty() } ?: "Android", osVersion = Build.VERSION.RELEASE)

    fun fingerprint(info: Payload): String = JsonArray(listOf(info.deviceManufacturer, info.deviceModel,
        info.osName, info.osVersion, info.appVersion, info.appBuild).map { it?.let(::JsonPrimitive) ?: JsonNull }).toString()

    fun shouldReport(info: Payload, fingerprint: String?, reportedAt: Double?,
                     now: Long = System.currentTimeMillis()): Boolean =
        fingerprint != fingerprint(info) || reportedAt == null || !reportedAt.isFinite() ||
            reportedAt > now || now - reportedAt >= refreshIntervalMillis
}

fun Payload.registrationPayload() = RegisterPayloadDeviceInfo(appBuild, appVersion, deviceManufacturer, deviceModel, osName, osVersion)
fun Payload.loginPayload() = ReportLastLoginPayloadDeviceInfo(appBuild, appVersion, deviceManufacturer, deviceModel, osName, osVersion)
