package com.noahwallet

import android.app.Application
import com.noahwallet.services.logging.AppTelemetry
import com.noahwallet.services.logging.BarkLog

class NoahApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            com.noahwallet.services.wallet.EsploraSettings(com.noahwallet.services.storage.SettingsStorage(this)).restoreLogPrivacy()
        } catch (_: Exception) { com.noahwallet.services.logging.EndpointLogPrivacy.failClosed() }
        BarkLog.install(this)
        AppTelemetry.start(this)
    }
}
