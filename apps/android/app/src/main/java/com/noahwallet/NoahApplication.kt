package com.noahwallet

import android.app.Application
import com.noahwallet.services.logging.BarkLog

class NoahApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        BarkLog.install(this)
    }
}
