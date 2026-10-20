package com.noahwallet

import android.app.Application
import com.noahwallet.services.logging.BarkLog
import com.noahwallet.services.logging.AppTelemetry

class NoahApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        BarkLog.install(this)
        AppTelemetry.start(this)
    }
}
